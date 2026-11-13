package com.melas.audit_api.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Consolidation;

public interface ConsolidationRepository extends JpaRepository<Consolidation, UUID> {

	/** Dernière version du dossier (index unique (dossier_id, numero)). */
	Optional<Consolidation> findFirstByDossierIdOrderByNumeroDesc(UUID dossierId);

	/** Historique du dossier, de la version la plus récente à la plus ancienne. */
	List<Consolidation> findByDossierIdOrderByNumeroDesc(UUID dossierId);

	Optional<Consolidation> findByDossierIdAndNumero(UUID dossierId, int numero);

	boolean existsByDossierId(UUID dossierId);

}
