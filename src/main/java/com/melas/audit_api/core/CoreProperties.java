package com.melas.audit_api.core;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * Accès au service audit-core (OCR, classification, extraction).
 *
 * @param url adresse d'audit-core
 * @param delaiConnexion délai d'établissement de la connexion
 * @param delaiLecture délai d'attente de la réponse : l'OCR d'un scan ou un appel au LLM (avec ses nouvelles
 *        tentatives côté audit-core) peuvent durer plusieurs dizaines de secondes
 */
@Validated
@ConfigurationProperties("app.core")
public record CoreProperties(
		@NotNull @DefaultValue("http://localhost:8000") URI url,
		@NotNull @DefaultValue("5s") Duration delaiConnexion,
		@NotNull @DefaultValue("5m") Duration delaiLecture) {
}
