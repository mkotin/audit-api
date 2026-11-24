package com.melas.audit_api.consolidation;

import java.util.UUID;

/** Une consolidation du dossier est déjà en cours (poller ou administration) : pas de second calcul simultané. */
public class ConsolidationEnCoursException extends RuntimeException {

	public ConsolidationEnCoursException(UUID dossierId) {
		super("Consolidation en cours pour le dossier " + dossierId + " : réessayer quand elle sera terminée");
	}

}
