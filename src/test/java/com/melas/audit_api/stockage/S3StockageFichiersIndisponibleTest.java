package com.melas.audit_api.stockage;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Un stockage injoignable se traduit par StockageIndisponibleException (réponse 503 côté API). */
class S3StockageFichiersIndisponibleTest {

	/** Port réservé, sur lequel rien n'écoute : la connexion est refusée immédiatement. */
	private static final URI ENDPOINT_FERME = URI.create("http://localhost:1");

	private final S3Client s3 = S3Client.builder()
			.endpointOverride(ENDPOINT_FERME)
			.region(Region.US_EAST_1)
			.credentialsProvider(AnonymousCredentialsProvider.create())
			.forcePathStyle(true)
			.overrideConfiguration(configuration -> configuration.retryStrategy(strategie -> strategie.maxAttempts(1)))
			.build();

	private final S3StockageFichiers stockage = new S3StockageFichiers(s3,
			S3Presigner.builder().region(Region.US_EAST_1).build(),
			new StockageProperties(ENDPOINT_FERME, null, "us-east-1", "a", "s", "documents", true, false,
					Duration.ofMinutes(1)));

	@AfterEach
	void fermer() {
		s3.close();
	}

	@Test
	void chaqueOperationSignaleLIndisponibilite() {
		assertThatThrownBy(() -> stockage.enregistrer("cle", new ByteArrayInputStream(new byte[3]), 3, "application/pdf"))
				.isInstanceOf(StockageIndisponibleException.class);
		assertThatThrownBy(() -> stockage.lire("cle")).isInstanceOf(StockageIndisponibleException.class);
		assertThatThrownBy(() -> stockage.supprimer("cle")).isInstanceOf(StockageIndisponibleException.class);
	}

}
