package com.melas.audit_api.core;

/** Connexion à audit-core impossible (service arrêté, adresse erronée, connexion interrompue). */
public class AuditCoreInjoignableException extends AuditCoreException {

	public AuditCoreInjoignableException(String detail, Throwable cause) {
		super("audit-core injoignable", detail, null, cause);
	}

}
