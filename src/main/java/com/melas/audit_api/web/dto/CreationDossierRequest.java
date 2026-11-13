package com.melas.audit_api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Création d'un dossier. L'ancien champ {@code typeSinistre} (supprimé, migration V4) est ignoré s'il est encore
 * envoyé : les champs inconnus du JSON ne font pas échouer la requête.
 */
public record CreationDossierRequest(
		@NotBlank @Size(max = 255) String referenceExterne,
		@NotBlank @Size(max = 100) String domaine) {
}
