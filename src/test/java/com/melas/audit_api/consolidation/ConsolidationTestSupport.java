package com.melas.audit_api.consolidation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.melas.audit_api.service.CleStockage;
import com.melas.audit_api.stockage.StockageFichiers;
import com.melas.audit_api.traitement.TraitementTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base des tests de la consolidation : audit-core simulé (WireMock) qui renvoie la réponse du contrat d'audit-core
 * (src/test/resources/core/consolidation_*.json, recopiés par scripts/copier-contrat-consolidation.ps1), et le dossier
 * du contrat chargé en base par JDBC avec ses identifiants fixes, pour que cette réponse le désigne bien. Profil dev
 * (endpoint d'administration), délai de lecture de la consolidation réduit à 1 s, statistiques de Hibernate activées
 * (comptage des requêtes).
 */
@TestPropertySource(properties = {
		"app.core.delai-lecture-consolidation=1s",
		"spring.jpa.properties.hibernate.generate_statistics=true" })
public abstract class ConsolidationTestSupport extends TraitementTestSupport {

	/** Identifiant du dossier du contrat (dossier fictif de la requête riche des tests d'audit-core). */
	protected static final UUID DOSSIER_CONTRAT = UUID.fromString("00000000-0000-0000-0000-0000000f423f");

	protected static final String CHEMIN = "/v1/consolidation";

	protected static final JsonMapper JSON = JsonMapper.builder()
			.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
			.build();

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected StockageFichiers stockage;

	/** Octets exacts d'un fichier de src/test/resources/core. */
	protected static byte[] octets(String nom) {
		try (InputStream contenu = ConsolidationTestSupport.class.getResourceAsStream("/core/" + nom)) {
			return contenu.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected static byte[] reponseContrat() {
		return octets("consolidation_reponse.json");
	}

	protected static JsonNode requeteContrat() {
		return JSON.readTree(octets("consolidation_requete.json"));
	}

	/** Réponse du contrat, pour un autre dossier (même contenu, autre dossier_id, donc autre empreinte). */
	protected static byte[] reponsePour(UUID dossierId) {
		return new String(reponseContrat(), StandardCharsets.UTF_8)
				.replace(DOSSIER_CONTRAT.toString(), dossierId.toString())
				.getBytes(StandardCharsets.UTF_8);
	}

	protected void simulerConsolidation(byte[] reponse) {
		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", "application/json")
				.withBody(reponse)));
	}

	protected void simulerErreurConsolidation(int statut) {
		core.stubFor(post(urlEqualTo(CHEMIN)).willReturn(aResponse()
				.withStatus(statut)
				.withHeader("Content-Type", "application/problem+json")
				.withBody("{\"title\": \"Erreur simulée\", \"status\": %d, \"detail\": \"test\"}".formatted(statut))));
	}

	/**
	 * Charge en base le dossier de la requête du contrat (identifiants fixes) : le dossier, ses documents, pour chaque
	 * document extrait un résultat d'OCR factice (exigé par la clé étrangère), son extraction et ses champs (ordre du
	 * contrat). Un éventuel chargement précédent, et ses fichiers dans le stockage, sont d'abord supprimés.
	 */
	protected void chargerDossierContrat() {
		JsonNode requete = requeteContrat();
		UUID dossierId = UUID.fromString(requete.get("dossier_id").stringValue());
		jdbc.update("DELETE FROM dossier WHERE id = ?", dossierId);
		jdbc.update("DELETE FROM dossier WHERE reference_externe = ?", requete.get("reference_externe").stringValue());
		stockage.supprimerParPrefixe(CleStockage.prefixeDossier(dossierId));
		jdbc.update("INSERT INTO dossier (id, reference_externe, domaine, date_creation, date_mise_a_jour) "
				+ "VALUES (?, ?, 'assurance_habitation', now(), now())", dossierId,
				requete.get("reference_externe").stringValue());
		int rang = 0;
		for (JsonNode piece : requete.get("pieces")) {
			UUID documentId = UUID.fromString(piece.get("document_id").stringValue());
			insererDocument(dossierId, documentId, piece, rang++);
			JsonNode extraction = piece.get("extraction");
			if (extraction != null && !extraction.isNull()) {
				insererExtraction(documentId, UUID.fromString(extraction.get("extraction_id").stringValue()),
						extraction, Instant.now());
			}
		}
	}

	private void insererDocument(UUID dossierId, UUID documentId, JsonNode piece, int rang) {
		jdbc.update("INSERT INTO document (id, dossier_id, type_document, nom_fichier_original, content_type, "
				+ "taille_octets, cle_stockage, empreinte_sha256, statut, message_erreur, date_reception, "
				+ "date_mise_a_jour) VALUES (?, ?, ?, ?, 'application/pdf', 1000, ?, ?, ?, ?, now(), now())",
				documentId, dossierId, texte(piece, "type_document"), texte(piece, "nom_fichier"),
				"dossiers/%s/%s.pdf".formatted(dossierId, documentId), "%064x".formatted(rang + 1),
				texte(piece, "statut"), texte(piece, "message_erreur"));
	}

	/** Une extraction (et son résultat d'OCR factice) avec les champs donnés, à la date donnée. */
	protected void insererExtraction(UUID documentId, UUID extractionId, JsonNode extraction, Instant date) {
		UUID ocrId = UUID.randomUUID();
		jdbc.update("INSERT INTO resultat_ocr (id, document_id, cle_stockage, version_service, type_fichier, "
				+ "methode_globale, nombre_pages, duree_telechargement_ms, duree_extraction_ms, date_creation) "
				+ "VALUES (?, ?, ?, '0.1.0', 'pdf', 'natif', 1, 0, 0, ?)", ocrId, documentId,
				"test/ocr/" + ocrId + ".json", java.sql.Timestamp.from(date));
		jdbc.update("INSERT INTO extraction (id, document_id, resultat_ocr_id, cle_stockage, type_document, confiance, "
				+ "alertes, pages_analysees, modele, fournisseur, version_consigne, empreinte_consigne, version_domaine, "
				+ "empreinte_domaine, duree_ms, tentatives, date_creation) "
				+ "VALUES (?, ?, ?, ?, ?, 0.9, '[]'::jsonb, 1, ?, 'test', ?, ?, ?, ?, 0, 1, ?)",
				extractionId, documentId, ocrId, "test/extraction/" + extractionId + ".json",
				texte(extraction, "type_document"), texte(extraction, "modele"), texte(extraction, "version_consigne"),
				texte(extraction, "empreinte_consigne"), texte(extraction, "version_domaine"),
				texte(extraction, "empreinte_domaine"), java.sql.Timestamp.from(date));
		int ordre = 0;
		for (JsonNode champ : extraction.get("champs")) {
			jdbc.update("INSERT INTO champ_extrait (id, document_id, extraction_id, nom, valeur_brute, "
					+ "valeur_normalisee, confiance, extrait_par, date_extraction, alertes, ordre) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'test', now(), '[]'::jsonb, ?)", UUID.randomUUID(), documentId,
					extractionId, texte(champ, "chemin"), texte(champ, "valeur_brute"),
					texte(champ, "valeur_normalisee"), champ.get("confiance").decimalValue(), ordre++);
		}
	}

	/** Une version de consolidation insérée directement (sans fichier dans le stockage). */
	protected void insererVersion(UUID dossierId, int numero, long demandeSeq) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO consolidation (id, dossier_id, numero, cle_stockage, empreinte_resultat, demande_seq, "
				+ "version_consolidation, empreinte_consolidation, version_domaine, empreinte_domaine, nombre_pieces, "
				+ "nombre_non_exploitables, nombre_liens, nombre_contradictions, contradictions, duree_ms, "
				+ "date_creation) VALUES (?, ?, ?, ?, ?, ?, '0.0', 'x', '0.0', 'x', 0, 0, 0, 0, '[]'::jsonb, 0, now())",
				id, dossierId, numero, CleStockage.consolidation(dossierId, id), "%064x".formatted(numero), demandeSeq);
	}

	/** Clés des versions de consolidation du dossier présentes dans le stockage. */
	protected java.util.List<String> clesConsolidation(UUID dossierId) {
		return clesStockees(dossierId.toString()).stream().filter(cle -> cle.contains("/consolidation/")).toList();
	}

	private static String texte(JsonNode noeud, String champ) {
		JsonNode valeur = noeud.get(champ);
		return valeur == null || valeur.isNull() ? null : valeur.stringValue();
	}

}
