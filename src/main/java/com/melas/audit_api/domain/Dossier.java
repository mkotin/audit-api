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
	private String domaine; // champ legacy, plus utile aujourd'hui.

	@CreationTimestamp
	@Column(name = "date_creation", nullable = false, updatable = false)
	private Instant dateCreation;

	@UpdateTimestamp
	@Column(name = "date_mise_a_jour", nullable = false)
	private Instant dateMiseAJour;

	@OneToMany(mappedBy = "dossier", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<Document> documents = new ArrayList<>();

	// --- Suivi de la consolidation (V5). Demandes et nouvelles tentatives automatiques : étape 7d. ---

	/** Incrémenté dans la transaction de chaque changement du dossier (7d) ; ne redescend jamais. */
	@Column(name = "consolidation_demande_seq", nullable = false)
	private long consolidationDemandeSeq;

	/** Dernier compteur traité : une demande est en attente si {@code consolidationDemandeSeq} est plus grand. */
	@Column(name = "consolidation_seq_traitee", nullable = false)
	private long consolidationSeqTraitee;

	/** Dernier échec (titre sans détail technique), effacé au succès suivant. */
	@Column(name = "consolidation_erreur", columnDefinition = "text")
	private String consolidationErreur;

	@Column(name = "consolidation_erreur_le")
	private Instant consolidationErreurLe;

	@Column(name = "consolidation_prochaine_tentative_le")
	private Instant consolidationProchaineTentativeLe;

	@Column(name = "consolidation_tentatives", nullable = false)
	private int consolidationTentatives;

	@Builder
	private Dossier(String referenceExterne, String domaine) {
		this.referenceExterne = Objects.requireNonNull(referenceExterne, "referenceExterne");
		this.domaine = Objects.requireNonNull(domaine, "domaine");
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

	/**
	 * Consolidation réussie (nouvelle version ou résultat inchangé) à partir du compteur {@code seqLue} : la demande
	 * correspondante est traitée, et la dernière erreur effacée. Le compteur traité ne redescend jamais.
	 */
	public void consolidationReussie(long seqLue) {
		this.consolidationSeqTraitee = Math.max(consolidationSeqTraitee, seqLue);
		this.consolidationErreur = null;
		this.consolidationErreurLe = null;
		this.consolidationTentatives = 0;
		this.consolidationProchaineTentativeLe = null;
	}

	/** Consolidation en échec : le message (sans détail technique) et sa date sont conservés jusqu'au succès suivant. */
	public void consolidationEchouee(String message, Instant le) {
		this.consolidationErreur = Objects.requireNonNull(message, "message");
		this.consolidationErreurLe = Objects.requireNonNull(le, "le");
	}

	/**
	 * Statut déduit, par ordre de priorité : en erreur, en attente, à jour (une version existe), aucune.
	 *
	 * @param aUneVersion le dossier a au moins une version de consolidation
	 */
	public StatutConsolidation statutConsolidation(boolean aUneVersion) {
		if (consolidationErreur != null) {
			return StatutConsolidation.EN_ERREUR;
		}
		if (consolidationDemandeSeq > consolidationSeqTraitee) {
			return StatutConsolidation.EN_ATTENTE;
		}
		return aUneVersion ? StatutConsolidation.A_JOUR : StatutConsolidation.AUCUNE;
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
