package com.melas.audit_api.stockage;

/** Le stockage ne répond pas (connexion impossible, erreur serveur) : l'opération peut être retentée plus tard. */
public class StockageIndisponibleException extends StockageException {

	public StockageIndisponibleException(String message, Throwable cause) {
		super(message, cause);
	}

}
