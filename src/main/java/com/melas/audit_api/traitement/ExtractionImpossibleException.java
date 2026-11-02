package com.melas.audit_api.traitement;

/** L'extraction seule ne peut pas être relancée : pas de résultat d'OCR, ou type de document inconnu (409). */
public class ExtractionImpossibleException extends RuntimeException {

	public ExtractionImpossibleException(String message) {
		super(message);
	}

}
