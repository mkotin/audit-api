package com.melas.audit_api.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

	List<Document> findByStatut(StatutDocument statut);

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
