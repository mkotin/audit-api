package com.melas.audit_api.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Réponse de POST /v1/ocr.
 *
 * @param typeFichier type détecté à partir du contenu : pdf, jpeg, png, tiff
 * @param methodeGlobale natif, ocr, mixte (pages natives et OCR) ou stub
 */
public record ReponseOcr(
		UUID documentId,
		String typeFichier,
		int nombrePages,
		String methodeGlobale,
		List<PageOcr> pages,
		int dureeTelechargementMs,
		int dureeExtractionMs,
		String versionService) {

	/**
	 * Page d'un résultat d'OCR.
	 *
	 * @param unite "pt" (points PDF) ou "px" (pixels d'image)
	 * @param methode natif, ocr ou stub (OCR non abouti dans les délais, page sans bloc)
	 * @param moteur moteur et sa version, ex. "tesseract 5.5.0" ; null si aucun
	 */
	public record PageOcr(int numero, double largeur, double hauteur, String unite, String methode, String moteur,
			List<BlocOcr> blocs) {
	}

	/** Bloc de texte, d'identifiant stable "p{page}-b{index}". */
	public record BlocOcr(String id, String texte, BoiteEnglobanteCore bbox, double confiance) {
	}

}
