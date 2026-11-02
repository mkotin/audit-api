package com.melas.audit_api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Extraction;

public interface ExtractionRepository extends JpaRepository<Extraction, UUID> {

	/** Extraction en vigueur : la plus récente. */
	Optional<Extraction> findFirstByDocumentIdOrderByDateCreationDesc(UUID documentId);

	List<Extraction> findByDocumentIdOrderByDateCreationAsc(UUID documentId);

}
