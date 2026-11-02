package com.melas.audit_api.traitement;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import com.melas.audit_api.core.Correlation;

/**
 * Exécution des traitements sur l'exécuteur dédié. Chaque méthode rend la main immédiatement ; si la file est
 * pleine, l'appel lève {@link TaskRejectedException} dans le thread de l'appelant, qui décide de la suite.
 *
 * <p>Chaque traitement reçoit un identifiant de corrélation : placé dans le MDC (logs d'audit-api) et transmis à
 * audit-core dans X-Request-ID par le client.
 */
@Component
public class ExecutionTraitement {

	private static final Logger log = LoggerFactory.getLogger(ExecutionTraitement.class);

	static final String CLE_MDC_DOCUMENT = "documentId";

	private final TraitementDocument traitement;

	ExecutionTraitement(TraitementDocument traitement) {
		this.traitement = traitement;
	}

	/** Traitement automatique, si le document est encore RECU. */
	@Async(TraitementConfiguration.EXECUTEUR)
	public void traiterSiRecu(UUID documentId) {
		avecCorrelation(documentId, () -> traitement.traiterSiRecu(documentId));
	}

	/** Traitement complet d'un document déjà passé EN_COURS. */
	@Async(TraitementConfiguration.EXECUTEUR)
	public void traiter(UUID documentId) {
		avecCorrelation(documentId, () -> traitement.traiter(documentId));
	}

	/** Extraction seule d'un document déjà passé EN_COURS. */
	@Async(TraitementConfiguration.EXECUTEUR)
	public void extraire(UUID documentId) {
		avecCorrelation(documentId, () -> traitement.extraire(documentId));
	}

	private static void avecCorrelation(UUID documentId, Runnable traitement) {
		MDC.put(Correlation.CLE_MDC, UUID.randomUUID().toString().replace("-", ""));
		MDC.put(CLE_MDC_DOCUMENT, documentId.toString());
		try {
			traitement.run();
		}
		catch (RuntimeException e) {
			// Filet de sécurité : TraitementDocument gère déjà ses erreurs
			log.error("Échec inattendu du traitement du document {}", documentId, e);
		}
		finally {
			MDC.remove(Correlation.CLE_MDC);
			MDC.remove(CLE_MDC_DOCUMENT);
		}
	}

}
