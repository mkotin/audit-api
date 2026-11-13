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

import com.melas.audit_api.consolidation.ConsolidationService;
import com.melas.audit_api.consolidation.ConsolidationService.VersionJson;
import com.melas.audit_api.consolidation.ResumeConsolidation;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.service.DocumentService;
import com.melas.audit_api.service.DossierService;
import com.melas.audit_api.web.dto.ConsolidationVersionResponse;
import com.melas.audit_api.web.dto.CreationDossierRequest;
import com.melas.audit_api.web.dto.DocumentResponse;
import com.melas.audit_api.web.dto.DossierResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/dossiers")
public class DossierController {

	/** En-tête qui donne le numéro de la version renvoyée par GET …/consolidations/derniere. */
	static final String ENTETE_NUMERO_CONSOLIDATION = "X-Consolidation-Numero";

	private final DossierService dossierService;
	private final DocumentService documentService;
	private final ConsolidationService consolidationService;

	public DossierController(DossierService dossierService, DocumentService documentService,
			ConsolidationService consolidationService) {
		this.dossierService = dossierService;
		this.documentService = documentService;
		this.consolidationService = consolidationService;
	}

	@PostMapping
	public ResponseEntity<DossierResponse> creer(@Valid @RequestBody CreationDossierRequest requete) {
		Dossier dossier = dossierService.creer(requete.referenceExterne(), requete.domaine());
		URI emplacement = ServletUriComponentsBuilder.fromCurrentRequest()
				.path("/{id}")
				.buildAndExpand(dossier.getId())
				.toUri();
		return ResponseEntity.created(emplacement).body(DossierResponse.depuis(dossier, ResumeConsolidation.aucune()));
	}

	@GetMapping("/{id}")
	public DossierResponse trouver(@PathVariable UUID id) {
		Dossier dossier = dossierService.trouver(id);
		return DossierResponse.depuis(dossier, consolidationService.resume(dossier));
	}

	@GetMapping
	public DossierResponse trouverParReference(@RequestParam("reference") String reference) {
		Dossier dossier = dossierService.trouverParReference(reference);
		return DossierResponse.depuis(dossier, consolidationService.resume(dossier));
	}

	@PostMapping(path = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public List<DocumentResponse> deposer(@PathVariable UUID id,
			@RequestPart("fichiers") List<MultipartFile> fichiers) {
		return documentService.deposer(id, fichiers).stream().map(DocumentResponse::depuis).toList();
	}

	/** Historique des versions de la consolidation, de la plus récente à la plus ancienne. */
	@GetMapping("/{id}/consolidations")
	public List<ConsolidationVersionResponse> consolidations(@PathVariable UUID id) {
		return consolidationService.historique(id).stream().map(ConsolidationVersionResponse::depuis).toList();
	}

	/** JSON complet de la dernière version, tel qu'audit-core l'a renvoyé ; son numéro dans un en-tête. */
	@GetMapping(path = "/{id}/consolidations/derniere", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> derniereConsolidation(@PathVariable UUID id) {
		return json(consolidationService.derniere(id));
	}

	/** JSON complet d'une version donnée. */
	@GetMapping(path = "/{id}/consolidations/{numero}", produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<byte[]> consolidation(@PathVariable UUID id, @PathVariable int numero) {
		return json(consolidationService.version(id, numero));
	}

	private static ResponseEntity<byte[]> json(VersionJson version) {
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.header(ENTETE_NUMERO_CONSOLIDATION, String.valueOf(version.numero()))
				.body(version.json());
	}

}
