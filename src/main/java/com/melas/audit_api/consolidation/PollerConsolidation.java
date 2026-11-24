package com.melas.audit_api.consolidation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

import com.melas.audit_api.repository.DossierRepository;

/**
 * Consolidation automatique : à chaque passage (toutes les {@code app.consolidation.intervalle}), les dossiers dont
 * une demande est en attente et dont la nouvelle tentative éventuelle est due sont lus en une requête, puis confiés à
 * l'exécuteur dédié, un seul calcul à la fois par dossier ({@link ConsolidationsEnCours}).
 *
 * <p>Les demandes se regroupent d'elles-mêmes : dix changements entre deux passages donnent un seul calcul, et ceux
 * qui arrivent pendant un calcul en donnent un seul de plus (le compteur traité n'avance que jusqu'au compteur lu).
 * Au démarrage, rien de particulier : les demandes en attente sont en base, et le premier passage les reprend.
 */
@Component
class PollerConsolidation {

	private static final Logger log = LoggerFactory.getLogger(PollerConsolidation.class);

	private final DossierRepository dossiers;
	private final ConsolidationService service;
	private final ConsolidationsEnCours enCours;
	private final TaskExecutor executeur;
	private final ConsolidationProperties proprietes;

	PollerConsolidation(DossierRepository dossiers, ConsolidationService service, ConsolidationsEnCours enCours,
			@Qualifier(ConsolidationConfiguration.EXECUTEUR) TaskExecutor executeur,
			ConsolidationProperties proprietes) {
		this.dossiers = dossiers;
		this.service = service;
		this.enCours = enCours;
		this.executeur = executeur;
		this.proprietes = proprietes;
	}

	/** Passage planifié : une erreur (base indisponible…) est journalisée, le passage suivant aura lieu. */
	void passage() {
		try {
			examiner();
		}
		catch (RuntimeException e) {
			log.error("Passage du poller de consolidation en échec", e);
		}
	}

	/**
	 * Soumet à l'exécuteur les dossiers à consolider, sauf ceux déjà en cours.
	 *
	 * @return le nombre de dossiers soumis
	 */
	int examiner() {
		List<UUID> aConsolider = dossiers.findIdsAConsolider(Instant.now(),
				Limit.of(proprietes.tailleFile() + proprietes.parallelisme()));
		int soumis = 0;
		for (UUID dossierId : aConsolider) {
			if (!enCours.reserver(dossierId)) {
				continue;
			}
			try {
				executeur.execute(() -> calculer(dossierId));
				soumis++;
			}
			catch (TaskRejectedException e) {
				enCours.liberer(dossierId);
				log.warn("File de consolidation pleine : {} dossier(s) repris au passage suivant",
						aConsolider.size() - soumis);
				break;
			}
		}
		return soumis;
	}

	/** Sur un thread de l'exécuteur, dossier déjà réservé ; l'échec est déjà noté et journalisé par le service. */
	private void calculer(UUID dossierId) {
		try {
			service.consoliderDossierReserve(dossierId);
		}
		catch (RuntimeException e) {
			log.debug("Consolidation automatique du dossier {} en échec", dossierId, e);
		}
		finally {
			enCours.liberer(dossierId);
		}
	}

}
