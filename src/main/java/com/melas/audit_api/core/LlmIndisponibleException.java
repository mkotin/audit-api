package com.melas.audit_api.core;

/**
 * Le fournisseur LLM d'audit-core est en échec : clé refusée, modèle introuvable, réponse non conforme (502), crédits
 * ou quota épuisés (503).
 */
public class LlmIndisponibleException extends AuditCoreException {

	public LlmIndisponibleException(String titre, String detail, Integer statut) {
		super(titre, detail, statut, null);
	}

	public LlmIndisponibleException(String titre, String detail, Throwable cause) {
		super(titre, detail, null, cause);
	}

}
