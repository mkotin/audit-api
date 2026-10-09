package com.melas.audit_api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.domain.Document;

class SuppressionDocumentTest extends ApiTestSupport {

	private record Depose(String dossierId, String documentId) {
	}

	private Depose deposerUnPdf() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");
		return new Depose(dossierId, documentId);
	}

	private HttpStatus statutDuGet(String documentId) {
		return HttpStatus.valueOf(client.get().uri("/api/documents/{id}", documentId)
				.retrieve().toEntity(String.class).getStatusCode().value());
	}

	@Test
	void unDocumentRecuEstSupprimeEnBaseEtDansLeStockage() {
		Depose depose = deposerUnPdf();
		assertThat(clesStockees(depose.dossierId())).hasSize(1);

		assertThat(supprimerDocument(depose.documentId())).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(statutDuGet(depose.documentId())).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(clesStockees(depose.dossierId())).isEmpty();
		String dossier = client.get().uri("/api/dossiers/{id}", depose.dossierId()).retrieve().body(String.class);
		assertThat((Integer) JsonPath.read(dossier, "$.documents.length()")).isZero();
	}

	@Test
	void unDocumentEnErreurPeutEtreSupprime() {
		Depose depose = deposerUnPdf();
		modifierDocument(depose.documentId(), document -> {
			document.demarrerTraitement();
			document.marquerErreur("OCR illisible");
		});

		assertThat(supprimerDocument(depose.documentId())).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(statutDuGet(depose.documentId())).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(clesStockees(depose.dossierId())).isEmpty();
	}

	@Test
	void unDocumentEnCoursDeTraitementNePeutPasEtreSupprime() {
		Depose depose = deposerUnPdf();
		modifierDocument(depose.documentId(), Document::demarrerTraitement);

		ResponseEntity<String> reponse = client.delete().uri("/api/documents/{id}", depose.documentId())
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(reponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat((String) JsonPath.read(reponse.getBody(), "$.detail"))
				.contains("EN_COURS")
				.contains("en cours de traitement ou déjà extrait ne peut pas être supprimé");
		assertThat(statutDuGet(depose.documentId())).isEqualTo(HttpStatus.OK);
		assertThat(clesStockees(depose.dossierId())).hasSize(1);
	}

	@Test
	void unDocumentDejaExtraitNePeutPasEtreSupprime() {
		Depose depose = deposerUnPdf();
		modifierDocument(depose.documentId(), document -> {
			document.demarrerTraitement();
			document.marquerExtrait();
		});

		ResponseEntity<String> reponse = client.delete().uri("/api/documents/{id}", depose.documentId())
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat((String) JsonPath.read(reponse.getBody(), "$.detail")).contains("EXTRAIT");
		assertThat(statutDuGet(depose.documentId())).isEqualTo(HttpStatus.OK);
		assertThat(clesStockees(depose.dossierId())).hasSize(1);
	}

	@Test
	void repond404PourUnDocumentInexistant() {
		ResponseEntity<String> reponse = client.delete().uri("/api/documents/{id}", UUID.randomUUID())
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(reponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void leMemeFichierPeutEtreRedeposeApresSuppression() {
		Depose depose = deposerUnPdf();
		assertThat(supprimerDocument(depose.documentId())).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(deposer(depose.dossierId(), pdf()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

}
