package com.melas.audit_api.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.service.DossierService;
import com.melas.audit_api.web.dto.CreationDossierRequest;
import com.melas.audit_api.web.dto.DocumentResponse;
import com.melas.audit_api.web.dto.DossierResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/dossiers")
public class DossierController {

	private final DossierService dossierService;
	private final DocumentService documentService;

	public DossierController(DossierService dossierService, DocumentService documentService) {
		this.dossierService = dossierService;
		this.documentService = documentService;
	}

	@PostMapping
	public ResponseEntity<DossierResponse> creer(@Valid @RequestBody CreationDossierRequest requete) {
		Dossier dossier = dossierService.creer(requete.referenceExterne(), requete.domaine(), requete.typeSinistre());
		URI emplacement = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(dossier.getId())
				.toUri();
		return ResponseEntity.created(emplacement).body(DossierResponse.depuis(dossier));
	}

	@GetMapping("/{id}")
	public DossierResponse trouver(@PathVariable UUID id) {
		return DossierResponse.depuis(dossierService.trouver(id));
	}

	@GetMapping
	public DossierResponse trouverParReference(@RequestParam("reference") String reference) {
		return DossierResponse.depuis(dossierService.trouverParReference(reference));
	}

	@PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public List<DocumentResponse> deposer(@PathVariable UUID id,
			@RequestPart("fichiers") List<MultipartFile> fichiers) {
		return documentService.deposer(id, fichiers).stream().map(DocumentResponse::depuis).toList();
	}

}
