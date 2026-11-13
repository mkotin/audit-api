package com.melas.audit_api.consolidation;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.consolidation.ConstructionRequete.Construction;
import com.melas.audit_api.consolidation.ResultatConsolidation.Issue;
import com.melas.audit_api.core.AuditCoreException;
import com.melas.audit_api.core.ClientAuditCore;
import com.melas.audit_api.core.Correlation;
import com.melas.audit_api.core.ReponseBrute;
import com.melas.audit_api.core.dto.ReponseConsolidation;
import com.melas.audit_api.domain.Consolidation;
import com.melas.audit_api.domain.ContradictionIndexee;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.repository.ConsolidationRepository;
import com.melas.audit_api.repository.DossierRepository;
import com.melas.audit_api.service.CleStockage;
import com.melas.audit_api.service.DossierIntrouvableException;
import com.melas.audit_api.service.EmpreinteSha256;
import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageFichiers;

/**
 * Consolidation d'un dossier par audit-core (POST /v1/consolidation) et lecture de ses versions.
 *
 * <p>Déroulement de {@link #consolider(UUID)}, sans transaction ouverte pendant l'appel HTTP ou l'accès au stockage :
 * <ol>
 * <li>transaction en lecture seule : requête construite (dossier entier, trois requêtes SQL), compteur de demande du
 * dossier et dernière version lus ; un dossier sans document et jamais consolidé s'arrête là (pas de version) ;</li>
 * <li>appel à audit-core, hors transaction ; empreinte SHA-256 des octets de la réponse ; si elle est égale à celle de
 * la dernière version, rien n'est écrit ;</li>
 * <li>JSON écrit dans le stockage, hors transaction ;</li>
 * <li>transaction courte, sous verrou du dossier : la dernière version est relue ; si elle a été calculée sur un
 * compteur plus récent, ce résultat est écarté (périmé) ; si elle a la même empreinte, rien n'est créé ; sinon la
 * version suivante est enregistrée. Le JSON est supprimé du stockage s'il n'est finalement pas référencé.</li>
 * </ol>
 *
 * <p><b>À l'étape 7c, le garde-fou par compteur n'est pas encore effectif</b> : rien n'incrémente
 * {@code consolidation_demande_seq} (ce sera fait dans la transaction de chaque changement du dossier, en 7d). Deux
 * recalculs manuels simultanés du même dossier ne sont donc pas départagés par lui : le verrou du dossier sérialise
 * seulement leurs enregistrements, et le second peut remplacer (par une nouvelle version) le résultat du premier
 * s'ils diffèrent. C'est acceptable pour un endpoint de développement. <b>Pour 7d</b> : la règle « un seul calcul à
 * la fois par dossier » devra couvrir aussi l'endpoint d'administration, pas seulement le poller.
 *
 * <p>Les journaux ne contiennent que des comptes, des versions, des durées et des identifiants, jamais une valeur
 * extraite (noms, adresses, montants : données personnelles de sinistres).
 */
@Service
public class ConsolidationService {

	private static final Logger log = LoggerFactory.getLogger(ConsolidationService.class);

	static final String CLE_MDC_DOSSIER = "dossierId";

	/** Ce qu'il faut savoir avant l'appel, lu dans une même transaction. */
	private record Lecture(Construction construction, Optional<Consolidation> derniere) {
	}

	private record Enregistrement(Issue issue, Integer numero) {
	}

	private final ConstructionRequete construction;
	private final ClientAuditCore core;
	private final ConsolidationRepository consolidations;
	private final DossierRepository dossiers;
	private final StockageFichiers stockage;
	private final TransactionTemplate transaction;

	ConsolidationService(ConstructionRequete construction, ClientAuditCore core, ConsolidationRepository consolidations,
			DossierRepository dossiers, StockageFichiers stockage, TransactionTemplate transaction) {
		this.construction = construction;
		this.core = core;
		this.consolidations = consolidations;
		this.dossiers = dossiers;
		this.stockage = stockage;
		this.transaction = transaction;
	}

	/**
	 * Consolide le dossier, de façon synchrone. Un échec (audit-core ou stockage) est noté sur le dossier, puis
	 * l'exception remonte.
	 *
	 * @throws DossierIntrouvableException si le dossier n'existe pas
	 * @throws AuditCoreException si audit-core est injoignable, trop lent ou répond une erreur
	 * @throws StockageException si le stockage est indisponible
	 */
	public ResultatConsolidation consolider(UUID dossierId) {
		String correlation = UUID.randomUUID().toString().replace("-", "");
		MDC.put(Correlation.CLE_MDC, correlation);
		MDC.put(CLE_MDC_DOSSIER, dossierId.toString());
		try {
			return executer(dossierId, correlation);
		}
		finally {
			MDC.remove(Correlation.CLE_MDC);
			MDC.remove(CLE_MDC_DOSSIER);
		}
	}

	private ResultatConsolidation executer(UUID dossierId, String correlation) {
		Lecture lecture = transaction.execute(statut -> new Lecture(construction.construire(dossierId),
				consolidations.findFirstByDossierIdOrderByNumeroDesc(dossierId)));
		Construction construite = lecture.construction();
		long seqLue = construite.demandeSeq();

		if (construite.dossierVide() && lecture.derniere().isEmpty()) {
			enregistrerSucces(dossierId, seqLue);
			log.info("Consolidation du dossier {} : dossier vide, jamais consolidé, aucune version", dossierId);
			return new ResultatConsolidation(Issue.DOSSIER_VIDE, null, 0);
		}

		try {
			long debut = System.nanoTime();
			ReponseBrute<ReponseConsolidation> reponse = core.consolider(construite.requete());
			int dureeMs = (int) ((System.nanoTime() - debut) / 1_000_000);
			String empreinte = empreinte(reponse.json());

			Optional<Consolidation> derniere = lecture.derniere();
			if (derniere.isPresent() && derniere.get().getEmpreinteResultat().equals(empreinte)) {
				enregistrerSucces(dossierId, seqLue);
				journaliser(dossierId, reponse, dureeMs, "résultat inchangé (version %d)"
						.formatted(derniere.get().getNumero()));
				return new ResultatConsolidation(Issue.INCHANGEE, derniere.get().getNumero(), dureeMs);
			}

			UUID id = UUID.randomUUID();
			String cle = CleStockage.consolidation(dossierId, id);
			stockage.enregistrer(cle, new ByteArrayInputStream(reponse.json()), reponse.json().length,
					MediaType.APPLICATION_JSON_VALUE);
			Enregistrement enregistrement;
			try {
				enregistrement = transaction.execute(statut -> enregistrer(dossierId, id, cle, seqLue, empreinte,
						reponse.valeur(), dureeMs, correlation));
			}
			catch (RuntimeException e) {
				supprimerApresEchec(cle, e);
				throw e;
			}
			if (enregistrement.issue() != Issue.NOUVELLE_VERSION) {
				supprimer(cle);
			}
			journaliser(dossierId, reponse, dureeMs, switch (enregistrement.issue()) {
				case NOUVELLE_VERSION -> "nouvelle version %d".formatted(enregistrement.numero());
				case INCHANGEE -> "résultat inchangé (version %d)".formatted(enregistrement.numero());
				case PERIMEE -> "résultat périmé, écarté (version %d plus récente)".formatted(enregistrement.numero());
				case DOSSIER_VIDE -> throw new IllegalStateException();
			});
			return new ResultatConsolidation(enregistrement.issue(), enregistrement.numero(), dureeMs);
		}
		catch (AuditCoreException e) {
			noterEchec(dossierId, "Consolidation impossible : " + e.getTitre());
			log.warn("Consolidation du dossier {} en erreur : {}", dossierId, e.getMessage());
			throw e;
		}
		catch (StockageException e) {
			noterEchec(dossierId, "Consolidation impossible : stockage des fichiers indisponible");
			log.error("Consolidation du dossier {} en erreur : stockage", dossierId, e);
			throw e;
		}
	}

	/** Sous verrou du dossier : version périmée, inchangée, ou nouvelle version de numéro suivant. */
	private Enregistrement enregistrer(UUID dossierId, UUID id, String cle, long seqLue, String empreinte,
			ReponseConsolidation reponse, int dureeMs, String correlation) {
		Dossier dossier = dossiers.findAvecVerrouById(dossierId)
				.orElseThrow(() -> new DossierIntrouvableException(dossierId));
		Optional<Consolidation> derniere = consolidations.findFirstByDossierIdOrderByNumeroDesc(dossierId);
		if (derniere.isPresent() && derniere.get().getDemandeSeq() > seqLue) {
			return new Enregistrement(Issue.PERIMEE, derniere.get().getNumero());
		}
		dossier.consolidationReussie(seqLue);
		if (derniere.isPresent() && derniere.get().getEmpreinteResultat().equals(empreinte)) {
			return new Enregistrement(Issue.INCHANGEE, derniere.get().getNumero());
		}
		int numero = derniere.map(version -> version.getNumero() + 1).orElse(1);
		consolidations.save(Consolidation.builder()
				.id(id)
				.dossier(dossier)
				.numero(numero)
				.cleStockage(cle)
				.empreinteResultat(empreinte)
				.demandeSeq(seqLue)
				.versionConsolidation(reponse.versionConsolidation())
				.empreinteConsolidation(reponse.empreinteConsolidation())
				.versionDomaine(reponse.versionDomaine())
				.empreinteDomaine(reponse.empreinteDomaine())
				.nombrePieces(reponse.pieces().size())
				.nombreNonExploitables(reponse.nombreNonExploitables())
				.nombreLiens(reponse.liens().size())
				.contradictions(reponse.contradictions().stream()
						.map(c -> new ContradictionIndexee(c.id(), c.code(), c.chemin(), c.lienId(), c.intervenantIds(),
								c.resume()))
						.toList())
				.dureeMs(dureeMs)
				.correlationId(correlation)
				.build());
		return new Enregistrement(Issue.NOUVELLE_VERSION, numero);
	}

	private void enregistrerSucces(UUID dossierId, long seqLue) {
		transaction.executeWithoutResult(statut -> dossiers.findAvecVerrouById(dossierId)
				.ifPresent(dossier -> dossier.consolidationReussie(seqLue)));
	}

	/** Note l'échec sur le dossier ; sans effet (signalé) si cet enregistrement échoue lui-même. */
	private void noterEchec(UUID dossierId, String message) {
		try {
			transaction.executeWithoutResult(statut -> dossiers.findAvecVerrouById(dossierId)
					.ifPresent(dossier -> dossier.consolidationEchouee(message, Instant.now())));
		}
		catch (RuntimeException e) {
			log.error("Impossible d'enregistrer l'échec de consolidation du dossier {} ({})", dossierId, message, e);
		}
	}

	/** Comptes, version, taille et durée seulement : jamais de valeur extraite (données personnelles de sinistres). */
	private static void journaliser(UUID dossierId, ReponseBrute<ReponseConsolidation> reponse, int dureeMs,
			String issue) {
		ReponseConsolidation valeur = reponse.valeur();
		log.info("Consolidation du dossier {} : {} pièce(s) dont {} non exploitable(s), {} lien(s), {} contradiction(s),"
				+ " version {}, {} octets, {} ms : {}", dossierId, valeur.pieces().size(),
				valeur.nombreNonExploitables(), valeur.liens().size(), valeur.contradictions().size(),
				valeur.versionConsolidation(), reponse.json().length, dureeMs, issue);
	}

	private static String empreinte(byte[] json) {
		try {
			return EmpreinteSha256.calculer(new ByteArrayInputStream(json));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private void supprimer(String cle) {
		try {
			stockage.supprimer(cle);
		}
		catch (StockageException e) {
			log.warn("Fichier orphelin dans le stockage : suppression impossible de {}", cle, e);
		}
	}

	private void supprimerApresEchec(String cle, RuntimeException cause) {
		try {
			stockage.supprimer(cle);
		}
		catch (RuntimeException e) {
			log.warn("Fichier orphelin dans le stockage : suppression impossible de {} ({})", cle, cause.getMessage(), e);
			cause.addSuppressed(e);
		}
	}

	// --- Lecture -----------------------------------------------------------------------------------------------

	/**
	 * Résumé de la consolidation du dossier (une requête). Chaque appel lit la dernière version d'un dossier : une
	 * future liste de dossiers devra charger les dernières versions de tous ses dossiers en une seule requête.
	 */
	@Transactional(readOnly = true)
	public ResumeConsolidation resume(Dossier dossier) {
		Optional<Consolidation> derniere = consolidations.findFirstByDossierIdOrderByNumeroDesc(dossier.getId());
		return new ResumeConsolidation(dossier.statutConsolidation(derniere.isPresent()),
				derniere.map(Consolidation::getNumero).orElse(null),
				derniere.map(Consolidation::getDateCreation).orElse(null),
				derniere.map(Consolidation::getNombreContradictions).orElse(null));
	}

	/** Historique du dossier, de la version la plus récente à la plus ancienne. */
	@Transactional(readOnly = true)
	public List<Consolidation> historique(UUID dossierId) {
		verifierDossier(dossierId);
		return consolidations.findByDossierIdOrderByNumeroDesc(dossierId);
	}

	/** Numéro et JSON complet (tel qu'audit-core l'a renvoyé) de la dernière version. */
	public VersionJson derniere(UUID dossierId) {
		Consolidation version = transaction.execute(statut -> {
			verifierDossier(dossierId);
			return consolidations.findFirstByDossierIdOrderByNumeroDesc(dossierId).orElseThrow(
					() -> new ConsolidationIntrouvableException("Aucune consolidation pour le dossier " + dossierId));
		});
		return new VersionJson(version.getNumero(), lire(version.getCleStockage()));
	}

	/** Numéro et JSON complet d'une version donnée. */
	public VersionJson version(UUID dossierId, int numero) {
		Consolidation version = transaction.execute(statut -> {
			verifierDossier(dossierId);
			return consolidations.findByDossierIdAndNumero(dossierId, numero).orElseThrow(
					() -> new ConsolidationIntrouvableException(
							"Aucune consolidation n° %d pour le dossier %s".formatted(numero, dossierId)));
		});
		return new VersionJson(version.getNumero(), lire(version.getCleStockage()));
	}

	public record VersionJson(int numero, byte[] json) {
	}

	private void verifierDossier(UUID dossierId) {
		if (!dossiers.existsById(dossierId)) {
			throw new DossierIntrouvableException(dossierId);
		}
	}

	private byte[] lire(String cle) {
		try (InputStream contenu = stockage.lire(cle)) {
			return contenu.readAllBytes();
		}
		catch (IOException e) {
			throw new UncheckedIOException("Lecture impossible de " + cle, e);
		}
	}

}
