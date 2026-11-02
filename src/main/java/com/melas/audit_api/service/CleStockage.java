package com.melas.audit_api.service;

import java.util.UUID;

/**
 * Convention de nommage des fichiers dans le stockage :
 * <ul>
 * <li>fichier déposé : {@code dossiers/{dossierId}/{documentId}.{extension}} ;</li>
 * <li>résultats des traitements : {@code dossiers/{dossierId}/{documentId}/ocr/{resultatOcrId}.json} et
 * {@code dossiers/{dossierId}/{documentId}/extraction/{extractionId}.json}.</li>
 * </ul>
 * Le nom d'origine n'y figure jamais (caractères arbitraires, données potentiellement personnelles).
 */
public final class CleStockage {

	private CleStockage() {
	}

	public static String pour(UUID dossierId, UUID documentId, TypeFichier type) {
		return "dossiers/%s/%s.%s".formatted(dossierId, documentId, type.extension());
	}

	/** Réponse complète d'un passage d'OCR (JSON d'audit-core). */
	public static String ocr(UUID dossierId, UUID documentId, UUID resultatOcrId) {
		return "%s%s.json".formatted(prefixeDocument(dossierId, documentId) + "ocr/", resultatOcrId);
	}

	/** Réponse complète d'un passage d'extraction (JSON d'audit-core). */
	public static String extraction(UUID dossierId, UUID documentId, UUID extractionId) {
		return "%s%s.json".formatted(prefixeDocument(dossierId, documentId) + "extraction/", extractionId);
	}

	/** Préfixe des résultats de traitement d'un document (le fichier déposé n'en fait pas partie). */
	public static String prefixeDocument(UUID dossierId, UUID documentId) {
		return "dossiers/%s/%s/".formatted(dossierId, documentId);
	}

	/** Préfixe commun à tous les fichiers d'un dossier. */
	public static String prefixeDossier(UUID dossierId) {
		return "dossiers/%s/".formatted(dossierId);
	}

}
