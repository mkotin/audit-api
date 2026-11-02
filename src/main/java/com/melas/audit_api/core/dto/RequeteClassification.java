package com.melas.audit_api.core.dto;

import java.util.UUID;

import tools.jackson.databind.JsonNode;

/** POST /v1/classification : le résultat d'OCR est renvoyé tel qu'audit-core l'a produit. */
public record RequeteClassification(JsonNode ocr, UUID documentId) {
}
