package com.melas.audit_api.core;

/**
 * Identifiant de corrélation d'un traitement : placé dans le MDC (clé {@link #CLE_MDC}, reprise par
 * {@code logging.pattern.correlation}) et transmis à audit-core dans l'en-tête {@link #ENTETE}, qu'audit-core reprend
 * dans ses propres logs.
 */
public final class Correlation {

	public static final String CLE_MDC = "correlationId";
	public static final String ENTETE = "X-Request-ID";

	private Correlation() {
	}

}
