package com.melas.audit_api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Un passage d'extraction (POST /v1/extraction d'audit-core), à partir d'un résultat d'OCR et d'un type de document.
 * La réponse complète est conservée en JSON dans le stockage ; ses champs sont des {@link ChampExtrait}. Les passages
 * successifs sont tous conservés : le plus récent fait foi.
 */
@Entity
@Table(name = "extraction")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Extraction implements Persistable<UUID> {

	/** Assigné par l'application : la clé du JSON dans le stockage en dépend, et il est écrit avant la ligne. */
	@Id
	private UUID id;

	@Transient
	@Getter(AccessLevel.NONE)
	private boolean nouveau = true;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "resultat_ocr_id", nullable = false)
	private ResultatOcr resultatOcr;

	@Column(name = "cle_stockage", nullable = false, length = 1024)
	private String cleStockage;

	@Column(name = "type_document", nullable = false, length = 100)
	private String typeDocument;

	/** Confiance globale du document : la plus basse des champs obligatoires présents (0 sinon). */
	@Column(name = "confiance", nullable = false, precision = 4, scale = 3)
	private BigDecimal confiance;

	/** Alertes du document ; celles des champs sont portées par chaque {@link ChampExtrait}. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "alertes", nullable = false)
	private List<Alerte> alertes;

	@Column(name = "pages_analysees", nullable = false)
	private int pagesAnalysees;

	@Column(name = "modele", nullable = false)
	private String modele;

	@Column(name = "fournisseur", nullable = false, length = 100)
	private String fournisseur;

	@Column(name = "version_consigne", nullable = false, length = 50)
	private String versionConsigne;

	@Column(name = "empreinte_consigne", nullable = false, length = 100)
	private String empreinteConsigne;

	@Column(name = "version_domaine", nullable = false, length = 50)
	private String versionDomaine;

	@Column(name = "empreinte_domaine", nullable = false, length = 100)
	private String empreinteDomaine;

	@Column(name = "jetons_entree")
	private Integer jetonsEntree;

	@Column(name = "jetons_sortie")
	private Integer jetonsSortie;

	@Column(name = "duree_ms", nullable = false)
	private int dureeMs;

	@Column(name = "tentatives", nullable = false)
	private int tentatives;

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@OneToMany(mappedBy = "extraction", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("ordre")
	private List<ChampExtrait> champs = new ArrayList<>();

	@Builder
	private Extraction(UUID id, Document document, ResultatOcr resultatOcr, String cleStockage, String typeDocument,
			double confiance, List<Alerte> alertes, int pagesAnalysees, String modele, String fournisseur,
			String versionConsigne, String empreinteConsigne, String versionDomaine, String empreinteDomaine,
			Integer jetonsEntree, Integer jetonsSortie, int dureeMs, int tentatives) {
		this.id = Objects.requireNonNull(id, "id");
		this.document = Objects.requireNonNull(document, "document");
		this.resultatOcr = Objects.requireNonNull(resultatOcr, "resultatOcr");
		this.cleStockage = Objects.requireNonNull(cleStockage, "cleStockage");
		this.typeDocument = Objects.requireNonNull(typeDocument, "typeDocument");
		this.confiance = Confiance.arrondir(confiance);
		this.alertes = alertes == null ? List.of() : List.copyOf(alertes);
		this.pagesAnalysees = pagesAnalysees;
		this.modele = Objects.requireNonNull(modele, "modele");
		this.fournisseur = Objects.requireNonNull(fournisseur, "fournisseur");
		this.versionConsigne = Objects.requireNonNull(versionConsigne, "versionConsigne");
		this.empreinteConsigne = Objects.requireNonNull(empreinteConsigne, "empreinteConsigne");
		this.versionDomaine = Objects.requireNonNull(versionDomaine, "versionDomaine");
		this.empreinteDomaine = Objects.requireNonNull(empreinteDomaine, "empreinteDomaine");
		this.jetonsEntree = jetonsEntree;
		this.jetonsSortie = jetonsSortie;
		this.dureeMs = dureeMs;
		this.tentatives = tentatives;
	}

	/** Identifiant de l'extracteur reporté dans {@link ChampExtrait#getExtraitPar()} : modèle et version de consigne. */
	public String extracteur() {
		return "%s/consigne-%s".formatted(modele, versionConsigne);
	}

	public List<ChampExtrait> getChamps() {
		return Collections.unmodifiableList(champs);
	}

	/** Ajoute un champ à ce passage ; le champ est aussi rattaché au document du passage. */
	public void ajouterChamp(ChampExtrait champ) {
		Objects.requireNonNull(champ, "champ");
		champs.add(champ);
		champ.rattacherAExtraction(this);
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
		Extraction autre = (Extraction) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
