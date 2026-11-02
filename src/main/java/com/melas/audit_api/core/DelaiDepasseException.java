package com.melas.audit_api.core;

/**
 * Délai dépassé : audit-core a répondu 504 (téléchargement, OCR ou LLM trop long), ou n'a pas répondu dans le délai de
 * lecture configuré (app.core.delai-lecture).
 */
public class DelaiDepasseException extends AuditCoreException {

	public DelaiDepasseException(String titre, String detail, Integer statut) {
		super(titre, detail, statut, null);
	}

	public DelaiDepasseException(String titre, String detail, Throwable cause) {
		super(titre, detail, null, cause);
	}

}
