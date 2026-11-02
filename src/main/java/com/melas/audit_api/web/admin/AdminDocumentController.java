package com.melas.audit_api.web.admin;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.traitement.TraitementService;
import com.melas.audit_api.web.dto.UrlTemporaireResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Outils de développement sur les documents : hors du profil {@code dev}, ce contrôleur n'est pas enregistré et
 * ses routes n'existent pas (404).
 */
@Tag(name = "admin (dev)", description = "Outils de développement, présents seulement dans le profil dev")
@Profile("dev")
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

	private final DocumentService documentService;
	private final TraitementService traitementService;

	public AdminDocumentController(DocumentService documentService, TraitementService traitementService) {
		this.documentService = documentService;
		this.traitementService = traitementService;
	}

	/**
	 * URL signée du fichier, pour appeler audit-core à la main (POST /v1/ocr).
	 * Son hôte est celui de {@code app.stockage.endpoint-public}.
	 */
	@Operation(summary = "URL signée du fichier", description = "Pour appeler audit-core à la main (POST /v1/ocr).")
	@GetMapping("/{id}/url-temporaire")
	public UrlTemporaireResponse urlTemporaire(@PathVariable UUID id) {
		return UrlTemporaireResponse.depuis(documentService.genererUrlTemporaire(id));
	}

	@Operation(summary = "JSON du dernier OCR",
			description = "Réponse complète de POST /v1/ocr d'audit-core, telle qu'elle est conservée dans le stockage.")
	@GetMapping(path = "/{id}/ocr", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> ocr(@PathVariable UUID id) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(traitementService.jsonDernierOcr(id));
	}

	@Operation(summary = "JSON de la dernière extraction",
			description = "Réponse complète de POST /v1/extraction d'audit-core (données, provenance, alertes), telle "
					+ "qu'elle est conservée dans le stockage.")
	@GetMapping(path = "/{id}/extraction", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> extraction(@PathVariable UUID id) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
				.body(traitementService.jsonDerniereExtraction(id));
	}

}
