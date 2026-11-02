package com.melas.audit_api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.ResultatOcr;

public interface ResultatOcrRepository extends JpaRepository<ResultatOcr, UUID> {

	/** Passage d'OCR en vigueur : le plus récent. */
	Optional<ResultatOcr> findFirstByDocumentIdOrderByDateCreationDesc(UUID documentId);

	long countByDocumentId(UUID documentId);

}
