package com.melas.audit_api.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * POST /v1/consolidation : le dossier entier. Chaque document avec son statut ; la dernière extraction d'un document
 * EXTRAIT seulement ; un document dans un autre statut est envoyé sans extraction. Les champs sont les lignes
 * {@code ChampExtrait} telles qu'elles sont enregistrées.
 */
public record RequeteConsolidation(UUID dossierId, String referenceExterne, List<Piece> pieces) {

	public record Piece(UUID documentId, String nomFichier, String statut, String messageErreur, String typeDocument,
			Extraction extraction) {
	}

	public record Extraction(UUID extractionId, String typeDocument, String versionDomaine, String empreinteDomaine,
			String versionConsigne, String empreinteConsigne, String modele, List<Champ> champs) {
	}

	/** {@code valeurNormalisee} : texte normalisé (« 316.80 », « 2026-03-16 », « true »), ou null. */
	public record Champ(String chemin, String valeurBrute, String valeurNormalisee, double confiance) {
	}

}
