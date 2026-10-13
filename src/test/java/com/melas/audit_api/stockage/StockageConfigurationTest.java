package com.melas.audit_api.stockage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Adresse du stockage : endpoint-public pour les URL signées seulement, endpoint pour tout le reste. */
class StockageConfigurationTest {

	private static final URI ENDPOINT = URI.create("http://localhost:9000");
	private static final URI ENDPOINT_PUBLIC = URI.create("http://host.docker.internal:9000");

	private final StockageConfiguration configuration = new StockageConfiguration();

	private static StockageProperties proprietes(URI endpointPublic) {
		return new StockageProperties(ENDPOINT, endpointPublic, "us-east-1", "cle", "secret", "documents", true,
				false, Duration.ofMinutes(15));
	}

	/** La signature est calculée localement : aucun stockage n'est contacté. */
	private UrlTemporaire signer(StockageProperties proprietes) {
		try (S3Presigner presigner = configuration.s3Presigner(proprietes)) {
			return new S3StockageFichiers(null, presigner, proprietes).genererUrlTemporaire("dossiers/d/f.pdf");
		}
	}

	@Test
	void lePresignerUtiliseEndpointPublicQuandIlEstDefini() {
		URL url = signer(proprietes(ENDPOINT_PUBLIC)).url();

		assertThat(url.getHost()).isEqualTo("host.docker.internal");
		assertThat(url.getPort()).isEqualTo(9000);
		assertThat(url.getPath()).isEqualTo("/documents/dossiers/d/f.pdf");
		assertThat(url.getQuery()).contains("X-Amz-Signature");
	}

	@Test
	void lePresignerUtiliseEndpointQuandEndpointPublicNEstPasDefini() {
		URL url = signer(proprietes(null)).url();

		assertThat(url.getHost()).isEqualTo("localhost");
		assertThat(url.getPort()).isEqualTo(9000);
	}

	@Test
	void leClientS3ContinueDUtiliserEndpoint() {
		try (S3Client s3 = configuration.s3Client(proprietes(ENDPOINT_PUBLIC))) {
			assertThat(s3.serviceClientConfiguration().endpointOverride()).contains(ENDPOINT);
		}
	}

	@Test
	void lExpirationCorrespondALaDureeConfiguree() {
		Instant avant = Instant.now();

		UrlTemporaire urlTemporaire = signer(proprietes(null));

		// Tolérance d'une seconde : l'instant de signature (X-Amz-Date) est tronqué à la seconde.
		assertThat(urlTemporaire.expireA()).isBetween(avant.plus(Duration.ofMinutes(15)).minusSeconds(1),
				Instant.now().plus(Duration.ofMinutes(15)).plusSeconds(1));
		assertThat(urlTemporaire.url().getQuery()).contains("X-Amz-Expires=900");
	}

}
