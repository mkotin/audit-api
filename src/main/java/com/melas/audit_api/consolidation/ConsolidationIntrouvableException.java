package com.melas.audit_api.consolidation;

/** Le dossier existe, mais n'a pas la version de consolidation demandée (aucune, ou numéro inconnu) (404). */
public class ConsolidationIntrouvableException extends RuntimeException {

	public ConsolidationIntrouvableException(String message) {
		super(message);
	}

}
