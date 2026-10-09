package com.melas.audit_api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Dossier;

public interface DossierRepository extends JpaRepository<Dossier, UUID> {

	Optional<Dossier> findByReferenceExterne(String referenceExterne);

	boolean existsByReferenceExterne(String referenceExterne);

	/** Charge le dossier et ses documents en une requête (open-in-view est désactivé). */
	@EntityGraph(attributePaths = "documents")
	Optional<Dossier> findAvecDocumentsById(UUID id);

	@EntityGraph(attributePaths = "documents")
	Optional<Dossier> findAvecDocumentsByReferenceExterne(String referenceExterne);

}
