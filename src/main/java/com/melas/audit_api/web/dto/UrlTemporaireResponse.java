package com.melas.audit_api.web.dto;

import java.time.Instant;

import com.melas.audit_api.stockage.UrlTemporaire;

/** URL signée d'un fichier et son instant d'expiration. */
public record UrlTemporaireResponse(String url, Instant expireA) {

	public static UrlTemporaireResponse depuis(UrlTemporaire urlTemporaire) {
		return new UrlTemporaireResponse(urlTemporaire.url().toString(), urlTemporaire.expireA());
	}

}
