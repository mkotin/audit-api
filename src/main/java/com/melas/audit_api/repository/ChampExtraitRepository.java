package com.melas.audit_api.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.melas.audit_api.domain.ChampExtrait;

public interface ChampExtraitRepository extends JpaRepository<ChampExtrait, UUID> {

	/** Champs de plusieurs extractions, en une seule requête, dans l'ordre de chaque extraction. */
	@Query("select c from ChampExtrait c where c.extraction.id in :extractionIds "
			+ "order by c.extraction.id, c.ordre, c.nom")
	List<ChampExtrait> findByExtractionIdIn(Collection<UUID> extractionIds);

	List<ChampExtrait> findByDocumentId(UUID documentId);

	List<ChampExtrait> findByExtractionIdOrderByOrdreAsc(UUID extractionId);

}
