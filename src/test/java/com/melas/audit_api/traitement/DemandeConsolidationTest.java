package com.melas.audit_api.traitement;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.consolidation.DemandeConsolidation;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.repository.DossierRepository;

/**
 * Chaque changement du dossier visible dans la requête de consolidation demande une consolidation (compteur
 * consolidation_demande_seq incrémenté dans la transaction du changement) ; un changement refusé n'en demande pas,
 * et le démarrage automatique RECU → EN_COURS non plus.
 */
class DemandeConsolidationTest extends TraitementTestSupport {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private RepriseTraitement reprise;

	@Autowired
	private DossierRepository dossiers;

	@Autowired
	private TransactionTemplate transaction;

	@Autowired
	private DemandeConsolidation demande;

	private long demandes(String dossierId) {
		return jdbc.queryForObject("SELECT consolidation_demande_seq FROM dossier WHERE id = ?", Long.class,
				uuid(dossierId));
	}

	private String dossierDe(String documentId) {
		return JsonPath.read(document(documentId), "$.dossierId");
	}

	/** Document créé directement en base, sans dépôt : aucun traitement ni demande. */
	private String documentEnBase(String dossierId) {
		UUID id = UUID.randomUUID();
		transaction.executeWithoutResult(statut -> {
			Dossier dossier = dossiers.findById(uuid(dossierId)).orElseThrow();
			dossier.ajouterDocument(Document.builder()
					.id(id)
					.nomFichierOriginal("facture.pdf")
					.contentType("application/pdf")
					.tailleOctets(1024)
					.cleStockage("dossiers/%s/%s.pdf".formatted(dossierId, id))
					.empreinteSha256("%064x".formatted(id.getLeastSignificantBits() & Long.MAX_VALUE))
					.build());
		});
		return id.toString();
	}

	@Test
	void horsTransactionLaDemandeEstRefusee() {
		assertThatThrownBy(() -> demande.demander(UUID.randomUUID()))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void unDepotDemandeUneConsolidationEtUneSeuleQuelQueSoitLeNombreDeFichiers() {
		// Traitement simulé en échec immédiat (OCR) : seule la fin du traitement de chaque document s'ajoute
		simulerProbleme("/v1/ocr", 422, "probleme_ocr_pdf_illisible.json");
		String dossierId = creerDossierEtRenvoyerId();

		assertThat(deposer(dossierId, pdfUnique("a.pdf"), pdfUnique("b.pdf"), pdfUnique("c.pdf")).getStatusCode())
				.isEqualTo(HttpStatus.CREATED);
		assertThat(demandes(dossierId)).isBetween(1L, 4L);

		for (Object id : JsonPath.<java.util.List<Object>>read(
				client.get().uri("/api/dossiers/{id}", dossierId).retrieve().body(String.class), "$.documents[*].id")) {
			attendreFinTraitement(id.toString());
		}
		// 1 (dépôt) + 3 (fins en ERREUR) ; aucune pour les démarrages RECU → EN_COURS
		assertThat(demandes(dossierId)).isEqualTo(4);
	}

	@Test
	void unDepotRefuseNeDemandeRien() {
		String dossierId = creerDossierEtRenvoyerId();
		Fichier fichier = pdfUnique("a.pdf");

		// le même fichier deux fois dans la requête : doublon, rien n'est créé
		assertThat(deposer(dossierId, fichier, fichier).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(demandes(dossierId)).isZero();
	}

	@Test
	void uneFinDeTraitementEnExtraitDemandeUneConsolidation() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();

		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");

		// dépôt + fin ; le démarrage automatique RECU → EN_COURS n'en demande pas
		assertThat(demandes(dossierDe(documentId))).isEqualTo(2);
	}

	@Test
	void uneFinDeTraitementEnErreurDemandeUneConsolidation() {
		simulerProbleme("/v1/ocr", 422, "probleme_ocr_pdf_illisible.json");
		String documentId = deposerUnPdf();

		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("ERREUR");

		assertThat(demandes(dossierDe(documentId))).isEqualTo(2);
	}

	@Test
	void unTypeInconnuDemandeUneConsolidation() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification_inconnu.json");
		String documentId = deposerUnPdf();

		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.messageErreur"))
				.isEqualTo(TraitementDocument.MESSAGE_TYPE_INCONNU);

		assertThat(demandes(dossierDe(documentId))).isEqualTo(2);
	}

	@Test
	void uneRelanceDuTraitementDemandeUneConsolidation() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);
		String dossierId = dossierDe(documentId);
		assertThat(demandes(dossierId)).isEqualTo(2);
		// relance plus lente, pour lire le compteur pendant qu'elle tourne
		core.stubFor(post(urlEqualTo("/v1/ocr")).willReturn(okJson(fixture("ocr.json")).withFixedDelay(1000)));

		assertThat(client.post().uri("/api/documents/{id}/traitement", documentId).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(demandes(dossierId)).isEqualTo(3);

		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");
		assertThat(demandes(dossierId)).isEqualTo(4);
	}

	@Test
	void uneRelanceDeLExtractionDemandeUneConsolidation() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);
		String dossierId = dossierDe(documentId);
		core.stubFor(post(urlEqualTo("/v1/extraction"))
				.willReturn(okJson(fixture("extraction.json")).withFixedDelay(1000)));

		assertThat(client.post().uri("/api/documents/{id}/extraction", documentId).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(demandes(dossierId)).isEqualTo(3);

		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");
		assertThat(demandes(dossierId)).isEqualTo(4);
	}

	@Test
	void uneRelanceRefuseeNeDemandeRien() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = documentEnBase(dossierId);
		modifierDocument(documentId, Document::demarrerTraitement);

		assertThat(client.post().uri("/api/documents/{id}/traitement", documentId).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(demandes(dossierId)).isZero();
	}

	@Test
	void uneSuppressionDemandeUneConsolidation() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = documentEnBase(dossierId);

		assertThat(supprimerDocument(documentId)).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(demandes(dossierId)).isEqualTo(1);
	}

	@Test
	void uneSuppressionRefuseeNeDemandeRien() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = documentEnBase(dossierId);
		modifierDocument(documentId, Document::demarrerTraitement);

		assertThat(supprimerDocument(documentId)).isEqualTo(HttpStatus.CONFLICT);

		assertThat(demandes(dossierId)).isZero();
	}

	@Test
	void laRepriseDemandeUneConsolidationParDossierTouche() {
		String dossierA = creerDossierEtRenvoyerId();
		String dossierB = creerDossierEtRenvoyerId();
		String dossierC = creerDossierEtRenvoyerId();
		for (String documentId : new String[] { documentEnBase(dossierA), documentEnBase(dossierA),
				documentEnBase(dossierB) }) {
			modifierDocument(documentId, Document::demarrerTraitement);
		}
		documentEnBase(dossierC); // RECU : remis en file, pas interrompu
		core.stubFor(post(urlEqualTo("/v1/ocr")).willReturn(okJson(fixture("ocr.json")).withFixedDelay(3000)));

		reprise.reprendre();

		assertThat(demandes(dossierA)).isEqualTo(1);
		assertThat(demandes(dossierB)).isEqualTo(1);
		assertThat(demandes(dossierC)).isZero();
	}

}
