package com.melas.audit_api.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;

import jakarta.persistence.LockModeType;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

	List<Document> findByStatut(StatutDocument statut);

	List<Document> findByStatutOrderByDateReceptionAsc(StatutDocument statut);

	/**
	 * Lit le document en verrouillant sa ligne jusqu'à la fin de la transaction (SELECT … FOR UPDATE) : deux
	 * demandes concurrentes de traitement, ou une demande et une suppression, sont sérialisées.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Document d where d.id = :id")
	Optional<Document> findAvecVerrouById(UUID id);

	/** Dossier du document, sans charger ni verrouiller le document. */
	@Query("select d.dossier.id from Document d where d.id = :id")
	Optional<UUID> findDossierIdById(UUID id);

	List<Document> findByEmpreinteSha256(String empreinteSha256);

	boolean existsByDossierIdAndEmpreinteSha256(UUID dossierId, String empreinteSha256);

	/**
	 * Supprime le document seulement s'il est encore dans l'un des statuts donnés : la vérification et la
	 * suppression forment une seule requête, sans fenêtre pendant laquelle le statut pourrait changer.
	 * Les champs extraits sont supprimés par la base (ON DELETE CASCADE).
	 *
	 * @return 1 si le document a été supprimé, 0 sinon
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Document d where d.id = :id and d.statut in :statuts")
	int supprimerSiStatutParmi(UUID id, Collection<StatutDocument> statuts);

}
