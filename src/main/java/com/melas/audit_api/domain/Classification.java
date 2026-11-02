package com.melas.audit_api.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
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

/**
 * Un passage de classification (POST /v1/classification d'audit-core), à partir d'un résultat d'OCR. Le type retenu
 * est aussi recopié dans {@link Document#getTypeDocument()}.
 */
@Entity
@Table(name = "classification")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Classification {

	/** Type renvoyé par audit-core quand aucun type du domaine ne convient. */
	public static final String TYPE_INCONNU = "inconnu";

	/** Bloc du résultat d'OCR cité par le LLM pour justifier son choix ; page nulle si le bloc n'existe pas. */
	public record BlocCite(String id, Integer page) {
	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id", nullable = false)
	private Document document;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "resultat_ocr_id", nullable = false)
	private ResultatOcr resultatOcr;

	@Column(name = "type_document", nullable = false, length = 100)
	private String typeDocument;

	@Column(name = "confiance", nullable = false, precision = 4, scale = 3)
	private BigDecimal confiance;

	@Column(name = "type_reference", nullable = false, length = 100)
	private String typeReference;

	@Column(name = "raison", columnDefinition = "text")
	private String raison;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "blocs", nullable = false)
	private List<BlocCite> blocs;

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

	@Column(name = "jetons_entree")
	private Integer jetonsEntree;

	@Column(name = "jetons_sortie")
	private Integer jetonsSortie;

	@Column(name = "duree_ms", nullable = false)
	private int dureeMs;

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@Builder
	private Classification(Document document, ResultatOcr resultatOcr, String typeDocument, double confiance,
			String typeReference, String raison, List<BlocCite> blocs, List<Alerte> alertes, int pagesAnalysees,
			String modele, String fournisseur, String versionConsigne, String empreinteConsigne, String versionDomaine,
			Integer jetonsEntree, Integer jetonsSortie, int dureeMs) {
		this.document = Objects.requireNonNull(document, "document");
		this.resultatOcr = Objects.requireNonNull(resultatOcr, "resultatOcr");
		this.typeDocument = Objects.requireNonNull(typeDocument, "typeDocument");
		this.confiance = Confiance.arrondir(confiance);
		this.typeReference = Objects.requireNonNull(typeReference, "typeReference");
		this.raison = raison;
		this.blocs = blocs == null ? List.of() : List.copyOf(blocs);
		this.alertes = alertes == null ? List.of() : List.copyOf(alertes);
		this.pagesAnalysees = pagesAnalysees;
		this.modele = Objects.requireNonNull(modele, "modele");
		this.fournisseur = Objects.requireNonNull(fournisseur, "fournisseur");
		this.versionConsigne = Objects.requireNonNull(versionConsigne, "versionConsigne");
		this.empreinteConsigne = Objects.requireNonNull(empreinteConsigne, "empreinteConsigne");
		this.versionDomaine = Objects.requireNonNull(versionDomaine, "versionDomaine");
		this.jetonsEntree = jetonsEntree;
		this.jetonsSortie = jetonsSortie;
		this.dureeMs = dureeMs;
	}

	public boolean estInconnu() {
		return TYPE_INCONNU.equals(typeDocument);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		Classification autre = (Classification) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
