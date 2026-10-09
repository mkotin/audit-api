package com.melas.audit_api.service;

/** Un fichier de même contenu (même empreinte SHA-256) existe déjà dans le dossier. */
public class DocumentEnDoublonException extends RuntimeException {

	public DocumentEnDoublonException(String nomFichier) {
		super("Le fichier \"%s\" est déjà présent dans ce dossier (même contenu)".formatted(nomFichier));
	}

}
