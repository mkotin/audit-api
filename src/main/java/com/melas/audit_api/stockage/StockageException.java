package com.melas.audit_api.stockage;

/** Erreur du stockage de fichiers. */
public class StockageException extends RuntimeException {

	public StockageException(String message, Throwable cause) {
		super(message, cause);
	}

}
