package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.core.Correlation;

/**
 * Le MDC est vidé après chaque tâche de l'exécuteur de consolidation : rien ne fuit d'un calcul à l'autre sur le même
 * thread. Exécuteur d'un seul thread, pour que les tâches successives s'y exécutent toutes.
 */
@TestPropertySource(properties = "app.consolidation.parallelisme=1")
class MdcConsolidationTest extends ConsolidationTestSupport {

	@Autowired
	@Qualifier(ConsolidationConfiguration.EXECUTEUR)
	private ThreadPoolTaskExecutor executeur;

	@Autowired
	private PollerConsolidation poller;

	@Autowired
	private ConsolidationsEnCours enCours;

	@Autowired
	private DemandeConsolidation demande;

	@Autowired
	private TransactionTemplate transaction;

	private record Observation(String thread, Map<String, String> mdc) {
	}

	/** Thread et MDC vus par une tâche de l'exécuteur. */
	private Observation observer() {
		return executeur.submitCompletable(() -> new Observation(Thread.currentThread().getName(),
				MDC.getCopyOfContextMap())).join();
	}

	@Test
	void uneTacheQuiLaisseDesValeursDansLeMdcNeLesTransmetPasALaSuivante() {
		String thread = executeur.submitCompletable(() -> {
			MDC.put(Correlation.CLE_MDC, "fuite");
			MDC.put(ConsolidationService.CLE_MDC_DOSSIER, "fuite");
			MDC.put("autre", "fuite");
			return Thread.currentThread().getName();
		}).join();

		Observation suivante = observer();

		assertThat(suivante.thread()).isEqualTo(thread);
		assertThat(suivante.mdc()).isNullOrEmpty();
	}

	@Test
	void apresUnCalculDuPollerLeMdcEstVide() {
		chargerDossierContrat();
		simulerConsolidation(reponseContrat());
		transaction.executeWithoutResult(statut -> demande.demander(DOSSIER_CONTRAT));
		AtomicReference<String> threadDuCalcul = new AtomicReference<>();
		executeur.submitCompletable(() -> {
			threadDuCalcul.set(Thread.currentThread().getName());
			return null;
		}).join();

		assertThat(poller.examiner()).isEqualTo(1);
		await().atMost(Duration.ofSeconds(10)).until(() -> !enCours.estEnCours(DOSSIER_CONTRAT));

		Observation suivante = observer();
		assertThat(suivante.thread()).isEqualTo(threadDuCalcul.get());
		assertThat(suivante.mdc()).isNullOrEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class,
				DOSSIER_CONTRAT)).isEqualTo(1);
	}

}
