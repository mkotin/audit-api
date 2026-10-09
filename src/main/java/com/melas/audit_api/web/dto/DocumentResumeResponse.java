package com.melas.audit_api.web.dto;

import java.util.UUID;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;

public record DocumentResumeResponse(
		UUID id,
		String nomFichierOriginal,
		String contentType,
		long tailleOctets,
		StatutDocument statut) {

	public static DocumentResumeResponse depuis(Document document) {
		return new DocumentResumeResponse(
				document.getId(),
				document.getNomFichierOriginal(),
				document.getContentType(),
				document.getTailleOctets(),
				document.getStatut());
	}

}
