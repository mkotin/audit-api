package com.melas.audit_api.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Un passage d'OCR (POST /v1/ocr d'audit-core). La réponse complète est conservée en JSON dans le stockage ; seules
 * ses métadonnées sont en base. Les passages successifs d'un document sont tous conservés : le plus récent fait foi.
 */
@Entity
@Table(name = "resultat_ocr")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultatOcr implements Persistable<UUID> {

	/** Assigné par l'application : la clé du JSON dans le stockage en dépend, et il est écrit avant la ligne. */
	@Id
	private UUID id;

	@Transient
	@Getter(AccessLevel.NONE)
	private boolean nouveau = true;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	@Column(name = "cle_stockage", nullable = false, length = 1024)
	private String cleStockage;

	@Column(name = "version_service", nullable = false, length = 50)
	private String versionService;

	@Column(name = "type_fichier", nullable = false, length = 10)
	private String typeFichier;

	@Column(name = "methode_globale", nullable = false, length = 10)
	private String methodeGlobale;

	@Column(name = "moteurs")
	private String moteurs;

	@Column(name = "nombre_pages", nullable = false)
	private int nombrePages;

	@Column(name = "duree_telechargement_ms", nullable = false)
	private int dureeTelechargementMs;

	@Column(name = "duree_extraction_ms", nullable = false)
	private int dureeExtractionMs;

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@Builder
	private ResultatOcr(UUID id, Document document, String cleStockage, String versionService, String typeFichier,
			String methodeGlobale, String moteurs, int nombrePages, int dureeTelechargementMs, int dureeExtractionMs) {
		this.id = Objects.requireNonNull(id, "id");
		this.document = Objects.requireNonNull(document, "document");
		this.cleStockage = Objects.requireNonNull(cleStockage, "cleStockage");
		this.versionService = Objects.requireNonNull(versionService, "versionService");
		this.typeFichier = Objects.requireNonNull(typeFichier, "typeFichier");
		this.methodeGlobale = Objects.requireNonNull(methodeGlobale, "methodeGlobale");
		this.moteurs = moteurs;
		this.nombrePages = nombrePages;
		this.dureeTelechargementMs = dureeTelechargementMs;
		this.dureeExtractionMs = dureeExtractionMs;
	}

	@Override
	public boolean isNew() {
		return nouveau;
	}

	@PostPersist
	@PostLoad
	void marquerPersiste() {
		this.nouveau = false;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		ResultatOcr autre = (ResultatOcr) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
