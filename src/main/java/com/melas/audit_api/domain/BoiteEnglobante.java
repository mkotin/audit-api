package com.melas.audit_api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Zone d'un champ sur la page, en coordonnées normalisées 0-1
 * (origine en haut à gauche de la page).
 */
@Embeddable
@Getter
@EqualsAndHashCode
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BoiteEnglobante {

	@Column(name = "zone_x")
	private Double x;

	@Column(name = "zone_y")
	private Double y;

	@Column(name = "zone_largeur")
	private Double largeur;

	@Column(name = "zone_hauteur")
	private Double hauteur;

	public BoiteEnglobante(double x, double y, double largeur, double hauteur) {
		this.x = verifierNormalise("x", x);
		this.y = verifierNormalise("y", y);
		this.largeur = verifierNormalise("largeur", largeur);
		this.hauteur = verifierNormalise("hauteur", hauteur);
	}

	private static double verifierNormalise(String nom, double valeur) {
		if (valeur < 0 || valeur > 1) {
			throw new IllegalArgumentException(nom + " doit être compris entre 0 et 1 : " + valeur);
		}
		return valeur;
	}

}
