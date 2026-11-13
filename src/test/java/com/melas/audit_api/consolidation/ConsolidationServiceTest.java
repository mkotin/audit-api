package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.melas.audit_api.consolidation.ResultatConsolidation.Issue;
import com.melas.audit_api.core.AuditCoreErreurException;

import tools.jackson.databind.JsonNode;

/** Enregistrement des versions : nouvelle, inchangée, périmée, dossier vide, échec noté puis effacé. */
class ConsolidationServiceTest extends ConsolidationTestSupport {

	@Autowired
	private ConsolidationService service;

	@BeforeEach
	void charger() {
		chargerDossierContrat();
	}

	private static String sha256(byte[] octets) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(octets));
	}

	private Map<String, Object> version(UUID dossierId, int numero) {
		return jdbc.queryForMap("SELECT * FROM consolidation WHERE dossier_id = ? AND numero = ?", dossierId, numero);
	}

	private int nombreDeVersions(UUID dossierId) {
		return jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class, dossierId);
	}

	private byte[] lire(String cle) throws IOException {
		try (InputStream contenu = stockage.lire(cle)) {
			return contenu.readAllBytes();
		}
	}

	@Test
	void unPremierCalculCreeLaVersion1() throws Exception {
		simulerConsolidation(reponseContrat());

		ResultatConsolidation resultat = service.consolider(DOSSIER_CONTRAT);

		assertThat(resultat.issue()).isEqualTo(Issue.NOUVELLE_VERSION);
		assertThat(resultat.numero()).isEqualTo(1);
		Map<String, Object> version = version(DOSSIER_CONTRAT, 1);
		String cle = (String) version.get("cle_stockage");
		assertThat(cle).startsWith("dossiers/" + DOSSIER_CONTRAT + "/consolidation/").endsWith(".json");
		assertThat(lire(cle)).isEqualTo(reponseContrat());
		assertThat(version.get("empreinte_resultat")).isEqualTo(sha256(reponseContrat()));
		assertThat(version.get("demande_seq")).isEqualTo(0L);
		assertThat(version.get("nombre_pieces")).isEqualTo(8);
		assertThat((String) version.get("correlation_id")).matches("[0-9a-f]{32}");

		JsonNode reponse = JSON.readTree(reponseContrat());
		JsonNode index = JSON.readTree(version.get("contradictions").toString());
		assertThat(index).hasSize(reponse.get("contradictions").size());
		assertThat(version.get("nombre_contradictions")).isEqualTo(reponse.get("contradictions").size());
		if (!index.isEmpty()) {
			assertThat(index.get(0).get("code").stringValue())
					.isEqualTo(reponse.get("contradictions").get(0).get("code").stringValue());
		}
		assertThat(version.get("nombre_liens")).isEqualTo(reponse.get("liens").size());

		// X-Request-ID transmis : celui de la version
		assertThat(core.findAll(postRequestedFor(urlEqualTo(CHEMIN))).getFirst().getHeader("X-Request-ID"))
				.isEqualTo(version.get("correlation_id"));
	}

	@Test
	void unResultatIdentiqueNeCreeRien() {
		simulerConsolidation(reponseContrat());
		service.consolider(DOSSIER_CONTRAT);

		ResultatConsolidation resultat = service.consolider(DOSSIER_CONTRAT);

		assertThat(resultat.issue()).isEqualTo(Issue.INCHANGEE);
		assertThat(resultat.numero()).isEqualTo(1);
		assertThat(nombreDeVersions(DOSSIER_CONTRAT)).isEqualTo(1);
		assertThat(clesConsolidation(DOSSIER_CONTRAT)).hasSize(1);
	}

	@Test
	void unResultatDifferentCreeLaVersionSuivante() {
		simulerConsolidation(reponseContrat());
		service.consolider(DOSSIER_CONTRAT);
		// mêmes données, octets différents : autre empreinte
		byte[] autre = (new String(reponseContrat(), StandardCharsets.UTF_8) + "\n").getBytes(StandardCharsets.UTF_8);
		simulerConsolidation(autre);

		ResultatConsolidation resultat = service.consolider(DOSSIER_CONTRAT);

		assertThat(resultat.issue()).isEqualTo(Issue.NOUVELLE_VERSION);
		assertThat(resultat.numero()).isEqualTo(2);
		assertThat(nombreDeVersions(DOSSIER_CONTRAT)).isEqualTo(2);
		assertThat(clesConsolidation(DOSSIER_CONTRAT)).hasSize(2);
		assertThat(service.derniere(DOSSIER_CONTRAT).json()).isEqualTo(autre);
		assertThat(service.version(DOSSIER_CONTRAT, 1).json()).isEqualTo(reponseContrat());
	}

	@Test
	void unResultatCalculeSurUnCompteurPlusAncienEstEcarte() {
		insererVersion(DOSSIER_CONTRAT, 1, 5);
		simulerConsolidation(reponseContrat());

		ResultatConsolidation resultat = service.consolider(DOSSIER_CONTRAT);

		assertThat(resultat.issue()).isEqualTo(Issue.PERIMEE);
		assertThat(resultat.numero()).isEqualTo(1);
		assertThat(nombreDeVersions(DOSSIER_CONTRAT)).isEqualTo(1);
		assertThat(clesConsolidation(DOSSIER_CONTRAT)).isEmpty();
	}

	@Test
	void unDossierVideJamaisConsolideNAppellePasAuditCore() {
		UUID dossierId = uuid(creerDossierEtRenvoyerId());

		ResultatConsolidation resultat = service.consolider(dossierId);

		assertThat(resultat.issue()).isEqualTo(Issue.DOSSIER_VIDE);
		assertThat(resultat.numero()).isNull();
		assertThat(nombreDeVersions(dossierId)).isZero();
		assertThat(core.findAll(postRequestedFor(urlEqualTo(CHEMIN)))).isEmpty();
	}

	@Test
	void unDossierDevenuVideAUneNouvelleVersion() {
		UUID dossierId = uuid(creerDossierEtRenvoyerId());
		insererVersion(dossierId, 1, 0);
		simulerConsolidation(reponsePour(dossierId));

		ResultatConsolidation resultat = service.consolider(dossierId);

		assertThat(resultat.issue()).isEqualTo(Issue.NOUVELLE_VERSION);
		assertThat(resultat.numero()).isEqualTo(2);
		JsonNode envoye = JSON.readTree(core.findAll(postRequestedFor(urlEqualTo(CHEMIN))).getFirst().getBody());
		assertThat(envoye.get("pieces")).isEmpty();
	}

	@Test
	void unEchecEstNotePuisEffaceAuSuccesSuivant() {
		simulerErreurConsolidation(500);

		assertThatThrownBy(() -> service.consolider(DOSSIER_CONTRAT)).isInstanceOf(AuditCoreErreurException.class);
		Map<String, Object> dossier = jdbc.queryForMap(
				"SELECT consolidation_erreur, consolidation_erreur_le FROM dossier WHERE id = ?", DOSSIER_CONTRAT);
		assertThat((String) dossier.get("consolidation_erreur")).startsWith("Consolidation impossible : ");
		assertThat(dossier.get("consolidation_erreur_le")).isNotNull();
		assertThat(nombreDeVersions(DOSSIER_CONTRAT)).isZero();

		core.resetAll();
		simulerConsolidation(reponseContrat());
		service.consolider(DOSSIER_CONTRAT);

		dossier = jdbc.queryForMap("SELECT consolidation_erreur, consolidation_erreur_le FROM dossier WHERE id = ?",
				DOSSIER_CONTRAT);
		assertThat(dossier.get("consolidation_erreur")).isNull();
		assertThat(dossier.get("consolidation_erreur_le")).isNull();
	}

}
