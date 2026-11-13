package com.melas.audit_api.core.dto;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Ce qu'audit-api lit du dossier consolidé (POST /v1/consolidation) : de quoi vérifier le dossier, enregistrer les
 * versions, les comptes et l'index des contradictions. Le reste (faits, intervenants…) n'est lu que dans le JSON
 * complet, conservé tel quel dans le stockage.
 */
public record ReponseConsolidation(UUID dossierId, String versionConsolidation, String empreinteConsolidation,
		String versionDomaine, String empreinteDomaine, List<Piece> pieces, List<JsonNode> liens,
		List<Contradiction> contradictions) {

	/** Valeur de {@code statut} d'une pièce qui ne peut pas être consolidée. */
	public static final String NON_EXPLOITABLE = "non_exploitable";

	public ReponseConsolidation {
		pieces = pieces == null ? List.of() : pieces;
		liens = liens == null ? List.of() : liens;
		contradictions = contradictions == null ? List.of() : contradictions;
	}

	public record Piece(UUID documentId, String statut) {
	}

	public record Contradiction(String id, String code, String chemin, String lienId, List<String> intervenantIds,
			String resume) {
	}

	public int nombreNonExploitables() {
		return (int) pieces.stream().filter(piece -> NON_EXPLOITABLE.equals(piece.statut())).count();
	}

}
