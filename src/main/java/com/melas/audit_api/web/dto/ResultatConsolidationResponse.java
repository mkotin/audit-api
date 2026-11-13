package com.melas.audit_api.web.dto;

import java.util.Locale;

import com.melas.audit_api.consolidation.ResultatConsolidation;

/**
 * Résultat d'un recalcul forcé (administration).
 *
 * @param resultat nouvelle_version, inchangee, perimee ou dossier_vide
 * @param numero version créée, ou dernière version existante ; null pour dossier_vide
 * @param dureeMs durée de l'appel à audit-core (0 sans appel)
 */
public record ResultatConsolidationResponse(String resultat, Integer numero, int dureeMs) {

	public static ResultatConsolidationResponse depuis(ResultatConsolidation resultat) {
		return new ResultatConsolidationResponse(resultat.issue().name().toLowerCase(Locale.ROOT), resultat.numero(),
				resultat.dureeMs());
	}

}
