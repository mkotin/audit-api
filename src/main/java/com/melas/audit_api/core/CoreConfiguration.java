package com.melas.audit_api.core;

import java.net.http.HttpClient;

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
		HttpClient client = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.connectTimeout(proprietes.delaiConnexion())
				.build();
		JdkClientHttpRequestFactory fabrique = new JdkClientHttpRequestFactory(client);
		fabrique.setReadTimeout(proprietes.delaiLecture());
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
