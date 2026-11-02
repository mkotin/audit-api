package com.melas.audit_api.core.dto;

/** Réponse d'erreur d'audit-core (application/problem+json, RFC 9457). */
public record ProblemeCore(String type, String title, Integer status, String detail, String instance) {
}
