package com.melas.audit_api.traitement;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.consolidation.DemandeConsolidation;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;
import com.melas.audit_api.repository.DocumentRepository;

/**
 * Reprise au démarrage : les documents restés EN_COURS (application arrêtée pendant leur traitement) passent en
 * ERREUR, et les documents RECU non traités sont remis en file, du plus ancien au plus récent.
 *
 * <p>Suppose une seule instance d'audit-api : avec plusieurs instances, les traitements encore en cours sur une autre
 * instance seraient marqués interrompus.
 */
@Component
public class RepriseTraitement {

	private static final Logger log = LoggerFactory.getLogger(RepriseTraitement.class);

	static final String MESSAGE_INTERROMPU = "Traitement interrompu";

	private final DocumentRepository documents;
	private final ExecutionTraitement execution;
	private final TransactionTemplate transaction;
	private final TraitementProperties proprietes;
	private final DemandeConsolidation demandeConsolidation;

	RepriseTraitement(DocumentRepository documents, ExecutionTraitement execution, TransactionTemplate transaction,
			TraitementProperties proprietes, DemandeConsolidation demandeConsolidation) {
		this.demandeConsolidation = demandeConsolidation;
		this.documents = documents;
		this.execution = execution;
		this.transaction = transaction;
		this.proprietes = proprietes;
	}

	@EventListener(ApplicationReadyEvent.class)
	void auDemarrage() {
		if (proprietes.automatique() && proprietes.repriseAuDemarrage()) {
			reprendre();
		}
	}

	public void reprendre() {
		List<UUID> interrompus = transaction.execute(statut -> {
			List<Document> enCours = documents.findByStatut(StatutDocument.EN_COURS);
			// Une demande de consolidation par dossier touché, avant de modifier les documents : dossiers verrouillés
			// d'abord, dans un ordre fixe (identifiant croissant), puis les documents au flush (DemandeConsolidation)
			enCours.stream()
					.map(document -> document.getDossier().getId())
					.distinct()
					.sorted()
					.forEach(demandeConsolidation::demander);
			enCours.forEach(document -> document.marquerErreur(MESSAGE_INTERROMPU));
			return enCours.stream().map(Document::getId).toList();
		});
		if (!interrompus.isEmpty()) {
			log.warn("Reprise : {} document(s) EN_COURS passé(s) en ERREUR (traitement interrompu) : {}",
					interrompus.size(), interrompus);
		}

		List<UUID> recus = transaction.execute(statut -> documents
				.findByStatutOrderByDateReceptionAsc(StatutDocument.RECU).stream()
				.map(Document::getId)
				.toList());
		int soumis = 0;
		for (UUID id : recus) {
			try {
				execution.traiterSiRecu(id);
				soumis++;
			}
			catch (TaskRejectedException e) {
				log.warn("Reprise : file de traitement pleine, {} document(s) RECU restent en attente",
						recus.size() - soumis);
				break;
			}
		}
		if (soumis > 0) {
			log.info("Reprise : {} document(s) RECU remis en file", soumis);
		}
	}

}
