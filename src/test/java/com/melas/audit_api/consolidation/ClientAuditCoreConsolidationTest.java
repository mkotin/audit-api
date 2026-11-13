package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.melas.audit_api.core.AuditCoreErreurException;
import com.melas.audit_api.core.AuditCoreInjoignableException;
import com.melas.audit_api.core.ClientAuditCore;
import com.melas.audit_api.core.Correlation;
import com.melas.audit_api.core.DelaiDepasseException;
import com.melas.audit_api.core.ReponseBrute;
import com.melas.audit_api.core.dto.RequeteConsolidation;
import com.melas.audit_api.core.dto.ReponseConsolidation;

/** Appel de POST /v1/consolidation : octets conservés, en-têtes, traduction des erreurs, réponse d'un autre dossier. */
class ClientAuditCoreConsolidationTest extends ConsolidationTestSupport {

	@Autowired
	private ClientAuditCore client;

	private static RequeteConsolidation requete() {
		return JSON.treeToValue(requeteContrat(), RequeteConsolidation.class);
	}

	@Test
	void laReponseEstLueEtSesOctetsConserves() {
		simulerConsolidation(reponseContrat());

		ReponseBrute<ReponseConsolidation> reponse = client.consolider(requete());

		assertThat(reponse.json()).isEqualTo(reponseContrat());
		assertThat(reponse.valeur().dossierId()).isEqualTo(DOSSIER_CONTRAT);
		assertThat(reponse.valeur().pieces()).hasSize(8);
		assertThat(reponse.valeur().versionConsolidation()).isNotBlank();
	}

	@Test
	void laRequeteEnvoyeeEstCelleDuContratAvecSesEnTetes() {
		simulerConsolidation(reponseContrat());

		// identifiant de corrélation posé par l'appelant (ConsolidationService, filtre HTTP…)
		MDC.put(Correlation.CLE_MDC, "0123456789abcdef0123456789abcdef");
		try {
			client.consolider(requete());
		}
		finally {
			MDC.remove(Correlation.CLE_MDC);
		}

		core.verify(postRequestedFor(urlEqualTo(CHEMIN))
				.withHeader("X-Request-ID", equalTo("0123456789abcdef0123456789abcdef"))
				.withHeader("Content-Length", matching("[0-9]+"))
				.withHeader("Content-Type", matching("application/json.*")));
		byte[] envoye = core.findAll(postRequestedFor(urlEqualTo(CHEMIN))).getFirst().getBody();
		assertThat(JSON.readTree(envoye)).isEqualTo(requeteContrat());
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 413, 500 })
	void uneErreurDAuditCoreEstTraduite(int statut) {
		simulerErreurConsolidation(statut);

		assertThatThrownBy(() -> client.consolider(requete()))
				.isInstanceOf(AuditCoreErreurException.class)
				.extracting("statut").isEqualTo(statut);
	}

	@Test
	void un504EstUnDelaiDepasse() {
		simulerErreurConsolidation(504);

		assertThatThrownBy(() -> client.consolider(requete())).isInstanceOf(DelaiDepasseException.class);
	}

	@Test
	void uneReponseTropLenteEstUnDelaiDepasse() {
		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody(reponseContrat())
				.withFixedDelay(2500)));

		assertThatThrownBy(() -> client.consolider(requete())).isInstanceOf(DelaiDepasseException.class);
	}

	@Test
	void auditCoreArreteEstInjoignable() {
		core.stop();

		assertThatThrownBy(() -> client.consolider(requete())).isInstanceOf(AuditCoreInjoignableException.class);
	}

	@Test
	void uneReponseIllisibleEstRefusee() {
		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(WireMock.okJson("{pas du json")));

		assertThatThrownBy(() -> client.consolider(requete()))
				.isInstanceOf(AuditCoreErreurException.class)
				.hasMessageContaining("illisible");
	}

	@Test
	void uneReponsePourUnAutreDossierEstRefusee() {
		simulerConsolidation(reponsePour(UUID.randomUUID()));

		assertThatThrownBy(() -> client.consolider(requete()))
				.isInstanceOf(AuditCoreErreurException.class)
				.hasMessageContaining("autre dossier");
	}

}
