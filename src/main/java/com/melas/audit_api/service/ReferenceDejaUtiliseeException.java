package com.melas.audit_api.service;

/** Un dossier porte déjà cette référence externe. */
public class ReferenceDejaUtiliseeException extends RuntimeException {

	public ReferenceDejaUtiliseeException(String reference) {
		super("Un dossier existe déjà avec la référence " + reference);
	}

}
