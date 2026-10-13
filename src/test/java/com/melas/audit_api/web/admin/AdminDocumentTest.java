package com.melas.audit_api.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.web.ApiTestSupport;

/**
 * Le profil dev désigne le stockage par host.docker.internal dans les URL signées : adresse non joignable depuis
 * le test, d'où l'absence de téléchargement (couvert par DocumentApiTest, sans endpoint-public).
 */
@ActiveProfiles("dev")
class AdminDocumentTest extends ApiTestSupport {

	@Autowired
	private DocumentRepository documentRepository;

	private ResponseEntity<String> urlTemporaire(Object documentId) {
		return client.get().uri("/api/admin/documents/{id}/url-temporaire", documentId).retrieve()
				.toEntity(String.class);
	}

	@Test
	void renvoieLUrlSigneeAvecLAdressePubliqueEtSonExpiration() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");
		String cle = documentRepository.findById(UUID.fromString(documentId)).orElseThrow().getCleStockage();
		Instant avant = Instant.now();

		ResponseEntity<String> reponse = urlTemporaire(documentId);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
		URI url = URI.create(JsonPath.read(reponse.getBody(), "$.url"));
		assertThat(proprietesStockage.endpointPresigner()).isEqualTo(URI.create("http://host.docker.internal:9000"));
		assertThat(url.getScheme() + "://" + url.getAuthority())
				.isEqualTo(proprietesStockage.endpointPresigner().toString());
		assertThat(url.getPath()).isEqualTo("/%s/%s".formatted(proprietesStockage.bucket(), cle));
		assertThat(url.getQuery()).contains("X-Amz-Signature").contains("X-Amz-Expires=900");

		Instant expireA = Instant.parse(JsonPath.read(reponse.getBody(), "$.expireA"));
		assertThat(expireA).isBetween(avant.plus(Duration.ofMinutes(15)).minusSeconds(1),
				Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(1));
	}

	@Test
	void repond404PourUnDocumentInexistant() {
		ResponseEntity<String> reponse = urlTemporaire(UUID.randomUUID());

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(reponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

}
