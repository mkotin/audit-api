package com.melas.audit_api.core;

/**
 * Échec d'un appel à audit-core. Le titre est court et sans détail technique (il peut être montré à l'utilisateur) ;
 * le détail, repris de la réponse problem+json d'audit-core quand il y en a une, est destiné aux logs.
 */
public abstract class AuditCoreException extends RuntimeException {

	private final String titre;
	private final String detail;
	private final Integer statut;

	protected AuditCoreException(String titre, String detail, Integer statut, Throwable cause) {
		super(detail == null ? titre : titre + " : " + detail, cause);
		this.titre = titre;
		this.detail = detail;
		this.statut = statut;
	}

	public String getTitre() {
		return titre;
	}

	public String getDetail() {
		return detail;
	}

	/** Statut HTTP de la réponse d'audit-core ; null si aucune réponse n'a été reçue. */
	public Integer getStatut() {
		return statut;
	}

}
