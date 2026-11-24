package com.melas.audit_api.core;

import java.net.http.HttpClient;
import java.time.Duration;

import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CoreProperties.class)
class CoreConfiguration {

	/**
	 * Client HTTP d'audit-core : délais propres, et identifiant de corrélation du traitement en cours (MDC) transmis
	 * dans X-Request-ID. HTTP/1.1 explicite : le client du JDK tenterait sinon une montée en HTTP/2 (h2c).
	 */
	@Bean
	RestClient restClientCore(CoreProperties proprietes) {
		return client(proprietes, proprietes.delaiLecture());
	}

	/** Même client, avec le délai de lecture propre à la consolidation (court : calcul sans LLM ni réseau). */
	@Bean
	RestClient restClientConsolidation(CoreProperties proprietes) {
		return client(proprietes, proprietes.delaiLectureConsolidation());
	}

	/**
	 * Client du JDK, explicitement : il ne rejoue jamais un POST de lui-même (ni après un statut 503 ou 429, ni après
	 * une coupure de connexion, tant que jdk.httpclient.enableAllMethodRetry n'est pas activé). Ne pas retirer cette
	 * fabrique : Apache HttpClient 5 est présent en production (dépendance du SDK S3), Spring le choisirait par défaut,
	 * et il rejoue une requête, même un POST, qui reçoit 503 ou 429. Un POST d'extraction rejoué serait un appel au LLM
	 * payé deux fois. Seules nos propres règles décident d'un nouvel appel (ClientAuditCoreSansNouvelleTentativeTest).
	 */
	private static RestClient client(CoreProperties proprietes, Duration delaiLecture) {
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(proprietes.delaiConnexion())
				.build();
		JdkClientHttpRequestFactory fabrique = new JdkClientHttpRequestFactory(client);
		fabrique.setReadTimeout(delaiLecture);
		return RestClient.builder()
				.baseUrl(proprietes.url().toString())
				.requestFactory(fabrique)
				.requestInterceptor((requete, corps, execution) -> {
					String correlation = MDC.get(Correlation.CLE_MDC);
					if (correlation != null) {
						requete.getHeaders().set(Correlation.ENTETE, correlation);
					}
					return execution.execute(requete, corps);
				})
				.build();
	}

	/**
	 * JSON d'audit-core (snake_case), propre à ce client. Volontairement pas un bean : Spring Boot n'ajouterait plus
	 * son propre JsonMapper, et toute l'API passerait en snake_case. Les champs inconnus sont ignorés, pour qu'un ajout
	 * côté audit-core ne casse pas audit-api.
	 */
	static JsonMapper jsonMapperCore() {
		return JsonMapper.builder()
				.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.build();
	}

}
