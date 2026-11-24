package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

/**
 * Poller de consolidation, appelé directement (poller planifié désactivé) : regroupement des demandes, un seul calcul
 * à la fois par dossier (endpoint d'administration compris), demande arrivée pendant un calcul, nouvelles tentatives
 * après une erreur technique, pas de nouvelle tentative après un bug.
 */
@TestPropertySource(properties = "app.core.delai-lecture-consolidation=5s")
class PollerConsolidationTest extends ConsolidationTestSupport {

	@Autowired
	private PollerConsolidation poller;

	@Autowired
	private ConsolidationsEnCours enCours;

	@Autowired
	private DemandeConsolidation demande;

	@Autowired
	private TransactionTemplate transaction;

	@BeforeEach
	void charger() {
		chargerDossierContrat();
	}

	/**
	 * Aucun calcul ne survit à son test : sinon, un calcul lent (Docker chargé) enregistrerait son résultat dans le
	 * dossier du contrat rechargé par le test suivant, sous le même identifiant, et fausserait ses compteurs.
	 */
	@AfterEach
	void attendreLaFinDesCalculs() {
		if (!core.isRunning()) {
			core.start();
		}
		attendreFinDuCalcul();
	}

	private void demander() {
		transaction.executeWithoutResult(statut -> demande.demander(DOSSIER_CONTRAT));
	}

	private void attendreFinDuCalcul() {
		await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(50))
				.until(() -> !enCours.estEnCours(DOSSIER_CONTRAT));
	}

	/** Un passage du poller, puis la fin du calcul éventuel ; renvoie le nombre de dossiers soumis. */
	private int passage() {
		int soumis = poller.examiner();
		attendreFinDuCalcul();
		return soumis;
	}

	private int appels() {
		return core.findAll(postRequestedFor(urlEqualTo(CHEMIN))).size();
	}

	private void attendreAppel(int numero) {
		await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() -> appels() >= numero);
	}

	private Map<String, Object> suivi() {
		return jdbc.queryForMap("SELECT consolidation_demande_seq AS demande, consolidation_seq_traitee AS traitee, "
				+ "consolidation_erreur AS erreur, consolidation_prochaine_tentative_le AS prochaine, "
				+ "consolidation_tentatives AS tentatives FROM dossier WHERE id = ?", DOSSIER_CONTRAT);
	}

	private int versions() {
		return jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class,
				DOSSIER_CONTRAT);
	}

	private String statut() {
		return JsonPath.read(client.get().uri("/api/dossiers/{id}", DOSSIER_CONTRAT).retrieve().body(String.class),
				"$.consolidation.statut");
	}

	private void avancerLaProchaineTentative() {
		jdbc.update("UPDATE dossier SET consolidation_prochaine_tentative_le = now() - interval '1 second' "
				+ "WHERE id = ?", DOSSIER_CONTRAT);
	}

	private void simulerConsolidationLente() {
		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody(reponseContrat())
				.withFixedDelay(1500)));
	}

	private static void verifierProchaineTentativeDans(Object prochaine, Duration attente) {
		Instant attendue = Instant.now().plus(attente);
		assertThat(((Timestamp) prochaine).toInstant())
				.isBetween(attendue.minusSeconds(10), attendue.plusSeconds(1));
	}

	@Test
	void sansDemandeRienNEstSoumis() {
		simulerConsolidation(reponseContrat());

		assertThat(passage()).isZero();
		assertThat(appels()).isZero();
	}

	@Test
	void dixDemandesDonnentUnSeulCalcul() {
		simulerConsolidation(reponseContrat());
		for (int i = 0; i < 10; i++) {
			demander();
		}
		assertThat(statut()).isEqualTo("en_attente");

		assertThat(passage()).isEqualTo(1);

		assertThat(appels()).isEqualTo(1);
		assertThat(suivi()).containsEntry("demande", 10L).containsEntry("traitee", 10L);
		assertThat(jdbc.queryForObject("SELECT demande_seq FROM consolidation WHERE dossier_id = ?", Long.class,
				DOSSIER_CONTRAT)).isEqualTo(10);
		assertThat(statut()).isEqualTo("a_jour");
		assertThat(passage()).isZero();
	}

	@Test
	void uneDemandeArriveePendantUnCalculEnProvoqueUnSecondSansNouvelleVersion() {
		simulerConsolidationLente();
		demander();
		assertThat(poller.examiner()).isEqualTo(1);
		attendreAppel(1); // la requête est construite : le compteur (1) est lu

		demander();
		assertThat(poller.examiner()).as("dossier déjà en cours : pas de second calcul").isZero();
		attendreFinDuCalcul();

		assertThat(suivi()).containsEntry("demande", 2L).containsEntry("traitee", 1L);
		assertThat(statut()).isEqualTo("en_attente");
		assertThat(versions()).isEqualTo(1);

		assertThat(passage()).isEqualTo(1);
		assertThat(appels()).isEqualTo(2);
		assertThat(suivi()).containsEntry("traitee", 2L);
		assertThat(versions()).as("résultat inchangé : pas de nouvelle version").isEqualTo(1);
		assertThat(statut()).isEqualTo("a_jour");
	}

	@Test
	void lEndpointDAdministrationRepond409PendantUnCalculDuPoller() {
		simulerConsolidationLente();
		demander();
		assertThat(poller.examiner()).isEqualTo(1);
		attendreAppel(1);

		ResponseEntity<String> reponse = client.post().uri("/api/admin/dossiers/{id}/consolidation", DOSSIER_CONTRAT)
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(JsonPath.<String>read(reponse.getBody(), "$.title")).isEqualTo("Consolidation en cours");
		attendreFinDuCalcul();
		assertThat(appels()).isEqualTo(1);
	}

	@Test
	void lePollerNeLancePasDeCalculPendantCeluiDeLEndpointDAdministration() {
		simulerConsolidationLente();
		CompletableFuture<ResponseEntity<String>> administration = CompletableFuture.supplyAsync(() -> client.post()
				.uri("/api/admin/dossiers/{id}/consolidation", DOSSIER_CONTRAT).retrieve().toEntity(String.class));
		attendreAppel(1);
		demander();

		assertThat(poller.examiner()).isZero();

		assertThat(administration.join().getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(appels()).isEqualTo(1);
		// la demande arrivée pendant le calcul de l'administration reste en attente
		assertThat(suivi()).containsEntry("demande", 1L).containsEntry("traitee", 0L);
		assertThat(passage()).isEqualTo(1);
	}

	@Test
	void uneErreurTechniqueEstReessayeeAvecUnDelaiCroissant() {
		demander();
		core.stop();

		assertThat(passage()).isEqualTo(1);
		Map<String, Object> suivi = suivi();
		assertThat(suivi).containsEntry("tentatives", 1).containsEntry("traitee", 0L)
				.containsEntry("erreur", "Consolidation impossible : audit-core injoignable");
		verifierProchaineTentativeDans(suivi.get("prochaine"), Duration.ofSeconds(30));
		assertThat(statut()).isEqualTo("en_erreur");

		// pas avant l'échéance, même si le dossier change entre-temps
		demander();
		assertThat(passage()).isZero();

		avancerLaProchaineTentative();
		assertThat(passage()).isEqualTo(1);
		suivi = suivi();
		assertThat(suivi).containsEntry("tentatives", 2).containsEntry("traitee", 0L);
		verifierProchaineTentativeDans(suivi.get("prochaine"), Duration.ofMinutes(2));

		core.start();
		simulerConsolidation(reponseContrat());
		avancerLaProchaineTentative();
		assertThat(passage()).isEqualTo(1);

		suivi = suivi();
		assertThat(suivi).containsEntry("tentatives", 0).containsEntry("traitee", 2L).containsEntry("erreur", null)
				.containsEntry("prochaine", null);
		assertThat(versions()).isEqualTo(1);
		assertThat(statut()).isEqualTo("a_jour");
	}

	@ParameterizedTest
	@ValueSource(ints = { 502, 503, 504 })
	void lesReponses502503Et504DAuditCoreSontTechniques(int statut) {
		simulerErreurConsolidation(statut);
		demander();

		assertThat(passage()).isEqualTo(1);

		assertThat(suivi()).containsEntry("tentatives", 1).containsEntry("traitee", 0L);
		assertThat(suivi().get("prochaine")).isNotNull();
	}

	@ParameterizedTest
	@ValueSource(strings = { "400", "500", "illisible" })
	void unBugNEstPasReessaye(String cas) {
		if (cas.equals("illisible")) {
			core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(okJson("{pas du json")));
		}
		else {
			simulerErreurConsolidation(Integer.parseInt(cas));
		}
		demander();

		assertThat(passage()).isEqualTo(1);

		Map<String, Object> suivi = suivi();
		assertThat(suivi).containsEntry("traitee", 1L).containsEntry("tentatives", 0).containsEntry("prochaine", null);
		assertThat((String) suivi.get("erreur")).startsWith("Consolidation impossible : ");
		assertThat(statut()).isEqualTo("en_erreur");
		assertThat(passage()).as("pas de nouvelle tentative").isZero();
		assertThat(appels()).isEqualTo(1);

		// le prochain changement du dossier redéclenche un calcul
		core.resetAll();
		simulerConsolidation(reponseContrat());
		demander();
		assertThat(passage()).isEqualTo(1);
		assertThat(suivi()).containsEntry("traitee", 2L).containsEntry("erreur", null);
		assertThat(statut()).isEqualTo("a_jour");
	}

}
