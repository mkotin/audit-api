package com.melas.audit_api.core.dto;

import java.util.UUID;

/** POST /v1/ocr : document désigné par une URL signée du stockage, dont l'hôte est autorisé par audit-core. */
public record RequeteOcr(String url, UUID documentId) {
}
