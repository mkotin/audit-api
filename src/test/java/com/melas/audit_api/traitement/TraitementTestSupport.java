package com.melas.audit_api.traitement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.web.ApiTestSupport;

/**
 * Base des tests du traitement : traitement automatique activé, et audit-core simulé par un vrai serveur HTTP
 * (WireMock) qui renvoie de vraies réponses d'audit-core, capturées sur la facture dossier_001_doc03 du jeu de test
 * (src/test/resources/core). Profil dev : les endpoints d'administration (JSON d'OCR et d'extraction) sont
 * disponibles.
 *
 * <p>Le serveur écoute sur un port fixe, choisi au chargement de la classe : il peut être arrêté puis relancé
 * (audit-core injoignable) sans que l'URL configurée change. Délai de lecture de 2 s pour tester les délais dépassés.
 */
@ActiveProfiles("dev")
@TestPropertySource(properties = {
		"app.traitement.automatique=true",
		"app.traitement.reprise-au-demarrage=false",
		"app.core.delai-lecture=2s" })
public abstract class TraitementTestSupport extends ApiTestSupport {

	protected static final WireMockServer core = new WireMockServer(options().port(portLibre()));

	static {
		core.start();
	}

	@DynamicPropertySource
	static void urlCore(DynamicPropertyRegistry registre) {
		registre.add("app.core.url", core::baseUrl);
	}

	@BeforeEach
	void reinitialiserCore() {
		if (!core.isRunning()) {
			core.start();
		}
		core.resetAll();
	}

	private static int portLibre() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Contenu d'une réponse d'audit-core enregistrée (src/test/resources/core). */
	protected static String fixture(String nom) {
		try (InputStream contenu = TraitementTestSupport.class.getResourceAsStream("/core/" + nom)) {
			return new String(contenu.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** audit-core répond aux trois appels avec les réponses réelles de la facture. */
	protected void simulerTraitementReussi() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification.json");
		simulerReponse("/v1/extraction", "extraction.json");
	}

	protected void simulerReponse(String chemin, String fixture) {
		core.stubFor(post(urlEqualTo(chemin)).willReturn(okJson(fixture(fixture))));
	}

	protected void simulerProbleme(String chemin, int statut, String fixture) {
		core.stubFor(post(urlEqualTo(chemin)).willReturn(aResponse()
				.withStatus(statut)
				.withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
				.withBody(fixture(fixture))));
	}

	/**
	 * PDF minimal rendu unique par un commentaire PDF ajouté en fin de fichier : plusieurs fichiers d'un même dossier
	 * (un même contenu n'y est accepté qu'une fois).
	 */
	protected static Fichier pdfUnique(String nom) {
		byte[] base = pdf().contenu();
		byte[] suffixe = ("\n%" + UUID.randomUUID() + "\n").getBytes(StandardCharsets.US_ASCII);
		byte[] contenu = new byte[base.length + suffixe.length];
		System.arraycopy(base, 0, contenu, 0, base.length);
		System.arraycopy(suffixe, 0, contenu, base.length, suffixe.length);
		return new Fichier(nom, "application/pdf", contenu);
	}

	/** Dépose un PDF dans un nouveau dossier et renvoie l'identifiant du document créé. */
	protected String deposerUnPdf() {
		return JsonPath.read(deposer(creerDossierEtRenvoyerId(), pdf()).getBody(), "$[0].id");
	}

	/** Attend la fin du traitement (statut EXTRAIT ou ERREUR) et renvoie le document (JSON de GET). */
	protected String attendreFinTraitement(String documentId) {
		return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(100))
				.until(() -> document(documentId),
						corps -> List.of("EXTRAIT", "ERREUR").contains(JsonPath.<String>read(corps, "$.statut")));
	}

	protected String document(String documentId) {
		return client.get().uri("/api/documents/{id}", documentId).retrieve().body(String.class);
	}

	protected static UUID uuid(String id) {
		return UUID.fromString(id);
	}

}
