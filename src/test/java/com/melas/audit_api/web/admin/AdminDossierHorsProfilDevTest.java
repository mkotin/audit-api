package com.melas.audit_api.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;

import com.melas.audit_api.web.ApiTestSupport;

/** Sans le profil dev, l'administration n'existe pas : ni bean, ni route. */
class AdminDossierHorsProfilDevTest extends ApiTestSupport {

	@Autowired
	private ApplicationContext contexte;

	@Test
	void leControleurDAdministrationNEstPasEnregistre() {
		assertThat(contexte.getEnvironment().getActiveProfiles()).doesNotContain("dev");
		assertThat(contexte.getBeanNamesForType(AdminDossierController.class)).isEmpty();
	}

	@Test
	void laRouteDeSuppressionDeDossierRepond404EtLeDossierEstConserve() {
		String dossierId = creerDossierEtRenvoyerId();
		deposer(dossierId, pdf());

		assertThat(client.delete().uri("/api/admin/dossiers/{id}", dossierId).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(client.get().uri("/api/dossiers/{id}", dossierId).retrieve().toBodilessEntity().getStatusCode())
				.isEqualTo(HttpStatus.OK);
		assertThat(clesStockees(dossierId)).hasSize(1);
	}

}
