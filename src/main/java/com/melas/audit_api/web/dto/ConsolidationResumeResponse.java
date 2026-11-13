package com.melas.audit_api.web.dto;

import java.time.Instant;
import java.util.Locale;

import com.melas.audit_api.consolidation.ResumeConsolidation;

/**
 * Résumé de la consolidation dans la réponse d'un dossier.
 *
 * @param statut aucune, en_attente, a_jour ou en_erreur (déduit)
 * @param numero numéro de la dernière version (null si aucune)
 * @param date date de la dernière version (null si aucune)
 * @param nombreContradictions de la dernière version (null si aucune)
 */
public record ConsolidationResumeResponse(String statut, Integer numero, Instant date, Integer nombreContradictions) {

	public static ConsolidationResumeResponse depuis(ResumeConsolidation resume) {
		return new ConsolidationResumeResponse(resume.statut().name().toLowerCase(Locale.ROOT), resume.numero(),
				resume.date(), resume.nombreContradictions());
	}

}
