package com.melas.audit_api.stockage;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Configuration du stockage objet compatible S3.
 *
 * @param endpoint URL de l'API S3 utilisée par l'application (stockage local ou service S3 hébergé)
 * @param endpointPublic URL du stockage placée dans les URL signées, telle que la joignent leurs destinataires
 *        (audit-core, souvent dans un conteneur) ; facultative, {@code endpoint} à défaut. La signature couvre
 *        l'hôte : l'URL ne peut pas être réécrite après coup.
 * @param pathStyle adressage {@code endpoint/bucket/cle} plutôt que {@code bucket.endpoint/cle} (requis hors AWS)
 * @param creerBucketAuDemarrage crée le bucket au démarrage s'il n'existe pas
 * @param dureeUrlTemporaire durée de validité des URL signées
 */
@Validated
@ConfigurationProperties("app.stockage")
public record StockageProperties(
		@NotNull URI endpoint,
		URI endpointPublic,
		@NotBlank String region,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		@NotBlank String bucket,
		boolean pathStyle,
		boolean creerBucketAuDemarrage,
		@NotNull Duration dureeUrlTemporaire) {

	/** Adresse utilisée par le S3Presigner : {@code endpointPublic} si défini, sinon {@code endpoint}. */
	public URI endpointPresigner() {
		return endpointPublic != null ? endpointPublic : endpoint;
	}

	@Override
	public String toString() {
		return "StockageProperties[endpoint=%s, endpointPublic=%s, region=%s, bucket=%s, pathStyle=%s]"
				.formatted(endpoint, endpointPublic, region, bucket, pathStyle);
	}

}
