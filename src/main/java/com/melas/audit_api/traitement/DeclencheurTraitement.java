package com.melas.audit_api.traitement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.melas.audit_api.service.DocumentDepose;

/**
 * Lance le traitement de chaque document déposé, une fois le dépôt validé en base.
 *
 * <p>L'écouteur reste synchrone et soumet le traitement à l'exécuteur ({@link ExecutionTraitement}, {@code @Async}) :
 * un écouteur lui-même {@code @Async} ferait remonter le refus d'une file pleine dans la validation du dépôt. Ici, le
 * refus est seulement signalé : le document reste RECU, et l'upload répond normalement.
 */
@Component
class DeclencheurTraitement {

	private static final Logger log = LoggerFactory.getLogger(DeclencheurTraitement.class);

	private final ExecutionTraitement execution;
	private final TraitementProperties proprietes;

	DeclencheurTraitement(ExecutionTraitement execution, TraitementProperties proprietes) {
		this.execution = execution;
		this.proprietes = proprietes;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void documentDepose(DocumentDepose evenement) {
		if (!proprietes.automatique()) {
			return;
		}
		try {
			execution.traiterSiRecu(evenement.documentId());
		}
		catch (TaskRejectedException e) {
			log.warn("File de traitement pleine : le document {} reste RECU (repris au prochain démarrage, ou par "
					+ "POST /api/documents/{}/traitement)", evenement.documentId(), evenement.documentId());
		}
	}

}
