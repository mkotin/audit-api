package com.melas.audit_api.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.repository.DossierRepository;
import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageFichiers;
import com.melas.audit_api.stockage.UrlTemporaire;

/**
 * Dépôt et consultation des documents d'un dossier.
 *
 * <p>Un dépôt est atomique : soit tous les fichiers de la requête sont stockés et enregistrés, soit aucun.
 * Les fichiers sont écrits dans le stockage <em>avant</em> la transaction en base (aucune transaction ne reste
 * ouverte pendant les transferts) ; en cas d'échec, les fichiers déjà écrits sont supprimés.
 */
@Service
public class DocumentService {

	private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

	static final String CONTRAINTE_EMPREINTE_UNIQUE = "uk_document_dossier_empreinte";
	private static final int LONGUEUR_MAX_NOM = 500;

	private final DossierRepository dossierRepository;
	private final DocumentRepository documentRepository;
	private final StockageFichiers stockage;
	private final DetecteurTypeFichier detecteur;
	private final TransactionTemplate transaction;

	public DocumentService(DossierRepository dossierRepository, DocumentRepository documentRepository,
			StockageFichiers stockage, DetecteurTypeFichier detecteur, TransactionTemplate transaction) {
		this.dossierRepository = dossierRepository;
		this.documentRepository = documentRepository;
		this.stockage = stockage;
		this.detecteur = detecteur;
		this.transaction = transaction;
	}

	@Transactional(readOnly = true)
	public Document trouver(UUID id) {
		return documentRepository.findById(id).orElseThrow(() -> new DocumentIntrouvableException(id));
	}

	/** Ouvre le fichier d'un document ; l'appelant doit fermer le flux. */
	public InputStream lireFichier(Document document) {
		return stockage.lire(document.getCleStockage());
	}

	/**
	 * URL signée donnant un accès temporaire au fichier du document, sans identifiants.
	 *
	 * @throws DocumentIntrouvableException si le document n'existe pas
	 */
	public UrlTemporaire genererUrlTemporaire(UUID id) {
		return stockage.genererUrlTemporaire(trouver(id).getCleStockage());
	}

	/**
	 * Supprime un document reçu ou en erreur : la ligne en base d'abord, puis le fichier. Si le fichier ne peut
	 * pas être supprimé, la suppression en base reste acquise et le fichier orphelin est signalé dans les logs.
	 *
	 * @throws DocumentIntrouvableException si le document n'existe pas
	 * @throws DocumentNonSupprimableException s'il est en cours de traitement ou déjà extrait
	 */
	public void supprimer(UUID id) {
		String cle = transaction.execute(statut -> {
			Document document = documentRepository.findById(id).orElseThrow(() -> new DocumentIntrouvableException(id));
			if (!document.estSupprimable()) {
				throw new DocumentNonSupprimableException(id, document.getStatut());
			}
			// Suppression conditionnelle : si le statut a changé depuis la lecture (prise en charge par le
			// traitement), rien n'est supprimé et la relecture donne la raison.
			if (documentRepository.supprimerSiStatutParmi(id, Document.STATUTS_SUPPRIMABLES) == 0) {
				Document relu = documentRepository.findById(id).orElseThrow(() -> new DocumentIntrouvableException(id));
				throw new DocumentNonSupprimableException(id, relu.getStatut());
			}
			return document.getCleStockage();
		});
		try {
			stockage.supprimer(cle);
		}
		catch (StockageException e) {
			log.error("Fichier orphelin dans le stockage : clé {} (document {} supprimé en base)", cle, id, e);
		}
	}

	/**
	 * Dépose les fichiers dans le dossier. Tous les fichiers sont validés (non vides, type accepté) avant la
	 * moindre écriture ; les doublons (même contenu déjà présent dans le dossier ou répété dans la requête)
	 * sont détectés après écriture, l'empreinte étant calculée pendant le transfert.
	 *
	 * @return les documents créés, au statut RECU, dans l'ordre des fichiers reçus
	 */
	public List<Document> deposer(UUID dossierId, List<MultipartFile> fichiers) {
		if (!dossierRepository.existsById(dossierId)) {
			throw new DossierIntrouvableException(dossierId);
		}
		if (fichiers == null || fichiers.isEmpty()) {
			throw new FichierInvalideException("Aucun fichier reçu dans le champ \"fichiers\"");
		}
		List<FichierValide> valides = fichiers.stream().map(this::valider).toList();

		List<String> clesEcrites = new ArrayList<>();
		try {
			List<Document> documents = new ArrayList<>();
			Set<String> empreintesDeLaRequete = new HashSet<>();
			for (FichierValide fichier : valides) {
				Document document = stocker(dossierId, fichier, clesEcrites);
				if (!empreintesDeLaRequete.add(document.getEmpreinteSha256())
						|| documentRepository.existsByDossierIdAndEmpreinteSha256(dossierId,
								document.getEmpreinteSha256())) {
					throw new DocumentEnDoublonException(fichier.nomOriginal());
				}
				documents.add(document);
			}
			return enregistrer(dossierId, documents, valides);
		}
		catch (RuntimeException e) {
			supprimerFichiers(clesEcrites, e);
			throw e;
		}
	}

	private record FichierValide(MultipartFile fichier, String nomOriginal, TypeFichier type) {
	}

	private FichierValide valider(MultipartFile fichier) {
		String nom = nomOriginal(fichier);
		if (fichier.isEmpty()) {
			throw new FichierInvalideException("Le fichier \"%s\" est vide".formatted(nom));
		}
		try (InputStream contenu = fichier.getInputStream()) {
			TypeFichier type = detecteur.detecter(contenu)
					.orElseThrow(() -> new TypeFichierNonSupporteException(nom));
			return new FichierValide(fichier, nom, type);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture impossible du fichier reçu \"%s\"".formatted(nom), e);
		}
	}

	/** Écrit le fichier dans le stockage en calculant son empreinte au passage. */
	private Document stocker(UUID dossierId, FichierValide fichier, List<String> clesEcrites) {
		UUID documentId = UUID.randomUUID();
		String cle = CleStockage.pour(dossierId, documentId, fichier.type());
		EmpreinteSha256 empreinte = new EmpreinteSha256();
		try (InputStream contenu = fichier.fichier().getInputStream()) {
			stockage.enregistrer(cle, empreinte.envelopper(contenu), fichier.fichier().getSize(),
					fichier.type().mimeType());
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture impossible du fichier reçu \"%s\"".formatted(fichier.nomOriginal()), e);
		}
		clesEcrites.add(cle);
		return Document.builder()
				.id(documentId)
				.nomFichierOriginal(fichier.nomOriginal())
				.contentType(fichier.type().mimeType())
				.tailleOctets(fichier.fichier().getSize())
				.cleStockage(cle)
				.empreinteSha256(empreinte.valeurHex())
				.build();
	}

	private List<Document> enregistrer(UUID dossierId, List<Document> documents, List<FichierValide> fichiers) {
		try {
			return transaction.execute(statut -> {
				Dossier dossier = dossierRepository.findById(dossierId)
						.orElseThrow(() -> new DossierIntrouvableException(dossierId));
				documents.forEach(dossier::ajouterDocument);
				return documentRepository.saveAllAndFlush(documents);
			});
		}
		catch (DataIntegrityViolationException e) {
			// Dépôt concurrent du même fichier, entre la vérification et l'insertion : l'index unique tranche.
			if (ViolationContrainte.concerne(e, CONTRAINTE_EMPREINTE_UNIQUE)) {
				throw new DocumentEnDoublonException(
						fichiers.size() == 1 ? fichiers.getFirst().nomOriginal() : "(dépôt multiple)");
			}
			throw e;
		}
	}

	private void supprimerFichiers(List<String> cles, RuntimeException cause) {
		for (String cle : cles) {
			try {
				stockage.supprimer(cle);
			}
			catch (RuntimeException e) {
				log.warn("Fichier orphelin dans le stockage : suppression impossible de {} après l'échec du dépôt ({})",
						cle, cause.getMessage(), e);
				cause.addSuppressed(e);
			}
		}
	}

	/** Nom d'origine sans chemin (certains navigateurs l'envoient), tronqué à la taille de la colonne. */
	private static String nomOriginal(MultipartFile fichier) {
		String nom = fichier.getOriginalFilename();
		if (!StringUtils.hasText(nom)) {
			return "fichier";
		}
		nom = nom.replace('\\', '/');
		nom = nom.substring(nom.lastIndexOf('/') + 1).strip();
		if (nom.isEmpty()) {
			return "fichier";
		}
		return nom.length() > LONGUEUR_MAX_NOM ? nom.substring(0, LONGUEUR_MAX_NOM) : nom;
	}

}
