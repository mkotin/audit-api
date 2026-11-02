package com.melas.audit_api.core;

/**
 * Autre erreur d'audit-core : requête refusée (400), stockage injoignable depuis audit-core, erreur interne, réponse
 * illisible.
 */
public class AuditCoreErreurException extends AuditCoreException {

	public AuditCoreErreurException(String titre, String detail, Integer statut) {
		super(titre, detail, statut, null);
	}

	public AuditCoreErreurException(String titre, String detail, Throwable cause) {
		super(titre, detail, null, cause);
	}

}
