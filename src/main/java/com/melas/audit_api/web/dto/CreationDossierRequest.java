package com.melas.audit_api.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreationDossierRequest(
		@NotBlank @Size(max = 255) String referenceExterne,
		@NotBlank @Size(max = 100) String domaine,
		@Size(max = 100) String typeSinistre) {
}
