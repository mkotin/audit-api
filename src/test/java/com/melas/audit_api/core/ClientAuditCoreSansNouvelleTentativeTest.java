package com.melas.audit_api.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.melas.audit_api.consolidation.ConsolidationTestSupport;
import com.melas.audit_api.core.dto.RequeteConsolidation;

/**
 * Le client d'audit-core ne rejoue jamais un POST de lui-même : un POST d'extraction ou de classification rejoué serait
 * un appel au LLM payé deux fois. Seules nos propres règles de nouvelle tentative (traitement relancé, consolidation
 * différée) décident d'un nouvel appel.
 *
 * <p>Le risque est réel : la bibliothèque Apache HttpClient 5, présente en production (dépendance du SDK S3), rejoue
 * par défaut une requête, même un POST, qui reçoit 503 ou 429 ; Spring la choisit pour tout RestClient construit sans
 * fabrique explicite. audit-core reçoit ici 503 (ou une coupure de connexion) puis répondrait 200 : il ne doit recevoir
 * qu'une requête.
 */
class ClientAuditCoreSansNouvelleTentativeTest extends ConsolidationTestSupport {

	private static final String SCENARIO = "une seule chance";
	private static final String DEUXIEME_APPEL = "deuxième appel";

	@Autowired
	private ClientAuditCore client;

	private record Appel(String chemin, String reponse, Consumer<ClientAuditCore> execution) {

		@Override
		public String toString() {
			return chemin;
		}

	}

	static Stream<Arguments> cas() {
		byte[] ocr = fixture("ocr.json").getBytes(StandardCharsets.UTF_8);
		RequeteConsolidation requete = JSON.treeToValue(requeteContrat(), RequeteConsolidation.class);
		UUID documentId = UUID.randomUUID();
		Stream<Appel> appels = Stream.of(
				new Appel("/v1/ocr", fixture("ocr.json"),
						c -> c.ocr(url("http://stockage-s3:9000/documents/x.pdf"), documentId)),
				new Appel("/v1/classification", fixture("classification.json"), c -> c.classifier(ocr, documentId)),
				new Appel("/v1/extraction", fixture("extraction.json"), c -> c.extraire(ocr, "facture", documentId)),
				new Appel(CHEMIN, new String(reponseContrat(), StandardCharsets.UTF_8), c -> c.consolider(requete)));
		return appels.flatMap(appel -> Stream.of(
				Arguments.of(appel, "503", aResponse()
						.withStatus(503)
						.withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
						.withBody("{\"title\": \"Service indisponible\", \"status\": 503}")),
				Arguments.of(appel, "429", aResponse()
						.withStatus(429)
						.withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
						.withBody("{\"title\": \"Trop de requêtes\", \"status\": 429}")),
				Arguments.of(appel, "connexion coupée", aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))));
	}

	private static java.net.URL url(String adresse) {
		try {
			return URI.create(adresse).toURL();
		}
		catch (java.net.MalformedURLException e) {
			throw new IllegalArgumentException(e);
		}
	}

	@ParameterizedTest(name = "{0}, {1}")
	@MethodSource("cas")
	void unPostEnEchecNEstJamaisRejoue(Appel appel, String echec, ResponseDefinitionBuilder premiereReponse) {
		core.stubFor(post(urlEqualTo(appel.chemin())).inScenario(SCENARIO)
				.whenScenarioStateIs(STARTED)
				.willReturn(premiereReponse)
				.willSetStateTo(DEUXIEME_APPEL));
		core.stubFor(post(urlEqualTo(appel.chemin())).inScenario(SCENARIO)
				.whenScenarioStateIs(DEUXIEME_APPEL)
				.willReturn(okJson(appel.reponse())));

		assertThatThrownBy(() -> appel.execution().accept(client)).isInstanceOf(AuditCoreException.class);

		assertThat(core.findAll(postRequestedFor(urlEqualTo(appel.chemin()))))
				.as("requêtes reçues par audit-core (%s)", echec)
				.hasSize(1);
	}

	/**
	 * Témoin : un RestClient construit sans fabrique explicite (Apache HttpClient 5, choisi par Spring) rejoue bien le
	 * POST après un 503. C'est ce que la fabrique du JDK de CoreConfiguration évite, et ce que le test ci-dessus
	 * détecterait.
	 */
	@org.junit.jupiter.api.Test
	void temoinUnRestClientParDefautRejoueLePostApresUn503() {
		core.stubFor(post(urlEqualTo("/temoin")).inScenario(SCENARIO)
				.whenScenarioStateIs(STARTED)
				.willReturn(aResponse().withStatus(503))
				.willSetStateTo(DEUXIEME_APPEL));
		core.stubFor(post(urlEqualTo("/temoin")).inScenario(SCENARIO)
				.whenScenarioStateIs(DEUXIEME_APPEL)
				.willReturn(okJson("{}")));

		String reponse = org.springframework.web.client.RestClient.builder().baseUrl(core.baseUrl()).build()
				.post().uri("/temoin").body("{}").retrieve().body(String.class);

		assertThat(reponse).isEqualTo("{}");
		assertThat(core.findAll(postRequestedFor(urlEqualTo("/temoin")))).hasSize(2);
	}

}
