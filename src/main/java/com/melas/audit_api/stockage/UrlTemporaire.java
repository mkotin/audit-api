package com.melas.audit_api.stockage;

import java.net.URL;
import java.time.Instant;

/**
 * URL signée donnant un accès en lecture au fichier sans identifiants, jusqu'à {@code expireA}.
 *
 * @param url URL signée, dont l'hôte est celui de {@code app.stockage.endpoint-public} (à défaut {@code endpoint})
 * @param expireA instant d'expiration de la signature
 */
public record UrlTemporaire(URL url, Instant expireA) {
}
