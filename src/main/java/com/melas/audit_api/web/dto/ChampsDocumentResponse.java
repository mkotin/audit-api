package com.melas.audit_api.web.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.melas.audit_api.domain.Alerte;
import com.melas.audit_api.domain.BoiteEnglobante;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.traitement.ChampsExtraits;

/**
 * Champs de la dernière extraction d'un document.
 *
 * @param confiance confiance globale du document : la plus basse des champs obligatoires présents (0 sinon)
 * @param alertes alertes du document (champs obligatoires absents, contrôles de cohérence, classification peu fiable)
 * @param extraitPar modèle et version de la consigne d'extraction
 * @param champs dans l'ordre des champs du type de document
 */
public record ChampsDocumentResponse(
		UUID documentId,
		UUID extractionId,
		String typeDocument,
		BigDecimal confiance,
		List<Alerte> alertes,
		String extraitPar,
		Instant dateExtraction,
		List<ChampResponse> champs) {

	/**
	 * Un champ extrait.
	 *
	 * @param nom chemin du champ : "total_ttc", ou "lignes[2].montant_ht" pour une liste (indices à partir de 0)
	 * @param valeurBrute valeur telle qu'elle est écrite dans le document
	 * @param valeurNormalisee valeur normalisée ("316.80", "2026-03-16", "true") ; null si non normalisable
	 * @param page numéro de page, à partir de 1
	 * @param zone zone de la valeur sur la page, coordonnées normalisées entre 0 et 1
	 */
	public record ChampResponse(
			String nom,
			String valeurBrute,
			String valeurNormalisee,
			BigDecimal confiance,
			Integer page,
			ZoneResponse zone,
			List<Alerte> alertes) {

		static ChampResponse depuis(ChampExtrait champ) {
			return new ChampResponse(champ.getNom(), champ.getValeurBrute(), champ.getValeurNormalisee(),
					champ.getConfiance(), champ.getPage(), ZoneResponse.depuis(champ.getZone()), champ.getAlertes());
		}

	}

	/** Zone normalisée entre 0 et 1, origine en haut à gauche de la page. */
	public record ZoneResponse(double x, double y, double largeur, double hauteur) {

		static ZoneResponse depuis(BoiteEnglobante zone) {
			return zone == null ? null : new ZoneResponse(zone.getX(), zone.getY(), zone.getLargeur(), zone.getHauteur());
		}

	}

	public static ChampsDocumentResponse depuis(UUID documentId, ChampsExtraits resultat) {
		Extraction extraction = resultat.extraction();
		return new ChampsDocumentResponse(
				documentId,
				extraction.getId(),
				extraction.getTypeDocument(),
				extraction.getConfiance(),
				extraction.getAlertes(),
				extraction.extracteur(),
				extraction.getDateCreation(),
				resultat.champs().stream().map(ChampResponse::depuis).toList());
	}

}
