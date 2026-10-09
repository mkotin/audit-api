package com.melas.audit_api.stockage;

/** Aucun fichier n'existe sous la clé demandée. */
public class FichierIntrouvableException extends StockageException {

	public FichierIntrouvableException(String cle, Throwable cause) {
		super("Aucun fichier sous la clé " + cle, cause);
	}

}
