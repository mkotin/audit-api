package com.melas.audit_api.consolidation;

import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(ConsolidationProperties.class)
class ConsolidationConfiguration {

	static final String EXECUTEUR = "executeurConsolidation";

	/**
	 * Exécuteur dédié à la consolidation, distinct de celui du traitement (un afflux de documents à traiter ne retarde
	 * pas les consolidations, et inversement) : {@code parallelisme} threads, file de {@code tailleFile} dossiers,
	 * refus immédiat au-delà (le poller reprend le dossier au passage suivant).
	 *
	 * <p>Le MDC est vidé après chaque tâche, quoi qu'elle ait fait : l'identifiant de corrélation et le dossier d'un
	 * calcul ne fuient jamais dans les journaux du calcul suivant sur le même thread.
	 */
	@Bean(EXECUTEUR)
	ThreadPoolTaskExecutor executeurConsolidation(ConsolidationProperties proprietes) {
		ThreadPoolTaskExecutor executeur = new ThreadPoolTaskExecutor();
		executeur.setCorePoolSize(proprietes.parallelisme());
		executeur.setMaxPoolSize(proprietes.parallelisme());
		executeur.setQueueCapacity(proprietes.tailleFile());
		executeur.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executeur.setThreadNamePrefix("consolidation-");
		executeur.setTaskDecorator(tache -> () -> {
			try {
				tache.run();
			}
			finally {
				MDC.clear();
			}
		});
		return executeur;
	}

	/** Passages du poller à intervalle fixe, seulement si la consolidation automatique est activée. */
	@Bean
	SchedulingConfigurer planificationConsolidation(ConsolidationProperties proprietes, PollerConsolidation poller) {
		return registre -> {
			if (proprietes.automatique()) {
				registre.addFixedDelayTask(poller::passage, proprietes.intervalle());
			}
		};
	}

}
