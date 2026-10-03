package com.melas.audit_api.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Document {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "dossier_id", nullable = false)
	private Dossier dossier;

	@Column(name = "type_document", length = 100)
	private String typeDocument;

	@Column(name = "nom_fichier_original", nullable = false, length = 500)
	private String nomFichierOriginal;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "taille_octets", nullable = false)
	private long tailleOctets;

	/** Chemin ou clé du fichier dans le stockage externe ; le contenu binaire n'est jamais en base. */
	@Column(name = "cle_stockage", nullable = false, length = 1024)
	private String cleStockage;

	@Column(name = "empreinte_sha256", nullable = false, length = 64)
	private String empreinteSha256;

	@Column(name = "nombre_pages")
	private Integer nombrePages;

	@Enumerated(EnumType.STRING)
	@Column(name = "statut", nullable = false, length = 20)
	private StatutDocument statut;

	@Column(name = "message_erreur", columnDefinition = "text")
	private String messageErreur;

	@CreationTimestamp
	@Column(name = "date_reception", nullable = false, updatable = false)
	private Instant dateReception;

	@UpdateTimestamp
	@Column(name = "date_mise_a_jour", nullable = false)
	private Instant dateMiseAJour;

	@OneToMany(mappedBy = "document", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<ChampExtrait> champs = new ArrayList<>();

	@Builder
	private Document(String typeDocument, String nomFichierOriginal, String contentType, long tailleOctets,
			String cleStockage, String empreinteSha256, Integer nombrePages) {
		this.typeDocument = typeDocument;
		this.nomFichierOriginal = Objects.requireNonNull(nomFichierOriginal, "nomFichierOriginal");
		this.contentType = Objects.requireNonNull(contentType, "contentType");
		this.tailleOctets = tailleOctets;
		this.cleStockage = Objects.requireNonNull(cleStockage, "cleStockage");
		this.empreinteSha256 = Objects.requireNonNull(empreinteSha256, "empreinteSha256").toLowerCase(Locale.ROOT);
		this.nombrePages = nombrePages;
		this.statut = StatutDocument.RECU;
	}

	/** Appelé uniquement par {@link Dossier#ajouterDocument} pour maintenir les deux côtés de la relation. */
	void rattacherA(Dossier dossier) {
		this.dossier = dossier;
	}

	public List<ChampExtrait> getChamps() {
		return Collections.unmodifiableList(champs);
	}

	public void ajouterChamp(ChampExtrait champ) {
		Objects.requireNonNull(champ, "champ");
		champs.add(champ);
		champ.rattacherA(this);
	}

	public void retirerChamp(ChampExtrait champ) {
		if (champs.remove(champ)) {
			champ.rattacherA(null);
		}
	}

	public void classer(String typeDocument) {
		this.typeDocument = typeDocument;
	}

	public void definirNombrePages(Integer nombrePages) {
		this.nombrePages = nombrePages;
	}

	// --- Transitions de statut ---

	/** RECU ou ERREUR → EN_COURS. Depuis ERREUR, il s'agit d'un retraitement : le message d'erreur est effacé. */
	public void demarrerTraitement() {
		verifierStatut("démarrer le traitement", StatutDocument.RECU, StatutDocument.ERREUR);
		this.statut = StatutDocument.EN_COURS;
		this.messageErreur = null;
	}

	/** EN_COURS → EXTRAIT. */
	public void marquerExtrait() {
		verifierStatut("marquer comme extrait", StatutDocument.EN_COURS);
		this.statut = StatutDocument.EXTRAIT;
	}

	/** EN_COURS → ERREUR. */
	public void marquerErreur(String message) {
		verifierStatut("marquer en erreur", StatutDocument.EN_COURS);
		this.statut = StatutDocument.ERREUR;
		this.messageErreur = message;
	}

	private void verifierStatut(String action, StatutDocument... statutsAutorises) {
		for (StatutDocument autorise : statutsAutorises) {
			if (statut == autorise) {
				return;
			}
		}
		throw new IllegalStateException("Impossible de %s le document %s : statut actuel %s, attendu %s"
				.formatted(action, id, statut, List.of(statutsAutorises)));
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || Hibernate.getClass(this) != Hibernate.getClass(o)) {
			return false;
		}
		Document autre = (Document) o;
		return id != null && id.equals(autre.getId());
	}

	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
