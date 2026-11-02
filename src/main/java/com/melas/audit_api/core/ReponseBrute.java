package com.melas.audit_api.core;

/**
 * Réponse d'audit-core, lue ({@code valeur}) et telle qu'elle a été reçue ({@code json}) : le JSON brut est conservé
 * dans le stockage et renvoyé à audit-core sans perte, même si audit-core ajoute des champs inconnus d'audit-api.
 */
public record ReponseBrute<T>(T valeur, byte[] json) {
}
