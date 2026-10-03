package com.melas.audit_api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "dossier")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dossier {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(name = "reference_externe", nullable = false, unique = true)
	private String referenceExterne;

	@Column(name = "domaine", nullable = false, length = 100)
	private String domaine;

	@Column(name = "type_sinistre", length = 100)
	private String typeSinistre;

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@UpdateTimestamp
	@Column(name = "date_mise_a_jour", nullable = false)
	private Instant dateMiseAJour;

	@OneToMany(mappedBy = "dossier", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Document> documents = new ArrayList<>();

	@Builder
	private Dossier(String referenceExterne, String domaine, String typeSinistre) {
		this.referenceExterne = Objects.requireNonNull(referenceExterne, "referenceExterne");
		this.domaine = Objects.requireNonNull(domaine, "domaine");
		this.typeSinistre = typeSinistre;
	}

	public List<Document> getDocuments() {
		return Collections.unmodifiableList(documents);
	}

	public void ajouterDocument(Document document) {
		Objects.requireNonNull(document, "document");
		documents.add(document);
		document.rattacherA(this);
	}

	public void retirerDocument(Document document) {
		if (documents.remove(document)) {
			document.rattacherA(null);
		}
	}

	public void definirTypeSinistre(String typeSinistre) {
		this.typeSinistre = typeSinistre;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		Dossier autre = (Dossier) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
