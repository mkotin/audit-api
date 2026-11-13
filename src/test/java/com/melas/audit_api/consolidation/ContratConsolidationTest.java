package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Les fichiers de contrat de POST /v1/consolidation copiés dans audit-api sont identiques, octet pour octet, à ceux
 * d'audit-core (dépôts clonés côte à côte). S'il échoue : .\scripts\copier-contrat-consolidation.ps1. Ignoré si
 * audit-core n'est pas cloné à côté.
 */
class ContratConsolidationTest {

	private static final Path CONTRAT_AUDIT_CORE = Path.of("..", "audit-core", "tests", "contrat");

	@ParameterizedTest
	@ValueSource(strings = { "consolidation_requete.json", "consolidation_reponse.json" })
	void laCopieEstIdentiqueAuContratDAuditCore(String nom) throws IOException {
		Path source = CONTRAT_AUDIT_CORE.resolve(nom);
		assumeTrue(Files.isRegularFile(source), "audit-core n'est pas cloné à côté d'audit-api");
		try (InputStream copie = getClass().getResourceAsStream("/core/" + nom)) {
			assertThat(copie).as("copie de %s (scripts/copier-contrat-consolidation.ps1)", nom).isNotNull();
			assertThat(copie.readAllBytes())
					.as("%s périmé : lancer .\\scripts\\copier-contrat-consolidation.ps1", nom)
					.isEqualTo(Files.readAllBytes(source));
		}
	}

}
