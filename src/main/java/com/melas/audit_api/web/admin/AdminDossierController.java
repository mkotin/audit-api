package com.melas.audit_api.web.admin;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.service.DossierService;

/**
 * Opérations d'administration destructives, réservées au développement : hors du profil {@code dev},
 * ce contrôleur n'est pas enregistré et ses routes n'existent pas (404).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/admin/dossiers")
public class AdminDossierController {

	private final DossierService dossierService;

	public AdminDossierController(DossierService dossierService) {
		this.dossierService = dossierService;
	}

	/** Supprime le dossier, tous ses documents (quel que soit leur statut) et tous ses fichiers. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void supprimer(@PathVariable UUID id) {
		dossierService.supprimer(id);
	}

}
