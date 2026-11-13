package com.melas.audit_api.consolidation;

/**
 * Issue d'une consolidation.
 *
 * @param issue ce qui a été enregistré
 * @param numero numéro de la version créée (NOUVELLE_VERSION), ou de la dernière version existante (INCHANGEE,
 *        PERIMEE) ; null pour DOSSIER_VIDE
 * @param dureeMs durée de l'appel à audit-core (0 sans appel)
 */
public record ResultatConsolidation(Issue issue, Integer numero, int dureeMs) {

	public enum Issue {
		/** Une nouvelle version a été enregistrée (empreinte du résultat différente de la dernière). */
		NOUVELLE_VERSION,
		/** Même empreinte que la dernière version : rien n'est enregistré. */
		INCHANGEE,
		/** Une version calculée sur un état plus récent du dossier existe déjà : ce résultat est écarté. */
		PERIMEE,
		/** Dossier sans document et jamais consolidé : pas d'appel, pas de version. */
		DOSSIER_VIDE
	}

}
