package com.melas.audit_api.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.melas.audit_api.domain.Dossier;

public record DossierResponse(
		UUID id,
		String referenceExterne,
		String domaine,
		String typeSinistre,
		Instant dateCreation,
		Instant dateMiseAJour,
		List<DocumentResumeResponse> documents) {

	/** Les documents du dossier doivent être chargés. */
	public static DossierResponse depuis(Dossier dossier) {
		return new DossierResponse(
				dossier.getId(),
				dossier.getReferenceExterne(),
				dossier.getDomaine(),
				dossier.getTypeSinistre(),
				dossier.getDateCreation(),
				dossier.getDateMiseAJour(),
				dossier.getDocuments().stream().map(DocumentResumeResponse::depuis).toList());
	}

}
