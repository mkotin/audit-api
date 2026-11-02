package com.melas.audit_api.core;

/**
 * Le document ne peut pas être lu par audit-core : fichier trop volumineux, type non supporté, PDF protégé, illisible
 * ou inaccessible (413, 415, 422).
 */
public class DocumentIllisibleException extends AuditCoreException {

	public DocumentIllisibleException(String titre, String detail, Integer statut) {
		super(titre, detail, statut, null);
	}

	public DocumentIllisibleException(String titre, String detail, Throwable cause) {
		super(titre, detail, null, cause);
	}

}
