package com.melas.audit_api.core.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Réponse de POST /v1/extraction.
 *
 * @param donnees objet du domaine construit à partir des valeurs normalisées (schéma du type dans GET /v1/domaines)
 * @param provenance par chemin de champ extrait ("total_ttc", "lignes[2].montant_ht", indices à partir de 0), dans
 *        l'ordre des champs du type
 * @param alertes alertes du document (celles des champs sont dans la provenance)
 * @param confiance plus basse confiance des champs obligatoires présents (0 sinon)
 */
public record DocumentExtrait(
		UUID documentId,
		String typeDocument,
		JsonNode donnees,
		LinkedHashMap<String, ProvenanceChamp> provenance,
		List<AlerteCore> alertes,
		double confiance,
		int pagesAnalysees,
		String modele,
		String fournisseur,
		String versionConsigne,
		String empreinteConsigne,
		String versionDomaine,
		String empreinteDomaine,
		Integer jetonsEntree,
		Integer jetonsSortie,
		int dureeMs,
		int tentatives) {

	/**
	 * Origine et vérification d'une valeur extraite.
	 *
	 * @param valeurBrute valeur telle que le LLM l'a recopiée du document
	 * @param valeurNormalisee valeur normalisée : texte ("1254.00", "2026-03-12"), booléen, ou null si la
	 *        normalisation a échoué
	 * @param bloc bloc où commence la valeur ; null si aucun bloc valable
	 * @param blocs blocs couverts par la valeur, dans l'ordre de lecture
	 * @param bbox union des zones des blocs couverts ; null si aucun
	 */
	public record ProvenanceChamp(
			String valeurBrute,
			JsonNode valeurNormalisee,
			String bloc,
			List<String> blocs,
			Integer page,
			BoiteEnglobanteCore bbox,
			double confiance,
			List<AlerteCore> alertes) {
	}

}
