package com.melas.audit_api.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.melas.audit_api.domain.Dossier;

import jakarta.persistence.LockModeType;

public interface DossierRepository extends JpaRepository<Dossier, UUID> {

	Optional<Dossier> findByReferenceExterne(String referenceExterne);

	boolean existsByReferenceExterne(String referenceExterne);

	/** Charge le dossier et ses documents en une requête (open-in-view est désactivé). */
	@EntityGraph(attributePaths = "documents")
	Optional<Dossier> findAvecDocumentsById(UUID id);

	@EntityGraph(attributePaths = "documents")
	Optional<Dossier> findAvecDocumentsByReferenceExterne(String referenceExterne);

	/**
	 * Lit le dossier en verrouillant sa ligne jusqu'à la fin de la transaction (SELECT … FOR UPDATE) : deux
	 * enregistrements de consolidation du même dossier sont sérialisés (numéro de version, garde-fou du compteur).
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Dossier d where d.id = :id")
	Optional<Dossier> findAvecVerrouById(UUID id);

}
