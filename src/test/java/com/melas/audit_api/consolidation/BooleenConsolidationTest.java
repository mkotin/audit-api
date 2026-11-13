package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.core.dto.RequeteConsolidation;

import tools.jackson.databind.node.ObjectNode;

/**
 * Format des booléens dans valeur_normalisee, de bout en bout : un booléen JSON renvoyé par POST /v1/extraction est
 * enregistré, puis envoyé à la consolidation, sous la forme "true", que lecture.retyper d'audit-core accepte.
 */
class BooleenConsolidationTest extends ConsolidationTestSupport {

	@Autowired
	private ConstructionRequete construction;

	@Autowired
	private TransactionTemplate transaction;

	@Test
	void unBooleenExtraitEstEnvoyeSousLaFormeTrue() {
		ObjectNode extraction = (ObjectNode) JSON.readTree(fixture("extraction.json"));
		ObjectNode provenance = ((ObjectNode) extraction.get("provenance")).putObject("champ_booleen_de_test");
		provenance.put("valeur_brute", "XOuiNon");
		provenance.put("valeur_normalisee", true);
		provenance.putNull("bloc");
		provenance.putArray("blocs");
		provenance.putNull("page");
		provenance.putNull("bbox");
		provenance.put("confiance", 0.8);
		provenance.putArray("alertes");
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification.json");
		core.stubFor(post(urlEqualTo("/v1/extraction")).willReturn(okJson(JSON.writeValueAsString(extraction))));

		String documentId = deposerUnPdf();
		assertThat(JsonPath.<String>read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");
		UUID dossierId = jdbc.queryForObject("SELECT dossier_id FROM document WHERE id = ?", UUID.class,
				uuid(documentId));

		assertThat(jdbc.queryForObject("SELECT valeur_normalisee FROM champ_extrait WHERE nom = ? AND document_id = ?",
				String.class, "champ_booleen_de_test", uuid(documentId))).isEqualTo("true");
		RequeteConsolidation requete = transaction.execute(statut -> construction.construire(dossierId).requete());
		RequeteConsolidation.Champ champ = requete.pieces().getFirst().extraction().champs().stream()
				.filter(c -> c.chemin().equals("champ_booleen_de_test"))
				.findFirst()
				.orElseThrow();
		assertThat(champ.valeurNormalisee()).isEqualTo("true");
	}

}
