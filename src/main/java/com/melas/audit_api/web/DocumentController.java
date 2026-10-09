package com.melas.audit_api.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.web.dto.DocumentResponse;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private final DocumentService documentService;

	public DocumentController(DocumentService documentService) {
		this.documentService = documentService;
	}

	@GetMapping("/{id}")
	public DocumentResponse trouver(@PathVariable UUID id) {
		return DocumentResponse.depuis(documentService.trouver(id));
	}

	/**
	 * Télécharge le fichier en streaming : le flux du stockage est recopié vers la réponse sans être chargé en
	 * mémoire, puis fermé par Spring. Le flux est ouvert avant l'envoi des en-têtes, pour qu'une indisponibilité
	 * du stockage produise une vraie réponse d'erreur.
	 */
	@GetMapping("/{id}/fichier")
	public ResponseEntity<InputStreamResource> telecharger(@PathVariable UUID id) {
		Document document = documentService.trouver(id);
		InputStreamResource contenu = new InputStreamResource(documentService.lireFichier(document));
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(document.getContentType()))
				.contentLength(document.getTailleOctets())
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
						.filename(document.getNomFichierOriginal(), StandardCharsets.UTF_8)
						.build()
						.toString())
				.body(contenu);
	}

	/** Supprime un document reçu ou en erreur ; 409 s'il est en cours de traitement ou déjà extrait. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void supprimer(@PathVariable UUID id) {
		documentService.supprimer(id);
	}

}
