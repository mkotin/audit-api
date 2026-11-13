package com.melas.audit_api.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
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
 * Une version de la consolidation d'un dossier (POST /v1/consolidation d'audit-core). Le JSON complet, tel qu'audit-core
 * l'a renvoyé, est dans le stockage ; la ligne en garde l'empreinte, les versions, des comptes et l'index des
 * contradictions. Toutes les versions sont conservées ; une version n'est créée que si l'empreinte du résultat change.
 */
@Entity
@Table(name = "consolidation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Consolidation implements Persistable<UUID> {

	/** Assigné par l'application : la clé du JSON dans le stockage en dépend, et il est écrit avant la ligne. */
	@Id
	private UUID id;

	@Transient
	@Getter(AccessLevel.NONE)
	private boolean nouveau = true;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "dossier_id", nullable = false)
	private Dossier dossier;

	@Column(name = "numero", nullable = false)
	private int numero;

	@Column(name = "cle_stockage", nullable = false, length = 1024)
	private String cleStockage;

	/** SHA-256 (hexadécimal) des octets de la réponse d'audit-core. */
	@Column(name = "empreinte_resultat", nullable = false, length = 64)
	private String empreinteResultat;

	/** Compteur de demande du dossier lu avant l'appel : garde-fou contre l'écrasement par un calcul plus ancien. */
	@Column(name = "demande_seq", nullable = false)
	private long demandeSeq;

	@Column(name = "version_consolidation", nullable = false, length = 20)
	private String versionConsolidation;

	@Column(name = "empreinte_consolidation", nullable = false, length = 100)
	private String empreinteConsolidation;

	@Column(name = "version_domaine", nullable = false, length = 50)
	private String versionDomaine;

	@Column(name = "empreinte_domaine", nullable = false, length = 100)
	private String empreinteDomaine;

	@Column(name = "nombre_pieces", nullable = false)
	private int nombrePieces;

	@Column(name = "nombre_non_exploitables", nullable = false)
	private int nombreNonExploitables;

	@Column(name = "nombre_liens", nullable = false)
	private int nombreLiens;

	@Column(name = "nombre_contradictions", nullable = false)
	private int nombreContradictions;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "contradictions", nullable = false)
	private List<ContradictionIndexee> contradictions;

	@Column(name = "duree_ms", nullable = false)
	private int dureeMs;

	@Column(name = "correlation_id", length = 64)
	private String correlationId;

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@Builder
	private Consolidation(UUID id, Dossier dossier, int numero, String cleStockage, String empreinteResultat,
			long demandeSeq, String versionConsolidation, String empreinteConsolidation, String versionDomaine,
			String empreinteDomaine, int nombrePieces, int nombreNonExploitables, int nombreLiens,
			List<ContradictionIndexee> contradictions, int dureeMs, String correlationId) {
		this.id = Objects.requireNonNull(id, "id");
		this.dossier = Objects.requireNonNull(dossier, "dossier");
		this.numero = numero;
		this.cleStockage = Objects.requireNonNull(cleStockage, "cleStockage");
		this.empreinteResultat = Objects.requireNonNull(empreinteResultat, "empreinteResultat");
		this.demandeSeq = demandeSeq;
		this.versionConsolidation = Objects.requireNonNull(versionConsolidation, "versionConsolidation");
		this.empreinteConsolidation = Objects.requireNonNull(empreinteConsolidation, "empreinteConsolidation");
		this.versionDomaine = Objects.requireNonNull(versionDomaine, "versionDomaine");
		this.empreinteDomaine = Objects.requireNonNull(empreinteDomaine, "empreinteDomaine");
		this.nombrePieces = nombrePieces;
		this.nombreNonExploitables = nombreNonExploitables;
		this.nombreLiens = nombreLiens;
		this.contradictions = contradictions == null ? List.of() : List.copyOf(contradictions);
		this.nombreContradictions = this.contradictions.size();
		this.dureeMs = dureeMs;
		this.correlationId = correlationId;
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
		Consolidation autre = (Consolidation) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
