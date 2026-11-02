package com.melas.audit_api.traitement;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Résumé des derniers passages de traitement d'un document ; valeurs nulles pour un passage qui n'a pas eu lieu.
 *
 * @param confianceClassification confiance de la dernière classification
 */
public record SyntheseTraitement(
		BigDecimal confianceClassification,
		Instant dateDernierOcr,
		Instant dateDerniereClassification,
		Instant dateDerniereExtraction) {

	/** Document jamais traité. */
	public static final SyntheseTraitement AUCUN = new SyntheseTraitement(null, null, null, null);

}
