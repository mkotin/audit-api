package com.melas.audit_api.traitement;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.core.AuditCoreException;
import com.melas.audit_api.core.ClientAuditCore;
import com.melas.audit_api.core.ReponseBrute;
import com.melas.audit_api.core.dto.AlerteCore;
import com.melas.audit_api.core.dto.BoiteEnglobanteCore;
import com.melas.audit_api.core.dto.DocumentExtrait;
import com.melas.audit_api.core.dto.DocumentExtrait.ProvenanceChamp;
import com.melas.audit_api.core.dto.ReponseOcr;
import com.melas.audit_api.core.dto.ResultatClassification;
import com.melas.audit_api.domain.Alerte;
import com.melas.audit_api.domain.BoiteEnglobante;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Classification;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.domain.ResultatOcr;
import com.melas.audit_api.domain.StatutDocument;
import com.melas.audit_api.repository.ClassificationRepository;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.repository.ExtractionRepository;
import com.melas.audit_api.repository.ResultatOcrRepository;
import com.melas.audit_api.service.CleStockage;
import com.melas.audit_api.service.DocumentIntrouvableException;
import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageFichiers;

import tools.jackson.databind.JsonNode;

/**
 * Déroulement du traitement d'un document : OCR, classification, extraction, par appels successifs à audit-core.
 *
 * <p>Aucune transaction ne reste ouverte pendant un appel HTTP ou un accès au stockage : chaque étape enregistre son
 * résultat dans sa propre transaction courte, et ce qui est enregistré le reste si une étape suivante échoue (un OCR
 * réussi n'est pas perdu si l'extraction échoue). Les réponses complètes d'audit-core (OCR, extraction) sont écrites
 * dans le stockage avant la ligne qui les référence ; si cette ligne ne peut pas être enregistrée, le fichier est
 * supprimé.
 *
 * <p>Les méthodes sont appelées sur un thread de l'exécuteur de traitement ({@link ExecutionTraitement}) et ne
 * lèvent pas d'exception : tout échec fait passer le document en ERREUR, avec un message sans détail technique.
 */
@Component
class TraitementDocument {

	private static final Logger log = LoggerFactory.getLogger(TraitementDocument.class);

	static final String MESSAGE_TYPE_INCONNU = "Type de document non reconnu";
	static final String MESSAGE_ERREUR_INTERNE = "Erreur interne pendant le traitement";
	static final String CODE_CLASSIFICATION_PEU_FIABLE = "classification_peu_fiable";

	private enum Etape {
		OCR("OCR"),
		CLASSIFICATION("Classification"),
		EXTRACTION("Extraction");

		private final String libelle;

		Etape(String libelle) {
			this.libelle = libelle;
		}
	}

	/** Ce qu'il faut savoir du document hors transaction. */
	private record Cible(UUID documentId, UUID dossierId, String cleFichier, String typeDocument) {
	}

	/** Point de départ d'une extraction : résultat d'OCR utilisé et confiance de la classification (si connue). */
	private record BaseExtraction(UUID resultatOcrId, String cleOcr, BigDecimal confianceClassification) {
	}

	private final DocumentRepository documents;
	private final ResultatOcrRepository resultatsOcr;
	private final ClassificationRepository classifications;
	private final ExtractionRepository extractions;
	private final StockageFichiers stockage;
	private final ClientAuditCore core;
	private final TransactionTemplate transaction;
	private final TraitementProperties proprietes;

	TraitementDocument(DocumentRepository documents, ResultatOcrRepository resultatsOcr,
			ClassificationRepository classifications, ExtractionRepository extractions, StockageFichiers stockage,
			ClientAuditCore core, TransactionTemplate transaction, TraitementProperties proprietes) {
		this.documents = documents;
		this.resultatsOcr = resultatsOcr;
		this.classifications = classifications;
		this.extractions = extractions;
		this.stockage = stockage;
		this.core = core;
		this.transaction = transaction;
		this.proprietes = proprietes;
	}

	/**
	 * Traitement automatique (après un dépôt, ou à la reprise) : seulement si le document est encore RECU. Un document
	 * déjà pris en charge entre-temps (relance explicite) ou supprimé est ignoré.
	 */
	void traiterSiRecu(UUID documentId) {
		boolean demarre = Boolean.TRUE.equals(transaction.execute(statut -> documents.findAvecVerrouById(documentId)
				.filter(document -> document.getStatut() == StatutDocument.RECU)
				.map(document -> {
					document.demarrerTraitement();
					return true;
				})
				.orElse(false)));
		if (!demarre) {
			log.info("Document {} déjà pris en charge ou supprimé : traitement automatique ignoré", documentId);
			return;
		}
		traiter(documentId);
	}

	/** Traitement complet d'un document EN_COURS : OCR, classification, puis extraction si le type est reconnu. */
	void traiter(UUID documentId) {
		Etape etape = Etape.OCR;
		try {
			log.info("Traitement du document {} : début", documentId);
			Cible cible = cible(documentId);
			ReponseBrute<ReponseOcr> ocr = core.ocr(stockage.genererUrlTemporaire(cible.cleFichier()).url(), documentId);
			ResultatOcr resultatOcr = enregistrerOcr(cible, ocr);

			etape = Etape.CLASSIFICATION;
			ResultatClassification resultat = core.classifier(ocr.json(), documentId);
			Classification classification = enregistrerClassification(documentId, resultatOcr.getId(), resultat);
			if (classification.estInconnu()) {
				log.info("Document {} : type non reconnu, pas d'extraction", documentId);
				terminerEnErreur(documentId, MESSAGE_TYPE_INCONNU);
				return;
			}

			etape = Etape.EXTRACTION;
			extraireEtEnregistrer(cible, classification.getTypeDocument(), ocr.json(),
					new BaseExtraction(resultatOcr.getId(), resultatOcr.getCleStockage(), classification.getConfiance()));
		}
		catch (RuntimeException e) {
			echec(documentId, etape, e);
		}
	}

	/** Extraction seule d'un document EN_COURS, à partir de son dernier résultat d'OCR et de son type. */
	void extraire(UUID documentId) {
		try {
			log.info("Extraction seule du document {} : début", documentId);
			Cible cible = cible(documentId);
			BaseExtraction base = transaction.execute(statut -> {
				ResultatOcr resultatOcr = resultatsOcr.findFirstByDocumentIdOrderByDateCreationDesc(documentId)
						.orElseThrow(() -> new IllegalStateException("Aucun résultat d'OCR pour " + documentId));
				BigDecimal confiance = classifications.findFirstByDocumentIdOrderByDateCreationDesc(documentId)
						.map(Classification::getConfiance)
						.orElse(null);
				return new BaseExtraction(resultatOcr.getId(), resultatOcr.getCleStockage(), confiance);
			});
			extraireEtEnregistrer(cible, cible.typeDocument(), lire(base.cleOcr()), base);
		}
		catch (RuntimeException e) {
			echec(documentId, Etape.EXTRACTION, e);
		}
	}

	private Cible cible(UUID documentId) {
		return transaction.execute(statut -> {
			Document document = documents.findById(documentId)
					.orElseThrow(() -> new DocumentIntrouvableException(documentId));
			return new Cible(documentId, document.getDossier().getId(), document.getCleStockage(),
					document.getTypeDocument());
		});
	}

	private ResultatOcr enregistrerOcr(Cible cible, ReponseBrute<ReponseOcr> ocr) {
		ReponseOcr reponse = ocr.valeur();
		UUID id = UUID.randomUUID();
		String cle = CleStockage.ocr(cible.dossierId(), cible.documentId(), id);
		ecrire(cle, ocr.json());
		try {
			return transaction.execute(statut -> {
				Document document = documents.findById(cible.documentId())
						.orElseThrow(() -> new DocumentIntrouvableException(cible.documentId()));
				document.definirNombrePages(reponse.nombrePages() > 0 ? reponse.nombrePages() : null);
				return resultatsOcr.save(ResultatOcr.builder()
						.id(id)
						.document(document)
						.cleStockage(cle)
						.versionService(reponse.versionService())
						.typeFichier(reponse.typeFichier())
						.methodeGlobale(reponse.methodeGlobale())
						.moteurs(moteurs(reponse))
						.nombrePages(reponse.nombrePages())
						.dureeTelechargementMs(reponse.dureeTelechargementMs())
						.dureeExtractionMs(reponse.dureeExtractionMs())
						.build());
			});
		}
		catch (RuntimeException e) {
			supprimerApresEchec(cle, e);
			throw e;
		}
	}

	private Classification enregistrerClassification(UUID documentId, UUID resultatOcrId,
			ResultatClassification resultat) {
		return transaction.execute(statut -> {
			Document document = documents.findById(documentId)
					.orElseThrow(() -> new DocumentIntrouvableException(documentId));
			document.classer(resultat.typeDocument());
			return classifications.save(Classification.builder()
					.document(document)
					.resultatOcr(resultatsOcr.getReferenceById(resultatOcrId))
					.typeDocument(resultat.typeDocument())
					.confiance(resultat.confiance())
					.typeReference(resultat.typeReference())
					.raison(resultat.raison())
					.blocs(resultat.blocs().stream()
							.map(bloc -> new Classification.BlocCite(bloc.id(), bloc.page()))
							.toList())
					.alertes(alertes(resultat.alertes()))
					.pagesAnalysees(resultat.pagesAnalysees())
					.modele(resultat.modele())
					.fournisseur(resultat.fournisseur())
					.versionConsigne(resultat.versionConsigne())
					.empreinteConsigne(resultat.empreinteConsigne())
					.versionDomaine(resultat.versionDomaine())
					.jetonsEntree(resultat.jetonsEntree())
					.jetonsSortie(resultat.jetonsSortie())
					.dureeMs(resultat.dureeMs())
					.build());
		});
	}

	private void extraireEtEnregistrer(Cible cible, String typeDocument, byte[] ocr, BaseExtraction base) {
		ReponseBrute<DocumentExtrait> reponse = core.extraire(ocr, typeDocument, cible.documentId());
		DocumentExtrait extrait = reponse.valeur();
		UUID id = UUID.randomUUID();
		String cle = CleStockage.extraction(cible.dossierId(), cible.documentId(), id);
		ecrire(cle, reponse.json());
		try {
			transaction.executeWithoutResult(statut -> {
				Document document = documents.findById(cible.documentId())
						.orElseThrow(() -> new DocumentIntrouvableException(cible.documentId()));
				List<Alerte> alertes = new ArrayList<>(alertes(extrait.alertes()));
				alerteClassification(typeDocument, base.confianceClassification()).ifPresent(alertes::add);
				Extraction extraction = Extraction.builder()
						.id(id)
						.document(document)
						.resultatOcr(resultatsOcr.getReferenceById(base.resultatOcrId()))
						.cleStockage(cle)
						.typeDocument(extrait.typeDocument())
						.confiance(extrait.confiance())
						.alertes(alertes)
						.pagesAnalysees(extrait.pagesAnalysees())
						.modele(extrait.modele())
						.fournisseur(extrait.fournisseur())
						.versionConsigne(extrait.versionConsigne())
						.empreinteConsigne(extrait.empreinteConsigne())
						.versionDomaine(extrait.versionDomaine())
						.empreinteDomaine(extrait.empreinteDomaine())
						.jetonsEntree(extrait.jetonsEntree())
						.jetonsSortie(extrait.jetonsSortie())
						.dureeMs(extrait.dureeMs())
						.tentatives(extrait.tentatives())
						.build();
				int ordre = 0;
				for (Map.Entry<String, ProvenanceChamp> entree : extrait.provenance().entrySet()) {
					extraction.ajouterChamp(champ(entree.getKey(), entree.getValue(), ordre++, extraction));
				}
				extractions.save(extraction);
				// Même transaction que les champs : un document n'est jamais EXTRAIT sans eux
				document.marquerExtrait();
			});
		}
		catch (RuntimeException e) {
			supprimerApresEchec(cle, e);
			throw e;
		}
		log.info("Document {} extrait : type {}, {} champ(s), confiance {}", cible.documentId(), typeDocument,
				extrait.provenance().size(), extrait.confiance());
	}

	private Optional<Alerte> alerteClassification(String typeDocument, BigDecimal confiance) {
		if (confiance == null || confiance.compareTo(BigDecimal.valueOf(proprietes.seuilClassification())) >= 0) {
			return Optional.empty();
		}
		log.warn("Classification peu fiable ({} < {}) : extraction faite quand même", confiance,
				proprietes.seuilClassification());
		return Optional.of(new Alerte(CODE_CLASSIFICATION_PEU_FIABLE,
				"Type « %s » retenu avec une confiance de %s, sous le seuil de %s : type à vérifier."
						.formatted(typeDocument, confiance.stripTrailingZeros().toPlainString(),
								BigDecimal.valueOf(proprietes.seuilClassification()).toPlainString()),
				List.of()));
	}

	private static ChampExtrait champ(String chemin, ProvenanceChamp provenance, int ordre, Extraction extraction) {
		BoiteEnglobanteCore bbox = provenance.bbox();
		return ChampExtrait.builder()
				.nom(chemin)
				.valeurBrute(provenance.valeurBrute())
				.valeurNormalisee(texte(provenance.valeurNormalisee()))
				.confiance(BigDecimal.valueOf(provenance.confiance()).setScale(3, RoundingMode.HALF_UP))
				.page(provenance.page())
				.zone(bbox == null ? null : new BoiteEnglobante(bbox.x(), bbox.y(), bbox.largeur(), bbox.hauteur()))
				.alertes(alertes(provenance.alertes()))
				.ordre(ordre)
				.extraitPar(extraction.extracteur())
				.build();
	}

	/** Valeur normalisée d'audit-core (texte, booléen ou null) en texte : "316.80", "2026-03-16", "true". */
	private static String texte(JsonNode valeur) {
		if (valeur == null || valeur.isNull() || valeur.isMissingNode()) {
			return null;
		}
		return valeur.isString() ? valeur.stringValue() : valeur.toString();
	}

	private static List<Alerte> alertes(List<AlerteCore> alertes) {
		if (alertes == null) {
			return List.of();
		}
		return alertes.stream().map(alerte -> new Alerte(alerte.code(), alerte.message(), alerte.champs())).toList();
	}

	/** Moteurs distincts des pages, ex. "pymupdf 1.28.2, tesseract 5.5.0" ; null si aucun. */
	private static String moteurs(ReponseOcr reponse) {
		String moteurs = reponse.pages().stream()
				.map(ReponseOcr.PageOcr::moteur)
				.filter(Objects::nonNull)
				.distinct()
				.collect(Collectors.joining(", "));
		if (moteurs.isEmpty()) {
			return null;
		}
		return moteurs.length() > 255 ? moteurs.substring(0, 255) : moteurs;
	}

	private void ecrire(String cle, byte[] json) {
		stockage.enregistrer(cle, new ByteArrayInputStream(json), json.length, MediaType.APPLICATION_JSON_VALUE);
	}

	private byte[] lire(String cle) {
		try (InputStream contenu = stockage.lire(cle)) {
			return contenu.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture impossible de " + cle, e);
		}
	}

	private void supprimerApresEchec(String cle, RuntimeException cause) {
		try {
			stockage.supprimer(cle);
		}
		catch (RuntimeException e) {
			log.warn("Fichier orphelin dans le stockage : suppression impossible de {} ({})", cle, cause.getMessage(), e);
			cause.addSuppressed(e);
		}
	}

	/**
	 * Fait passer le document en ERREUR. Le message enregistré ne reprend que le titre de l'erreur (sans détail
	 * technique) ; le détail est dans les logs.
	 */
	private void echec(UUID documentId, Etape etape, RuntimeException e) {
		if (e instanceof DocumentIntrouvableException) {
			log.warn("Document {} supprimé pendant son traitement (étape {})", documentId, etape.libelle);
			return;
		}
		String message;
		if (e instanceof AuditCoreException erreur) {
			message = "%s impossible : %s".formatted(etape.libelle, erreur.getTitre());
			log.warn("Document {} en erreur, étape {} : {}", documentId, etape.libelle, erreur.getMessage());
		}
		else if (e instanceof StockageException) {
			message = "%s impossible : stockage des fichiers indisponible".formatted(etape.libelle);
			log.error("Document {} en erreur, étape {} : stockage", documentId, etape.libelle, e);
		}
		else {
			message = MESSAGE_ERREUR_INTERNE;
			log.error("Document {} en erreur, étape {} : erreur inattendue", documentId, etape.libelle, e);
		}
		terminerEnErreur(documentId, message);
	}

	/** EN_COURS → ERREUR ; sans effet (signalé dans les logs) si le document n'est plus en cours ou n'existe plus. */
	void terminerEnErreur(UUID documentId, String message) {
		try {
			transaction.executeWithoutResult(statut -> documents.findAvecVerrouById(documentId).ifPresentOrElse(
					document -> {
						if (document.getStatut() == StatutDocument.EN_COURS) {
							document.marquerErreur(message);
						}
						else {
							log.warn("Document {} au statut {} : message d'erreur non enregistré ({})", documentId,
									document.getStatut(), message);
						}
					},
					() -> log.warn("Document {} supprimé : message d'erreur non enregistré ({})", documentId, message)));
		}
		catch (RuntimeException e) {
			log.error("Impossible d'enregistrer l'erreur du document {} ({}) : il reste EN_COURS jusqu'à la reprise",
					documentId, message, e);
		}
	}

}
