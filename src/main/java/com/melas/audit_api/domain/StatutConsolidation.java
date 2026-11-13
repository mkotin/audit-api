package com.melas.audit_api.domain;

/**
 * Statut de la consolidation d'un dossier, déduit (jamais stocké) : de la dernière erreur, des compteurs de demande et
 * de l'existence d'une version. Voir {@link Dossier#statutConsolidation(boolean)}.
 */
public enum StatutConsolidation {

	/** Jamais consolidé, et aucune demande en attente. */
	AUCUNE,
	/** Une demande de recalcul attend d'être traitée (7d). */
	EN_ATTENTE,
	/** La dernière version correspond au dernier état connu du dossier. */
	A_JOUR,
	/** Le dernier calcul a échoué (audit-core ou stockage indisponible, par exemple). */
	EN_ERREUR

}
