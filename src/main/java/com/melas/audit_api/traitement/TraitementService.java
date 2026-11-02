package com.melas.audit_api.traitement;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.domain.Classification;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.domain.ResultatOcr;
import com.melas.audit_api.domain.StatutDocument;
import com.melas.audit_api.repository.ChampExtraitRepository;
import com.melas.audit_api.repository.ClassificationRepository;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.repository.ExtractionRepository;
import com.melas.audit_api.repository.ResultatOcrRepository;
import com.melas.audit_api.service.DocumentIntrouvableException;
import com.melas.audit_api.stockage.StockageFichiers;

/**
 * Relances et consultation des résultats du traitement. Le traitement lui-même est asynchrone : une relance fait
 * passer le document EN_COURS (sous verrou, pour qu'une demande concurrente obtienne un 409), puis le confie à
 * l'exécuteur.
 */
@Service
public class TraitementService {

	private static final Logger log = LoggerFactory.getLogger(TraitementService.class);

	static final String MESSAGE_FILE_PLEINE = "File de traitement pleine : relancer le traitement plus tard";

	private final DocumentRepository documents;
	private final ResultatOcrRepository resultatsOcr;
	private final ClassificationRepository classifications;
	private final ExtractionRepository extractions;
	private final ChampExtraitRepository champs;
	private final StockageFichiers stockage;
	private final ExecutionTraitement execution;
	private final TransactionTemplate transaction;

	TraitementService(DocumentRepository documents, ResultatOcrRepository resultatsOcr,
			ClassificationRepository classifications, ExtractionRepository extractions, ChampExtraitRepository champs,
			StockageFichiers stockage, ExecutionTraitement execution, TransactionTemplate transaction) {
		this.documents = documents;
		this.resultatsOcr = resultatsOcr;
		this.classifications = classifications;
		this.extractions = extractions;
		this.champs = champs;
		this.stockage = stockage;
		this.execution = execution;
		this.transaction = transaction;
	}

	/**
	 * Relance le traitement complet (OCR, classification, extraction) d'un document reçu, en erreur ou extrait. Les
	 * passages précédents sont conservés ; les nouveaux deviennent ceux en vigueur.
	 *
	 * @throws DocumentIntrouvableException si le document n'existe pas
	 * @throws TraitementEnCoursException s'il est en cours de traitement
	 * @throws FileTraitementPleineException si la file est pleine (le document passe alors en ERREUR)
	 */
	public void relancerTraitement(UUID id) {
		demarrer(id, document -> {
		});
		soumettre(id, execution::traiter);
		log.info("Traitement complet du document {} relancé", id);
	}

	/**
	 * Relance seulement l'extraction, à partir du dernier résultat d'OCR et du type connu du document, sans refaire
	 * l'OCR ni la classification.
	 *
	 * @throws DocumentIntrouvableException si le document n'existe pas
	 * @throws TraitementEnCoursException s'il est en cours de traitement
	 * @throws ExtractionImpossibleException s'il n'a pas de résultat d'OCR, ou pas de type reconnu
	 * @throws FileTraitementPleineException si la file est pleine (le document passe alors en ERREUR)
	 */
	public void relancerExtraction(UUID id) {
		demarrer(id, document -> {
			if (resultatsOcr.countByDocumentId(id) == 0) {
				throw new ExtractionImpossibleException(
						"Le document %s n'a pas de résultat d'OCR : relancer le traitement complet".formatted(id));
			}
			String type = document.getTypeDocument();
			if (type == null || Classification.TYPE_INCONNU.equals(type)) {
				throw new ExtractionImpossibleException(
						"Le type du document %s n'est pas reconnu : relancer le traitement complet".formatted(id));
			}
		});
		soumettre(id, execution::extraire);
		log.info("Extraction du document {} relancée", id);
	}

	/** Vérifie les conditions puis fait passer le document EN_COURS, dans une transaction et sous verrou. */
	private void demarrer(UUID id, Consumer<Document> verification) {
		transaction.executeWithoutResult(statut -> {
			Document document = documents.findAvecVerrouById(id).orElseThrow(() -> new DocumentIntrouvableException(id));
			if (document.getStatut() == StatutDocument.EN_COURS) {
				throw new TraitementEnCoursException(id);
			}
			verification.accept(document);
			document.relancerTraitement();
		});
	}

	private void soumettre(UUID id, Consumer<UUID> tache) {
		try {
			tache.accept(id);
		}
		catch (TaskRejectedException e) {
			log.warn("File de traitement pleine : le document {} passe en ERREUR", id);
			transaction.executeWithoutResult(statut -> documents.findAvecVerrouById(id)
					.filter(document -> document.getStatut() == StatutDocument.EN_COURS)
					.ifPresent(document -> document.marquerErreur(MESSAGE_FILE_PLEINE)));
			throw new FileTraitementPleineException(id);
		}
	}

	/** Dates des derniers passages et confiance de la dernière classification. */
	@Transactional(readOnly = true)
	public SyntheseTraitement synthese(UUID id) {
		var classification = classifications.findFirstByDocumentIdOrderByDateCreationDesc(id);
		return new SyntheseTraitement(
				classification.map(Classification::getConfiance).orElse(null),
				resultatsOcr.findFirstByDocumentIdOrderByDateCreationDesc(id).map(ResultatOcr::getDateCreation)
						.orElse(null),
				classification.map(Classification::getDateCreation).orElse(null),
				extractions.findFirstByDocumentIdOrderByDateCreationDesc(id).map(Extraction::getDateCreation)
						.orElse(null));
	}

	/**
	 * Champs de la dernière extraction.
	 *
	 * @throws DocumentIntrouvableException si le document n'existe pas
	 * @throws ResultatIntrouvableException s'il n'a encore jamais été extrait
	 */
	@Transactional(readOnly = true)
	public ChampsExtraits champs(UUID id) {
		verifierExistence(id);
		Extraction extraction = extractions.findFirstByDocumentIdOrderByDateCreationDesc(id)
				.orElseThrow(() -> new ResultatIntrouvableException("Aucune extraction pour le document " + id));
		return new ChampsExtraits(extraction, champs.findByExtractionIdOrderByOrdreAsc(extraction.getId()));
	}

	/** JSON complet du dernier OCR, tel qu'audit-core l'a renvoyé. */
	public byte[] jsonDernierOcr(UUID id) {
		String cle = transaction.execute(statut -> {
			verifierExistence(id);
			return resultatsOcr.findFirstByDocumentIdOrderByDateCreationDesc(id)
					.orElseThrow(() -> new ResultatIntrouvableException("Aucun résultat d'OCR pour le document " + id))
					.getCleStockage();
		});
		return lire(cle);
	}

	/** JSON complet de la dernière extraction, tel qu'audit-core l'a renvoyé. */
	public byte[] jsonDerniereExtraction(UUID id) {
		String cle = transaction.execute(statut -> {
			verifierExistence(id);
			return extractions.findFirstByDocumentIdOrderByDateCreationDesc(id)
					.orElseThrow(() -> new ResultatIntrouvableException("Aucune extraction pour le document " + id))
					.getCleStockage();
		});
		return lire(cle);
	}

	private void verifierExistence(UUID id) {
		if (!documents.existsById(id)) {
			throw new DocumentIntrouvableException(id);
		}
	}

	private byte[] lire(String cle) {
		try (InputStream contenu = stockage.lire(cle)) {
			return contenu.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture impossible de " + cle, e);
		}
	}

}
