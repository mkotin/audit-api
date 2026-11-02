package com.melas.audit_api.traitement;

import java.util.concurrent.ThreadPoolExecutor;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties(TraitementProperties.class)
class TraitementConfiguration {

	static final String EXECUTEUR = "executeurTraitement";

	/**
	 * Exécuteur dédié et borné : {@code parallelisme} threads, file de {@code tailleFile} documents. File pleine : la
	 * soumission échoue immédiatement (TaskRejectedException), à charge de l'appelant de le signaler. À l'arrêt, les
	 * traitements en cours ne sont pas attendus : la reprise au démarrage passe leurs documents en ERREUR.
	 */
	@Bean(EXECUTEUR)
	ThreadPoolTaskExecutor executeurTraitement(TraitementProperties proprietes) {
		ThreadPoolTaskExecutor executeur = new ThreadPoolTaskExecutor();
		executeur.setCorePoolSize(proprietes.parallelisme());
		executeur.setMaxPoolSize(proprietes.parallelisme());
		executeur.setQueueCapacity(proprietes.tailleFile());
		executeur.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
		executeur.setThreadNamePrefix("traitement-");
		return executeur;
	}

}
