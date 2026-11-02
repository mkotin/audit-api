package com.melas.audit_api.core;

/** Aucun LLM n'est configuré dans audit-core (503 « LLM non configuré ») : classification et extraction impossibles. */
public class LlmNonConfigureException extends AuditCoreException {

	public LlmNonConfigureException(String titre, String detail, Integer statut) {
		super(titre, detail, statut, null);
	}

	public LlmNonConfigureException(String titre, String detail, Throwable cause) {
		super(titre, detail, null, cause);
	}

}
