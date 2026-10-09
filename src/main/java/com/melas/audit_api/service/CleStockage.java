package com.melas.audit_api.service;

import java.util.UUID;

/**
 * Convention de nommage des fichiers dans le stockage : {@code dossiers/{dossierId}/{documentId}.{extension}}.
 * Le nom d'origine n'y figure jamais (caractères arbitraires, données potentiellement personnelles).
 */
public final class CleStockage {

	private CleStockage() {
	}

	public static String pour(UUID dossierId, UUID documentId, TypeFichier type) {
		return "dossiers/%s/%s.%s".formatted(dossierId, documentId, type.extension());
	}

	/** Préfixe commun à tous les fichiers d'un dossier. */
	public static String prefixeDossier(UUID dossierId) {
		return "dossiers/%s/".formatted(dossierId);
	}

}
