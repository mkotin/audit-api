package com.melas.audit_api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.melas.audit_api.domain.Extraction;

public interface ExtractionRepository extends JpaRepository<Extraction, UUID> {

	/**
	 * Extraction en vigueur (la plus récente) de chacun des documents, en une seule requête (DISTINCT ON de
	 * PostgreSQL, index (document_id, date_creation DESC)) ; l'identifiant départage deux passages de même date. La
	 * collection ne doit pas être vide.
	 */
	@Query(value = """
			SELECT DISTINCT ON (document_id) * FROM extraction
			WHERE document_id IN (:documentIds)
			ORDER BY document_id, date_creation DESC, id DESC""", nativeQuery = true)
	List<Extraction> findDernieresParDocumentIdIn(Collection<UUID> documentIds);

	/** Extraction en vigueur : la plus récente. */
	Optional<Extraction> findFirstByDocumentIdOrderByDateCreationDesc(UUID documentId);

	List<Extraction> findByDocumentIdOrderByDateCreationAsc(UUID documentId);

}
