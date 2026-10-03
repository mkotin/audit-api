package com.melas.audit_api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.ChampExtrait;

public interface ChampExtraitRepository extends JpaRepository<ChampExtrait, UUID> {

	List<ChampExtrait> findByDocumentId(UUID documentId);

}
