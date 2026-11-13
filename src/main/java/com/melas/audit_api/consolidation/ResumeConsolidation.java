package com.melas.audit_api.consolidation;

import java.time.Instant;

import com.melas.audit_api.domain.StatutConsolidation;

/**
 * Résumé de la consolidation d'un dossier, repris dans la réponse du dossier.
 *
 * @param numero numéro de la dernière version (null si aucune)
 * @param date date de la dernière version (null si aucune)
 * @param nombreContradictions nombre de contradictions de la dernière version (null si aucune)
 */
public record ResumeConsolidation(StatutConsolidation statut, Integer numero, Instant date,
		Integer nombreContradictions) {

	/** Dossier qui vient d'être créé : jamais consolidé. */
	public static ResumeConsolidation aucune() {
		return new ResumeConsolidation(StatutConsolidation.AUCUNE, null, null, null);
	}

}
