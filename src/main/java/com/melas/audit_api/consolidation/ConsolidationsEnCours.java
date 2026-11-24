package com.melas.audit_api.consolidation;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Dossiers en cours de consolidation, pour qu'il n'y ait jamais deux calculs du même dossier en même temps : ni deux
 * passages du poller, ni le poller et l'endpoint d'administration. Ensemble en mémoire : suppose une seule instance
 * d'audit-api (en plusieurs instances, il faudrait réclamer les dossiers en base par un bail, voir l'ADR 0004
 * d'audit-core). Vide au démarrage : un calcul interrompu par un arrêt n'a pas avancé le compteur traité, il est
 * simplement repris.
 */
@Component
class ConsolidationsEnCours {

	private final Set<UUID> enCours = ConcurrentHashMap.newKeySet();

	/** Réserve le dossier ; false s'il est déjà en cours. */
	boolean reserver(UUID dossierId) {
		return enCours.add(dossierId);
	}

	void liberer(UUID dossierId) {
		enCours.remove(dossierId);
	}

	boolean estEnCours(UUID dossierId) {
		return enCours.contains(dossierId);
	}

}
