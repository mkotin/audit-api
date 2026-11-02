package com.melas.audit_api.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Réponse de POST /v1/classification.
 *
 * @param typeDocument identifiant du type (GET /v1/domaines), ou "inconnu"
 * @param confiance calculée par audit-core (pas par le LLM), entre 0 et 1
 * @param raison justification du LLM ; null s'il n'a pas été appelé (document sans texte)
 * @param typeReference type proposé par le classifieur par indices
 * @param tentatives tentatives de validation ; 0 si le LLM n'a pas été appelé
 */
public record ResultatClassification(
		UUID documentId,
		String typeDocument,
		double confiance,
		List<BlocJustificatif> blocs,
		String raison,
		List<AlerteCore> alertes,
		String typeReference,
		int pagesAnalysees,
		String modele,
		String fournisseur,
		String versionConsigne,
		String empreinteConsigne,
		String versionDomaine,
		Integer jetonsEntree,
		Integer jetonsSortie,
		int dureeMs,
		int tentatives) {

	/** Bloc cité par le LLM ; page nulle si le bloc n'existe pas. */
	public record BlocJustificatif(String id, Integer page) {
	}

}
