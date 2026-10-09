package com.melas.audit_api.service;

/** Requête de dépôt inexploitable (aucun fichier, fichier vide). */
public class FichierInvalideException extends RuntimeException {

	public FichierInvalideException(String message) {
		super(message);
	}

}
