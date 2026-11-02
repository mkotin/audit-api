package com.melas.audit_api.web;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.traitement.TraitementService;
import com.melas.audit_api.web.dto.ChampsDocumentResponse;
import com.melas.audit_api.web.dto.DocumentResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "documents", description = "Documents d'un dossier et résultats de leur traitement (OCR, classification, "
		+ "extraction des champs par audit-core)")
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private final DocumentService documentService;
	private final TraitementService traitementService;

	public DocumentController(DocumentService documentService, TraitementService traitementService) {
		this.documentService = documentService;
		this.traitementService = traitementService;
	}

	@Operation(summary = "Métadonnées et état du traitement d'un document",
			description = "Statut (RECU, EN_COURS, EXTRAIT, ERREUR) et message d'erreur, type détecté, confiance de la "
					+ "classification et dates des derniers passages d'OCR, de classification et d'extraction.")
	@ApiResponse(responseCode = "200", description = "Document trouvé")
	@ApiResponse(responseCode = "404", description = "Document introuvable", content = @Content(
			mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/{id}")
	public DocumentResponse trouver(@PathVariable UUID id) {
		return reponse(id);
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

	@Operation(summary = "Relancer le traitement complet",
			description = "Refait l'OCR, la classification et l'extraction d'un document reçu, en erreur ou extrait. "
					+ "Le traitement est asynchrone : le document est renvoyé EN_COURS, suivre son statut par "
					+ "GET /api/documents/{id}. Les passages précédents sont conservés.")
	@ApiResponse(responseCode = "202", description = "Traitement lancé, document EN_COURS")
	@ApiResponse(responseCode = "404", description = "Document introuvable", content = @Content(
			mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Traitement déjà en cours", content = @Content(
			mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "503", description = "File de traitement pleine : le document passe en ERREUR, à "
			+ "relancer plus tard", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/{id}/traitement")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public DocumentResponse relancerTraitement(@PathVariable UUID id) {
		traitementService.relancerTraitement(id);
		return reponse(id);
	}

	@Operation(summary = "Relancer seulement l'extraction",
			description = "Refait l'extraction des champs à partir du dernier résultat d'OCR et du type connu du "
					+ "document, sans refaire l'OCR ni la classification. Asynchrone, comme le traitement complet. "
					+ "Le passage d'extraction précédent est conservé.")
	@ApiResponse(responseCode = "202", description = "Extraction lancée, document EN_COURS")
	@ApiResponse(responseCode = "404", description = "Document introuvable", content = @Content(
			mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409", description = "Traitement en cours, aucun résultat d'OCR, ou type de document "
			+ "non reconnu", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "503", description = "File de traitement pleine : le document passe en ERREUR, à "
			+ "relancer plus tard", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/{id}/extraction")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public DocumentResponse relancerExtraction(@PathVariable UUID id) {
		traitementService.relancerExtraction(id);
		return reponse(id);
	}

	@Operation(summary = "Champs extraits",
			description = "Champs de la dernière extraction, dans l'ordre du type de document : valeur brute et "
					+ "normalisée, confiance, page, zone et alertes de chaque champ ; alertes et confiance globale du "
					+ "document. Les champs d'une liste sont nommés par leur chemin, ex. lignes[2].montant_ht.")
	@ApiResponse(responseCode = "200", description = "Champs de la dernière extraction")
	@ApiResponse(responseCode = "404", description = "Document introuvable, ou jamais extrait", content = @Content(
			mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/{id}/champs")
	public ChampsDocumentResponse champs(@PathVariable UUID id) {
		return ChampsDocumentResponse.depuis(id, traitementService.champs(id));
	}

	private DocumentResponse reponse(UUID id) {
		return DocumentResponse.depuis(documentService.trouver(id), traitementService.synthese(id));
	}

}
