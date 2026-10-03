package com.melas.audit_api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Dossier;

public interface DossierRepository extends JpaRepository<Dossier, UUID> {

	Optional<Dossier> findByReferenceExterne(String referenceExterne);

}
