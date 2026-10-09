package com.melas.audit_api.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;

/** Métadonnées d'un document ; la clé de stockage, détail interne, n'est pas exposée. */
public record DocumentResponse(
		UUID id,
		UUID dossierId,
		String nomFichierOriginal,
		String contentType,
		long tailleOctets,
		String empreinteSha256,
		Integer nombrePages,
		String typeDocument,
		StatutDocument statut,
		Instant dateReception) {

	public static DocumentResponse depuis(Document document) {
		return new DocumentResponse(
				document.getId(),
				document.getDossier().getId(),
				document.getNomFichierOriginal(),
				document.getContentType(),
				document.getTailleOctets(),
				document.getEmpreinteSha256(),
				document.getNombrePages(),
				document.getTypeDocument(),
				document.getStatut(),
				document.getDateReception());
	}

}
