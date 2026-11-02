package com.melas.audit_api.core.dto;

/** Zone normalisée entre 0 et 1 par rapport à la page, origine en haut à gauche. */
public record BoiteEnglobanteCore(double x, double y, double largeur, double hauteur) {
}
