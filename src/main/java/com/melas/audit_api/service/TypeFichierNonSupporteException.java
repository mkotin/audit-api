package com.melas.audit_api.service;

/** Le contenu du fichier ne correspond à aucun type accepté. */
public class TypeFichierNonSupporteException extends RuntimeException {

	public TypeFichierNonSupporteException(String nomFichier) {
		super("Type de fichier non supporté pour \"%s\" : seuls PDF, JPEG, PNG et TIFF sont acceptés".formatted(nomFichier));
	}

}
