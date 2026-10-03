package com.melas.audit_api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.StatutDocument;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

	List<Document> findByStatut(StatutDocument statut);

	List<Document> findByEmpreinteSha256(String empreinteSha256);

}
