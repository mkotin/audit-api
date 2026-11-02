package com.melas.audit_api.domain;

import java.util.List;
import java.util.Objects;

/**
 * Point à vérifier signalé par un traitement (classification, extraction, contrôles du domaine) : une alerte ne
 * modifie aucune valeur. Stockée en jsonb.
 *
 * @param code code stable, ex. "bloc_inexistant"
 * @param message description lisible
 * @param champs chemins des champs concernés ("total_ttc", "lignes[2].montant_ht"), éventuellement vide
 */
public record Alerte(String code, String message, List<String> champs) {

	public Alerte {
		Objects.requireNonNull(code, "code");
		Objects.requireNonNull(message, "message");
		champs = champs == null ? List.of() : List.copyOf(champs);
	}

}
