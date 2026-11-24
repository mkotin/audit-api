package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;

/** Poller réellement planifié (toutes les 200 ms) : reprise des demandes en base, et dépôt de dix documents. */
@TestPropertySource(properties = { "app.consolidation.automatique=true", "app.consolidation.intervalle=200ms" })
class ConsolidationAutomatiqueTest extends ConsolidationTestSupport {

	private int versions(UUID dossierId) {
		return jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class,
				dossierId);
	}

	/**
	 * Une demande déjà en base, que rien en mémoire ne connaît : c'est la situation d'un redémarrage (l'ensemble des
	 * calculs en cours est vide au démarrage, le poller ne lit que la base). Elle est reprise sans intervention.
	 */
	@Test
	void uneDemandeEnBaseEstRepriseSansIntervention() {
		simulerConsolidation(reponseContrat());
		chargerDossierContrat();

		jdbc.update("UPDATE dossier SET consolidation_demande_seq = 3 WHERE id = ?", DOSSIER_CONTRAT);

		await().atMost(Duration.ofSeconds(15)).until(() -> versions(DOSSIER_CONTRAT) == 1);
		assertThat(jdbc.queryForObject("SELECT consolidation_seq_traitee FROM dossier WHERE id = ?", Long.class,
				DOSSIER_CONTRAT)).isEqualTo(3);
	}

	@Test
	void dixDocumentsDeposesEnsembleFinissentParUneConsolidationAJour() {
		simulerTraitementReussi();
		String dossierId = creerDossierEtRenvoyerId();
		simulerConsolidation(reponsePour(uuid(dossierId)));
		Fichier[] fichiers = new Fichier[10];
		for (int i = 0; i < fichiers.length; i++) {
			fichiers[i] = pdfUnique("piece-" + i + ".pdf");
		}

		assertThat(deposer(dossierId, fichiers).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> {
			String dossier = client.get().uri("/api/dossiers/{id}", dossierId).retrieve().body(String.class);
			List<String> statuts = JsonPath.read(dossier, "$.documents[*].statut");
			return statuts.size() == 10 && statuts.stream().allMatch("EXTRAIT"::equals)
					&& "a_jour".equals(JsonPath.read(dossier, "$.consolidation.statut"));
		});

		// Le dernier calcul a vu l'état final : dix pièces extraites
		List<LoggedRequest> requetes = core.findAll(postRequestedFor(urlEqualTo(CHEMIN))).stream()
				.filter(requete -> JSON.readTree(requete.getBody()).get("dossier_id").stringValue().equals(dossierId))
				.toList();
		JsonNode derniere = JSON.readTree(requetes.getLast().getBody());
		assertThat(derniere.get("pieces")).hasSize(10);
		for (JsonNode piece : derniere.get("pieces")) {
			assertThat(piece.get("statut").stringValue()).isEqualTo("EXTRAIT");
			assertThat(piece.get("extraction").isNull()).isFalse();
		}
		assertThat(versions(uuid(dossierId))).isEqualTo(1);
	}

}
