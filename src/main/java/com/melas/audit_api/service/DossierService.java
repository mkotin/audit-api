package com.melas.audit_api.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.repository.DossierRepository;
import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageFichiers;

@Service
public class DossierService {

	private static final Logger log = LoggerFactory.getLogger(DossierService.class);

	static final String CONTRAINTE_REFERENCE_UNIQUE = "uk_dossier_reference_externe";

	private final DossierRepository dossierRepository;
	private final StockageFichiers stockage;
	private final TransactionTemplate transaction;

	public DossierService(DossierRepository dossierRepository, StockageFichiers stockage,
			TransactionTemplate transaction) {
		this.dossierRepository = dossierRepository;
		this.stockage = stockage;
		this.transaction = transaction;
	}

	public Dossier creer(String referenceExterne, String domaine, String typeSinistre) {
		if (dossierRepository.existsByReferenceExterne(referenceExterne)) {
			throw new ReferenceDejaUtiliseeException(referenceExterne);
		}
		Dossier dossier = Dossier.builder()
				.referenceExterne(referenceExterne)
				.domaine(domaine)
				.typeSinistre(typeSinistre)
				.build();
		try {
			return dossierRepository.saveAndFlush(dossier);
		}
		catch (DataIntegrityViolationException e) {
			// Création concurrente avec la même référence, entre la vérification et l'insertion.
			if (ViolationContrainte.concerne(e, CONTRAINTE_REFERENCE_UNIQUE)) {
				throw new ReferenceDejaUtiliseeException(referenceExterne);
			}
			throw e;
		}
	}

	/** Dossier avec ses documents chargés. */
	@Transactional(readOnly = true)
	public Dossier trouver(UUID id) {
		return dossierRepository.findAvecDocumentsById(id).orElseThrow(() -> new DossierIntrouvableException(id));
	}

	/** Dossier avec ses documents chargés. */
	@Transactional(readOnly = true)
	public Dossier trouverParReference(String referenceExterne) {
		return dossierRepository.findAvecDocumentsByReferenceExterne(referenceExterne)
				.orElseThrow(() -> new DossierIntrouvableException(referenceExterne));
	}

	/**
	 * Supprime définitivement le dossier, tous ses documents quel que soit leur statut, leurs champs extraits et
	 * tous les fichiers du dossier dans le stockage (y compris d'éventuels orphelins). Réservé à l'administration.
	 * La base est traitée d'abord ; si le stockage échoue ensuite, les fichiers restants sont signalés dans les logs.
	 *
	 * @throws DossierIntrouvableException si le dossier n'existe pas
	 */
	public void supprimer(UUID id) {
		transaction.executeWithoutResult(statut -> {
			Dossier dossier = dossierRepository.findById(id).orElseThrow(() -> new DossierIntrouvableException(id));
			dossierRepository.delete(dossier);
		});
		String prefixe = CleStockage.prefixeDossier(id);
		try {
			int supprimes = stockage.supprimerParPrefixe(prefixe);
			log.info("Dossier {} supprimé avec {} fichier(s)", id, supprimes);
		}
		catch (StockageException e) {
			log.error("Fichiers orphelins dans le stockage sous le préfixe {} (dossier {} supprimé en base)",
					prefixe, id, e);
		}
	}

}
