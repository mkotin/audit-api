package com.melas.audit_api.traitement;

import java.util.UUID;

/** Le document est déjà en cours de traitement : une nouvelle demande est refusée (409). */
public class TraitementEnCoursException extends RuntimeException {

	public TraitementEnCoursException(UUID id) {
		super("Le document %s est en cours de traitement : attendre la fin du traitement avant de le relancer"
				.formatted(id));
	}

}
