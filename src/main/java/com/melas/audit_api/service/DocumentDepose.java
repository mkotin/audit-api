package com.melas.audit_api.service;

import java.util.UUID;

/**
 * Événement publié dans la transaction d'un dépôt, pour chaque document créé. Ses écouteurs transactionnels ne le
 * reçoivent qu'après la validation en base.
 */
public record DocumentDepose(UUID documentId) {
}
