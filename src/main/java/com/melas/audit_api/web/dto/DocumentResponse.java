package com.melas.audit_api.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;
import com.melas.audit_api.traitement.SyntheseTraitement;

/**
 * Métadonnées d'un document et état de son traitement ; la clé de stockage, détail interne, n'est pas exposée.
 *
 * @param typeDocument type issu de la dernière classification ("inconnu" si aucun type n'a été reconnu)
 * @param messageErreur cause de l'erreur, au statut ERREUR
 * @param confianceClassification confiance de la dernière classification
 */
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
		String messageErreur,
		Instant dateReception,
		BigDecimal confianceClassification,
		Instant dateDernierOcr,
		Instant dateDerniereClassification,
		Instant dateDerniereExtraction) {

	/** Document qui n'a pas encore été traité (juste déposé). */
	public static DocumentResponse depuis(Document document) {
		return depuis(document, SyntheseTraitement.AUCUN);
	}

	public static DocumentResponse depuis(Document document, SyntheseTraitement synthese) {
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
				document.getMessageErreur(),
				document.getDateReception(),
				synthese.confianceClassification(),
				synthese.dateDernierOcr(),
				synthese.dateDerniereClassification(),
				synthese.dateDerniereExtraction());
	}

}
