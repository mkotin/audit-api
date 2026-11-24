package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.jayway.jsonpath.JsonPath;

/** Endpoints : recalcul d'administration, résumé dans le dossier, historique et JSON des versions. */
class ConsolidationApiTest extends ConsolidationTestSupport {

	@BeforeEach
	void charger() {
		chargerDossierContrat();
	}

	private ResponseEntity<String> recalculer(Object dossierId) {
		return client.post().uri("/api/admin/dossiers/{id}/consolidation", dossierId).retrieve().toEntity(String.class);
	}

	private String dossier(Object dossierId) {
		return client.get().uri("/api/dossiers/{id}", dossierId).retrieve().body(String.class);
	}

	private ResponseEntity<byte[]> json(String uri, Object... variables) {
		return client.get().uri(uri, variables).retrieve().toEntity(byte[].class);
	}

	@Test
	void leRecalculRenvoieSonIssue() {
		simulerConsolidation(reponseContrat());

		ResponseEntity<String> premier = recalculer(DOSSIER_CONTRAT);
		assertThat(premier.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(JsonPath.<String>read(premier.getBody(), "$.resultat")).isEqualTo("nouvelle_version");
		assertThat(JsonPath.<Integer>read(premier.getBody(), "$.numero")).isEqualTo(1);

		ResponseEntity<String> second = recalculer(DOSSIER_CONTRAT);
		assertThat(JsonPath.<String>read(second.getBody(), "$.resultat")).isEqualTo("inchangee");
		assertThat(JsonPath.<Integer>read(second.getBody(), "$.numero")).isEqualTo(1);

		ResponseEntity<String> vide = recalculer(creerDossierEtRenvoyerId());
		assertThat(JsonPath.<String>read(vide.getBody(), "$.resultat")).isEqualTo("dossier_vide");
		assertThat(JsonPath.<Object>read(vide.getBody(), "$.numero")).isNull();
	}

	@Test
	void lesErreursDAuditCoreSontTraduites() {
		simulerErreurConsolidation(500);
		assertThat(recalculer(DOSSIER_CONTRAT).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

		simulerErreurConsolidation(504);
		assertThat(recalculer(DOSSIER_CONTRAT).getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(aResponse().withStatus(200).withFixedDelay(2500)));
		assertThat(recalculer(DOSSIER_CONTRAT).getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

		core.stop();
		ResponseEntity<String> injoignable = recalculer(DOSSIER_CONTRAT);
		assertThat(injoignable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
		assertThat(JsonPath.<String>read(injoignable.getBody(), "$.detail")).isEqualTo("audit-core injoignable");

		// Un calcul par appel : trois échecs techniques (504, délai dépassé, injoignable), le 500 n'en étant pas un.
		// Avec un client HTTP qui rejoue les POST reçus en 503, l'appel « injoignable » aurait compté deux fois.
		assertThat(jdbc.queryForObject("SELECT consolidation_tentatives FROM dossier WHERE id = ?", Integer.class,
				DOSSIER_CONTRAT)).isEqualTo(3);
	}

	@Test
	void unDossierInconnuRepond404() {
		assertThat(recalculer(UUID.randomUUID()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void leDossierResumeSaConsolidation() {
		assertThat(JsonPath.<String>read(dossier(DOSSIER_CONTRAT), "$.consolidation.statut")).isEqualTo("aucune");
		assertThat(JsonPath.<Object>read(dossier(DOSSIER_CONTRAT), "$.consolidation.numero")).isNull();

		simulerConsolidation(reponseContrat());
		recalculer(DOSSIER_CONTRAT);
		String corps = dossier(DOSSIER_CONTRAT);
		assertThat(JsonPath.<String>read(corps, "$.consolidation.statut")).isEqualTo("a_jour");
		assertThat(JsonPath.<Integer>read(corps, "$.consolidation.numero")).isEqualTo(1);
		assertThat(JsonPath.<String>read(corps, "$.consolidation.date")).isNotNull();
		assertThat(JsonPath.<Integer>read(corps, "$.consolidation.nombreContradictions"))
				.isEqualTo(JSON.readTree(reponseContrat()).get("contradictions").size());

		simulerErreurConsolidation(500);
		recalculer(DOSSIER_CONTRAT);
		assertThat(JsonPath.<String>read(dossier(DOSSIER_CONTRAT), "$.consolidation.statut")).isEqualTo("en_erreur");

		// le dossier créé par l'API a aussi son résumé
		String cree = creerDossier(referenceUnique("dossier")).getBody();
		assertThat(JsonPath.<String>read(cree, "$.consolidation.statut")).isEqualTo("aucune");
	}

	@Test
	void historiqueEtJsonDesVersions() {
		simulerConsolidation(reponseContrat());
		recalculer(DOSSIER_CONTRAT);
		byte[] autre = (new String(reponseContrat(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
		simulerConsolidation(autre);
		recalculer(DOSSIER_CONTRAT);

		String historique = client.get().uri("/api/dossiers/{id}/consolidations", DOSSIER_CONTRAT).retrieve()
				.body(String.class);
		assertThat(JsonPath.<java.util.List<Integer>>read(historique, "$[*].numero")).containsExactly(2, 1);
		assertThat(JsonPath.<Integer>read(historique, "$[0].nombrePieces")).isEqualTo(8);

		ResponseEntity<byte[]> derniere = json("/api/dossiers/{id}/consolidations/derniere", DOSSIER_CONTRAT);
		assertThat(derniere.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(derniere.getHeaders().getFirst("X-Consolidation-Numero")).isEqualTo("2");
		assertThat(derniere.getBody()).isEqualTo(autre);

		ResponseEntity<byte[]> premiere = json("/api/dossiers/{id}/consolidations/{n}", DOSSIER_CONTRAT, 1);
		assertThat(premiere.getHeaders().getFirst("X-Consolidation-Numero")).isEqualTo("1");
		assertThat(premiere.getBody()).isEqualTo(reponseContrat());
	}

	@Test
	void lesVersionsAbsentesRepondent404() {
		assertThat(json("/api/dossiers/{id}/consolidations/derniere", DOSSIER_CONTRAT).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(json("/api/dossiers/{id}/consolidations/{n}", DOSSIER_CONTRAT, 3).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(json("/api/dossiers/{id}/consolidations", UUID.randomUUID()).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(json("/api/dossiers/{id}/consolidations/derniere", UUID.randomUUID()).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

}
