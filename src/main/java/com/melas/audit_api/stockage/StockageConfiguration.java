package com.melas.audit_api.stockage;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StockageProperties.class)
class StockageConfiguration {

	@Bean(destroyMethod = "close")
	S3Client s3Client(StockageProperties proprietes) {
		return S3Client.builder()
				.endpointOverride(proprietes.endpoint())
				.region(Region.of(proprietes.region()))
				.credentialsProvider(identifiants(proprietes))
				.forcePathStyle(proprietes.pathStyle())
				// Sommes de contrôle uniquement quand l'opération les exige : les stockages compatibles S3
				// ne gèrent pas tous les sommes de contrôle étendues activées par défaut dans le SDK.
				.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
				.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
				.build();
	}

	@Bean(destroyMethod = "close")
	S3Presigner s3Presigner(StockageProperties proprietes) {
		return S3Presigner.builder()
				.endpointOverride(proprietes.endpoint())
				.region(Region.of(proprietes.region()))
				.credentialsProvider(identifiants(proprietes))
				.serviceConfiguration(S3Configuration.builder()
						.pathStyleAccessEnabled(proprietes.pathStyle())
						.build())
				.build();
	}

	private static StaticCredentialsProvider identifiants(StockageProperties proprietes) {
		return StaticCredentialsProvider.create(
				AwsBasicCredentials.create(proprietes.accessKey(), proprietes.secretKey()));
	}

}
