package com.melas.audit_api.traitement;

/** Le document existe, mais n'a pas encore le résultat demandé (OCR, extraction) (404). */
public class ResultatIntrouvableException extends RuntimeException {

	public ResultatIntrouvableException(String message) {
		super(message);
	}

}
