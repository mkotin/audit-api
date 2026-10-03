package com.melas.audit_api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "champ_extrait")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChampExtrait {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	/** Nom du champ, défini par la configuration du domaine (ex. "total_ttc"). */
	@Column(name = "nom", nullable = false, length = 100)
	private String nom;

	@Column(name = "valeur_brute", nullable = false, columnDefinition = "text")
	private String valeurBrute;

	@Column(name = "valeur_normalisee", columnDefinition = "text")
	private String valeurNormalisee;

	@Column(name = "confiance", nullable = false, precision = 4, scale = 3)
	private BigDecimal confiance;

	/** Numéro de page, à partir de 1. */
	@Column(name = "page")
	private Integer page;

	@Embedded
	private BoiteEnglobante zone;

	/** Identifiant et version de l'extracteur ou du modèle (ex. "llm-extract-v1/claude-sonnet"). */
	@Column(name = "extrait_par", nullable = false)
	private String extraitPar;

	@Column(name = "date_extraction", nullable = false)
	private Instant dateExtraction;

	@Builder
	private ChampExtrait(String nom, String valeurBrute, String valeurNormalisee, BigDecimal confiance,
			Integer page, BoiteEnglobante zone, String extraitPar, Instant dateExtraction) {
		this.nom = Objects.requireNonNull(nom, "nom");
		this.valeurBrute = Objects.requireNonNull(valeurBrute, "valeurBrute");
		this.valeurNormalisee = valeurNormalisee;
		this.confiance = Objects.requireNonNull(confiance, "confiance");
		this.page = page;
		this.zone = zone;
		this.extraitPar = Objects.requireNonNull(extraitPar, "extraitPar");
		this.dateExtraction = dateExtraction != null ? dateExtraction : Instant.now();
	}

	/** Appelé uniquement par {@link Document#ajouterChamp} pour maintenir les deux côtés de la relation. */
	void rattacherA(Document document) {
		this.document = document;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		ChampExtrait autre = (ChampExtrait) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
