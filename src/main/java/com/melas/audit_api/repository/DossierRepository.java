package com.melas.audit_api.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

	/**
	 * Demande de consolidation : incrémente le compteur en SQL (jamais en lisant puis réécrivant l'entité, ce qui
	 * perdrait un incrément concurrent), ce qui verrouille la ligne du dossier jusqu'à la fin de la transaction.
	 * N'utiliser que par {@code DemandeConsolidation}.
	 *
	 * @return 1 si le dossier existe, 0 sinon
	 */
	@Modifying
	@Query("update Dossier d set d.consolidationDemandeSeq = d.consolidationDemandeSeq + 1 where d.id = :id")
	int incrementerDemandeConsolidation(UUID id);

	/**
	 * Verrouille la ligne du dossier jusqu'à la fin de la transaction, sans la charger, au même niveau qu'un UPDATE
	 * (FOR NO KEY UPDATE). N'utiliser que par {@code DemandeConsolidation}.
	 */
	@Query(value = "SELECT id FROM dossier WHERE id = :id FOR NO KEY UPDATE", nativeQuery = true)
	Optional<UUID> verrouiller(UUID id);

	/**
	 * Dossiers dont une demande de consolidation est en attente (compteur de demande plus grand que le compteur
	 * traité) et dont la nouvelle tentative éventuelle est due : ceux qui n'ont jamais échoué d'abord, puis les plus
	 * anciennes échéances. Index partiel ix_dossier_consolidation_en_attente (V6).
	 */
	@Query("""
			select d.id from Dossier d
			where d.consolidationDemandeSeq > d.consolidationSeqTraitee
			  and (d.consolidationProchaineTentativeLe is null or d.consolidationProchaineTentativeLe <= :maintenant)
			order by d.consolidationProchaineTentativeLe asc nulls first, d.id""")
	List<UUID> findIdsAConsolider(Instant maintenant, Limit limite);

}
