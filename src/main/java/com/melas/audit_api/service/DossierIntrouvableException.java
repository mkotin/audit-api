package com.melas.audit_api.service;

/** Aucun dossier ne correspond à l'identifiant ou à la référence demandés. */
public class DossierIntrouvableException extends RuntimeException {

	public DossierIntrouvableException(Object identifiantOuReference) {
		super("Dossier introuvable : " + identifiantOuReference);
	}

}
