package com.melas.audit_api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Classification;

public interface ClassificationRepository extends JpaRepository<Classification, UUID> {

	/** Classification en vigueur : la plus récente. */
	Optional<Classification> findFirstByDocumentIdOrderByDateCreationDesc(UUID documentId);

}
