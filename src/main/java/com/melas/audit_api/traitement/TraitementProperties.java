package com.melas.audit_api.traitement;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

/**
 * Traitement des documents par audit-core.
 *
 * @param automatique traitement lancé après chaque dépôt, et reprise au démarrage ; désactivé, seules les relances
 *        explicites (POST /api/documents/{id}/traitement) traitent les documents
 * @param parallelisme documents traités simultanément
 * @param tailleFile documents en attente au-delà desquels une demande est refusée
 * @param seuilClassification confiance de classification sous laquelle l'extraction porte l'alerte
 *        {@code classification_peu_fiable} (elle a lieu quand même)
 * @param repriseAuDemarrage au démarrage, passe en ERREUR les documents restés EN_COURS et remet en file les
 *        documents RECU (sans effet si {@code automatique} est désactivé)
 */
@Validated
@ConfigurationProperties("app.traitement")
public record TraitementProperties(
		@DefaultValue("true") boolean automatique,
		@Min(1) @DefaultValue("2") int parallelisme,
		@Min(0) @DefaultValue("100") int tailleFile,
		@DecimalMin("0") @DecimalMax("1") @DefaultValue("0.5") double seuilClassification,
		@DefaultValue("true") boolean repriseAuDemarrage) {
}
