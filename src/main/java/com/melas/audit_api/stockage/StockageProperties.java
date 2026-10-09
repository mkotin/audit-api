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
 * @param endpoint URL de l'API S3 (stockage local ou service S3 hébergé)
 * @param pathStyle adressage {@code endpoint/bucket/cle} plutôt que {@code bucket.endpoint/cle} (requis hors AWS)
 * @param creerBucketAuDemarrage crée le bucket au démarrage s'il n'existe pas
 * @param dureeUrlTemporaire durée de validité des URL signées
 */
@Validated
@ConfigurationProperties("app.stockage")
public record StockageProperties(
		@NotNull URI endpoint,
		@NotBlank String region,
		@NotBlank String accessKey,
		@NotBlank String secretKey,
		@NotBlank String bucket,
		boolean pathStyle,
		boolean creerBucketAuDemarrage,
		@NotNull Duration dureeUrlTemporaire) {

	@Override
	public String toString() {
		return "StockageProperties[endpoint=%s, region=%s, bucket=%s, pathStyle=%s]"
				.formatted(endpoint, region, bucket, pathStyle);
	}

}
