package com.melas.audit_api.web.admin;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.consolidation.ConsolidationService;
import com.melas.audit_api.service.DossierService;
import com.melas.audit_api.web.dto.ResultatConsolidationResponse;

/**
 * Opérations d'administration, réservées au développement : hors du profil {@code dev}, ce contrôleur n'est pas
 * enregistré et ses routes n'existent pas (404).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/admin/dossiers")
public class AdminDossierController {

	private final DossierService dossierService;
	private final ConsolidationService consolidationService;

	public AdminDossierController(DossierService dossierService, ConsolidationService consolidationService) {
		this.dossierService = dossierService;
		this.consolidationService = consolidationService;
	}

	/** Supprime le dossier, tous ses documents (quel que soit leur statut) et tous ses fichiers. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void supprimer(@PathVariable UUID id) {
		dossierService.supprimer(id);
	}

	/**
	 * Force un recalcul synchrone de la consolidation : nouvelle version, résultat inchangé (même empreinte), résultat
	 * périmé, ou dossier vide jamais consolidé. Calcul déjà en cours pour ce dossier (poller ou autre appel) : 409,
	 * sans second calcul. audit-core injoignable : 503 ; délai dépassé : 504 ; autre erreur d'audit-core : 502.
	 */
	@PostMapping("/{id}/consolidation")
	public ResultatConsolidationResponse consolider(@PathVariable UUID id) {
		return ResultatConsolidationResponse.depuis(consolidationService.consolider(id));
	}

}
