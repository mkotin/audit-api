package com.melas.audit_api.web.admin;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.web.dto.UrlTemporaireResponse;

/**
 * Outils de développement sur les documents : hors du profil {@code dev}, ce contrôleur n'est pas enregistré et
 * ses routes n'existent pas (404).
 */
@Profile("dev")
@RestController
@RequestMapping("/api/admin/documents")
public class AdminDocumentController {

	private final DocumentService documentService;

	public AdminDocumentController(DocumentService documentService) {
		this.documentService = documentService;
	}

	/**
	 * URL signée du fichier, pour appeler audit-core à la main (POST /v1/ocr) en attendant l'orchestration.
	 * Son hôte est celui de {@code app.stockage.endpoint-public}.
	 */
	@GetMapping("/{id}/url-temporaire")
	public UrlTemporaireResponse urlTemporaire(@PathVariable UUID id) {
		return UrlTemporaireResponse.depuis(documentService.genererUrlTemporaire(id));
	}

}
