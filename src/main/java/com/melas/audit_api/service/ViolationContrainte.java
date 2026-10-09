package com.melas.audit_api.service;

import java.util.Locale;

import org.springframework.dao.DataIntegrityViolationException;

/** Identifie la contrainte de base de données à l'origine d'une violation d'intégrité. */
final class ViolationContrainte {

	private ViolationContrainte() {
	}

	/** PostgreSQL cite le nom de la contrainte (ou de l'index unique) dans le message d'erreur. */
	static boolean concerne(DataIntegrityViolationException e, String nomContrainte) {
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			String message = cause.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(nomContrainte)) {
				return true;
			}
		}
		return false;
	}

}
