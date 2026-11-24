package com.melas.audit_api.consolidation;

import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.melas.audit_api.repository.DossierRepository;
import com.melas.audit_api.service.DossierIntrouvableException;

/**
 * Seul endroit qui demande une consolidation : incrémente {@code consolidation_demande_seq} du dossier, dans la
 * transaction même du changement (jamais par un événement après commit, qui serait perdu en cas d'arrêt). Le poller
 * ({@link PollerConsolidation}) traite ensuite les dossiers dont le compteur dépasse le compteur traité.
 *
 * <p>Changements qui demandent une consolidation, c'est-à-dire tout ce qui change la requête envoyée à audit-core :
 * dépôt, relance (traitement complet ou extraction seule), fin de traitement (EXTRAIT ou ERREUR), suppression d'un
 * document, passage en ERREUR à la reprise au démarrage ou quand la file de traitement est pleine. Le démarrage
 * automatique d'un document reçu (RECU → EN_COURS) n'en demande pas : voir {@code TraitementDocument.traiterSiRecu}.
 *
 * <h2>Ordre des verrous : dossier, puis document</h2>
 *
 * L'incrément est un UPDATE : il verrouille la ligne du dossier jusqu'à la fin de la transaction. Pour éviter les
 * interblocages, toute transaction qui verrouille à la fois un dossier et l'un de ses documents verrouille
 * <b>d'abord le dossier</b> ({@link #demander} ou {@link #verrouiller}), <b>puis le document</b> (lecture sous verrou,
 * modification ou suppression). Deux conséquences :
 * <ul>
 * <li>appeler {@link #demander} en tête de transaction, avant toute modification d'entité : l'UPDATE en JPQL
 * déclenche le flush des modifications en attente, qui verrouilleraient le document avant le dossier ;</li>
 * <li>quand la demande dépend de l'état du document (passage en ERREUR seulement s'il est encore EN_COURS),
 * {@link #verrouiller} le dossier d'abord, puis verrouiller et vérifier le document, puis {@link #demander}.</li>
 * </ul>
 * Transactions concernées, toutes dans cet ordre : dépôt, relance, fin de traitement (EXTRAIT et ERREUR), suppression
 * d'un document, file pleine, reprise au démarrage (dossiers verrouillés par identifiant croissant, avant les
 * documents), suppression d'un dossier (administration). L'enregistrement d'une consolidation ne verrouille que le
 * dossier, jamais un document. Les autres transactions du traitement (démarrage RECU → EN_COURS, OCR, classification)
 * ne verrouillent que le document.
 */
@Component
public class DemandeConsolidation {

	private final DossierRepository dossiers;

	DemandeConsolidation(DossierRepository dossiers) {
		this.dossiers = dossiers;
	}

	/**
	 * Demande une consolidation du dossier, dans la transaction en cours : annulée avec elle si elle échoue.
	 *
	 * @throws DossierIntrouvableException si le dossier n'existe pas
	 * @throws org.springframework.transaction.IllegalTransactionStateException hors transaction
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void demander(UUID dossierId) {
		if (dossiers.incrementerDemandeConsolidation(dossierId) == 0) {
			throw new DossierIntrouvableException(dossierId);
		}
	}

	/**
	 * Verrouille la ligne du dossier jusqu'à la fin de la transaction en cours, avant de verrouiller l'un de ses
	 * documents (voir l'ordre des verrous ci-dessus).
	 *
	 * @return false si le dossier n'existe pas (ou plus)
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean verrouiller(UUID dossierId) {
		return dossiers.verrouiller(dossierId).isPresent();
	}

}
