package com.melas.audit_api.web.dto;

import java.time.Instant;

import com.melas.audit_api.domain.Consolidation;

/** Une version de la consolidation d'un dossier, sans son JSON complet (voir GET …/consolidations/{numero}). */
public record ConsolidationVersionResponse(
		int numero,
		Instant dateCreation,
		String versionConsolidation,
		String empreinteConsolidation,
		String versionDomaine,
		String empreinteDomaine,
		String empreinteResultat,
		int nombrePieces,
		int nombreNonExploitables,
		int nombreLiens,
		int nombreContradictions,
		int dureeMs,
		String correlationId) {

	public static ConsolidationVersionResponse depuis(Consolidation version) {
		return new ConsolidationVersionResponse(version.getNumero(), version.getDateCreation(),
				version.getVersionConsolidation(), version.getEmpreinteConsolidation(), version.getVersionDomaine(),
				version.getEmpreinteDomaine(), version.getEmpreinteResultat(), version.getNombrePieces(),
				version.getNombreNonExploitables(), version.getNombreLiens(), version.getNombreContradictions(),
				version.getDureeMs(), version.getCorrelationId());
	}

}
