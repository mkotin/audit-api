package com.melas.audit_api.traitement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.melas.audit_api.consolidation.ConsolidationService;
import com.melas.audit_api.consolidation.ConsolidationTestSupport;
import com.melas.audit_api.service.DocumentService;

/**
 * Ordre des verrous (dossier, puis document : voir DemandeConsolidation). Une fin de traitement en erreur, la
 * suppression d'un autre document et une consolidation du même dossier, lancées ensemble à plusieurs reprises, se
 * terminent toutes sans interblocage (PostgreSQL en annulerait une) et sans perdre d'incrément du compteur.
 */
class OrdreDesVerrousTest extends ConsolidationTestSupport {

	private static final int TOURS = 15;

	@Autowired
	private TraitementDocument traitement;

	@Autowired
	private ConsolidationService consolidation;

	@Autowired
	private DocumentService documents;

	private UUID documentEnCours(int rang) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO document (id, dossier_id, type_document, nom_fichier_original, content_type, "
				+ "taille_octets, cle_stockage, empreinte_sha256, statut, date_reception, date_mise_a_jour) "
				+ "VALUES (?, ?, 'facture', 'x.pdf', 'application/pdf', 1, ?, ?, 'EN_COURS', now(), now())",
				id, DOSSIER_CONTRAT, "dossiers/%s/%s.pdf".formatted(DOSSIER_CONTRAT, id),
				"%064x".formatted(5000 + rang));
		return id;
	}

	private String statut(UUID documentId) {
		return jdbc.queryForObject("SELECT statut FROM document WHERE id = ?", String.class, documentId);
	}

	@Test
	void finDeTraitementSuppressionEtConsolidationEnParalleleSansInterblocage() throws Exception {
		chargerDossierContrat();
		simulerConsolidation(reponseContrat());
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try {
			UUID precedent = null;
			int suppressions = 0;
			for (int tour = 0; tour < TOURS; tour++) {
				UUID enCours = documentEnCours(tour);
				UUID aSupprimer = precedent;
				List<Callable<Void>> taches = new ArrayList<>();
				taches.add(() -> {
					traitement.terminerEnErreur(enCours, "Erreur simulée");
					return null;
				});
				taches.add(() -> {
					consolidation.consolider(DOSSIER_CONTRAT);
					return null;
				});
				if (aSupprimer != null) {
					taches.add(() -> {
						documents.supprimer(aSupprimer);
						return null;
					});
					suppressions++;
				}
				CyclicBarrier depart = new CyclicBarrier(taches.size());
				List<Future<Void>> lancees = new ArrayList<>();
				for (Callable<Void> tache : taches) {
					lancees.add(pool.submit(() -> {
						depart.await(10, TimeUnit.SECONDS);
						return tache.call();
					}));
				}
				for (Future<Void> lancee : lancees) {
					lancee.get(30, TimeUnit.SECONDS); // une exception (interblocage annulé…) fait échouer le test
				}
				// terminerEnErreur ne lève pas d'exception : on vérifie qu'il a bien abouti
				assertThat(statut(enCours)).as("tour %d", tour).isEqualTo("ERREUR");
				precedent = enCours;
			}
			assertThat(jdbc.queryForObject("SELECT consolidation_demande_seq FROM dossier WHERE id = ?", Long.class,
					DOSSIER_CONTRAT)).as("un incrément par fin de traitement et par suppression")
					.isEqualTo(TOURS + suppressions);
		}
		finally {
			pool.shutdownNow();
		}
	}

}
