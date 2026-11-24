package com.melas.audit_api.traitement;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.repository.DossierRepository;

/**
 * File de traitement pleine lors d'une relance : le document passe EN_COURS (une demande) puis ERREUR (une seconde
 * demande). Exécuteur d'un seul thread, sans file : une relance pendant qu'un traitement tourne est refusée.
 */
@TestPropertySource(properties = { "app.traitement.parallelisme=1", "app.traitement.taille-file=0" })
class DemandeConsolidationFilePleineTest extends TraitementTestSupport {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private DossierRepository dossiers;

	@Autowired
	private TransactionTemplate transaction;


	private String documentEnBase(String dossierId) {
		UUID id = UUID.randomUUID();
		transaction.executeWithoutResult(statut -> dossiers.findById(uuid(dossierId)).orElseThrow()
				.ajouterDocument(Document.builder()
						.id(id)
						.nomFichierOriginal("facture.pdf")
						.contentType("application/pdf")
						.tailleOctets(1024)
						.cleStockage("dossiers/%s/%s.pdf".formatted(dossierId, id))
						.empreinteSha256("%064x".formatted(id.getLeastSignificantBits() & Long.MAX_VALUE))
						.build()));
		return id.toString();
	}

	private long demandes(String dossierId) {
		return jdbc.queryForObject("SELECT consolidation_demande_seq FROM dossier WHERE id = ?", Long.class,
				uuid(dossierId));
	}

	@Test
	void unPassageEnErreurFilePleineDemandeUneConsolidation() {
		core.stubFor(post(urlEqualTo("/v1/ocr")).willReturn(okJson(fixture("ocr.json")).withFixedDelay(3000)));
		String dossierId = creerDossierEtRenvoyerId();
		String occupe = documentEnBase(dossierId);
		String refuse = documentEnBase(dossierId);

		assertThat(client.post().uri("/api/documents/{id}/traitement", occupe).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(demandes(dossierId)).isEqualTo(1);

		// Le client HTTP des tests ne rejoue pas une requête reçue en 503 (ApiTestSupport) : une seule relance
		assertThat(client.post().uri("/api/documents/{id}/traitement", refuse).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

		assertThat(JsonPath.<String>read(document(refuse), "$.statut")).isEqualTo("ERREUR");
		// relance du premier (1), relance du second (1), passage du second en ERREUR (1)
		assertThat(demandes(dossierId)).isEqualTo(3);
		attendreFinTraitement(occupe);
	}

}
