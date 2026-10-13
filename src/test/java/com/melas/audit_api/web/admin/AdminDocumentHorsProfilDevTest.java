package com.melas.audit_api.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.web.ApiTestSupport;

/** Sans le profil dev, l'URL signée d'un document n'est pas exposée : ni bean, ni route. */
class AdminDocumentHorsProfilDevTest extends ApiTestSupport {

	@Autowired
	private ApplicationContext contexte;

	@Test
	void leControleurNEstPasEnregistre() {
		assertThat(contexte.getEnvironment().getActiveProfiles()).doesNotContain("dev");
		assertThat(contexte.getBeanNamesForType(AdminDocumentController.class)).isEmpty();
	}

	@Test
	void laRouteRepond404PourUnDocumentExistant() {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");

		assertThat(client.get().uri("/api/admin/documents/{id}/url-temporaire", documentId).retrieve()
				.toEntity(String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
