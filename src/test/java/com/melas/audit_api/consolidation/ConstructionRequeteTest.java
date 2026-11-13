package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.core.dto.RequeteConsolidation;

import jakarta.persistence.EntityManagerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Construction de la requête de consolidation : identique au contrat, trois requêtes SQL, dernière extraction. */
class ConstructionRequeteTest extends ConsolidationTestSupport {

	@Autowired
	private ConstructionRequete construction;

	@Autowired
	private TransactionTemplate transaction;

	@Autowired
	private EntityManagerFactory emf;

	@BeforeEach
	void charger() {
		chargerDossierContrat();
	}

	private RequeteConsolidation construire() {
		return transaction.execute(statut -> construction.construire(DOSSIER_CONTRAT).requete());
	}

	private JsonNode arbre(RequeteConsolidation requete) {
		return JSON.valueToTree(requete);
	}

	@Test
	void laRequeteConstruiteEstCelleDuContrat() {
		assertThat(arbre(construire())).isEqualTo(requeteContrat());
	}

	@Test
	void troisRequetesSqlQuelQueSoitLeNombreDeDocuments() {
		assertThat(nombreDeRequetes()).isEqualTo(3);

		// 20 documents extraits de plus, chacun avec sa dernière extraction et ses champs
		JsonNode extraction = requeteContrat().get("pieces").get(0).get("extraction");
		for (int i = 0; i < 20; i++) {
			UUID documentId = UUID.randomUUID();
			jdbc.update("INSERT INTO document (id, dossier_id, type_document, nom_fichier_original, content_type, "
					+ "taille_octets, cle_stockage, empreinte_sha256, statut, date_reception, date_mise_a_jour) "
					+ "VALUES (?, ?, 'declaration', 'x.pdf', 'application/pdf', 1, ?, ?, 'EXTRAIT', now(), now())",
					documentId, DOSSIER_CONTRAT, "x/" + documentId, "%064x".formatted(1000 + i));
			insererExtraction(documentId, UUID.randomUUID(), extraction, Instant.now());
		}
		assertThat(construire().pieces()).hasSize(28);
		assertThat(nombreDeRequetes()).isEqualTo(3);
	}

	private long nombreDeRequetes() {
		Statistics statistiques = emf.unwrap(SessionFactory.class).getStatistics();
		statistiques.clear();
		construire();
		return statistiques.getPrepareStatementCount();
	}

	@Test
	void uneExtractionPlusAncienneEstIgnoree() {
		JsonNode piece = requeteContrat().get("pieces").get(0);
		ObjectNode ancienne = (ObjectNode) piece.get("extraction").deepCopy();
		ancienne.put("modele", "ancien-modele");
		insererExtraction(UUID.fromString(piece.get("document_id").stringValue()), UUID.randomUUID(), ancienne,
				Instant.now().minus(1, ChronoUnit.DAYS));

		assertThat(arbre(construire())).isEqualTo(requeteContrat());
	}

	@Test
	void unDocumentNonExtraitEstEnvoyeSansSonAncienneExtraction() {
		// La pièce en ERREUR du contrat garde une extraction d'un passage précédent
		JsonNode erreur = null;
		for (JsonNode piece : requeteContrat().get("pieces")) {
			if ("ERREUR".equals(piece.get("statut").stringValue())) {
				erreur = piece;
			}
		}
		assertThat(erreur).isNotNull();
		ObjectNode extraction = (ObjectNode) requeteContrat().get("pieces").get(1).get("extraction").deepCopy();
		insererExtraction(UUID.fromString(erreur.get("document_id").stringValue()), UUID.randomUUID(), extraction,
				Instant.now());

		assertThat(arbre(construire())).isEqualTo(requeteContrat());
	}

}
