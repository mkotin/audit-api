package com.melas.audit_api.domain;

import java.util.List;

/**
 * Une entrée de l'index des contradictions d'une version de consolidation, gardée en base (jsonb) pour pouvoir la
 * requêter ; le détail (valeurs, sources, écarts) reste dans le JSON complet du stockage. Le chemin désigne un fait
 * (« faits.bien.adresse »), un lien ou des intervenants. Le résumé contient des comptes, des types de pièces et des
 * noms de champs ; seul celui d'un SIRET partagé cite une valeur, le SIRET (identifiant d'entreprise). Comme le JSON
 * complet, l'index ne doit pas être écrit dans les journaux.
 */
public record ContradictionIndexee(String id, String code, String chemin, String lienId, List<String> intervenantIds,
		String resume) {

	public ContradictionIndexee {
		intervenantIds = intervenantIds == null ? List.of() : List.copyOf(intervenantIds);
	}

}
