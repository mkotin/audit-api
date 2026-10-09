package com.melas.audit_api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.jayway.jsonpath.JsonPath;

class DossierApiTest extends ApiTestSupport {

	@Test
	void creeUnDossierAvecLEnTeteLocation() {
		String reference = referenceUnique("dossier");

		ResponseEntity<String> reponse = creerDossier(reference);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String id = JsonPath.read(reponse.getBody(), "$.id");
		assertThat(reponse.getHeaders().getLocation()).hasPath("/api/dossiers/" + id);
		assertThat((String) JsonPath.read(reponse.getBody(), "$.referenceExterne")).isEqualTo(reference);
		assertThat((String) JsonPath.read(reponse.getBody(), "$.domaine")).isEqualTo("assurance_habitation");
		assertThat((String) JsonPath.read(reponse.getBody(), "$.typeSinistre")).isEqualTo("degat_des_eaux");
		assertThat((Integer) JsonPath.read(reponse.getBody(), "$.documents.length()")).isZero();
	}

	@Test
	void leTypeDeSinistreEstOptionnel() {
		ResponseEntity<String> reponse = client.post().uri("/api/dossiers")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"referenceExterne": "%s", "domaine": "auto"}""".formatted(referenceUnique("dossier")))
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((Object) JsonPath.read(reponse.getBody(), "$.typeSinistre")).isNull();
	}

	@Test
	void refuseUneReferenceDejaUtilisee() {
		String reference = referenceUnique("dossier");
		creerDossier(reference);

		ResponseEntity<String> reponse = creerDossier(reference);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(reponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat((Integer) JsonPath.read(reponse.getBody(), "$.status")).isEqualTo(409);
	}

	@Test
	void refuseUnDossierSansDomaine() {
		ResponseEntity<String> reponse = client.post().uri("/api/dossiers")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"referenceExterne": "%s"}""".formatted(referenceUnique("dossier")))
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void relitUnDossierParIdEtParReferenceAvecSesDocuments() {
		String reference = referenceUnique("dossier");
		String id = JsonPath.read(creerDossier(reference).getBody(), "$.id");
		deposer(id, pdf());

		for (ResponseEntity<String> reponse : List.of(
				client.get().uri("/api/dossiers/{id}", id).retrieve().toEntity(String.class),
				client.get().uri("/api/dossiers?reference={ref}", reference).retrieve().toEntity(String.class))) {
			assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat((String) JsonPath.read(reponse.getBody(), "$.id")).isEqualTo(id);
			assertThat((Integer) JsonPath.read(reponse.getBody(), "$.documents.length()")).isEqualTo(1);
			assertThat((String) JsonPath.read(reponse.getBody(), "$.documents[0].nomFichierOriginal"))
					.isEqualTo("devis plombier.pdf");
			assertThat((String) JsonPath.read(reponse.getBody(), "$.documents[0].statut")).isEqualTo("RECU");
		}
	}

	@Test
	void repond404PourUnDossierInconnu() {
		assertThat(client.get().uri("/api/dossiers/{id}", UUID.randomUUID()).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(client.get().uri("/api/dossiers?reference=inconnu").retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
