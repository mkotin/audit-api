package com.melas.audit_api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Même image que le service stockage-s3 d'audit-infra (rustfs/rustfs:1.0.1), figée par digest. */
	static final String IMAGE_STOCKAGE_S3 =
			"rustfs/rustfs@sha256:1803faef57627e2d9c2e7d89d655d712ddded5389040054987163043fecb6a3c";
	private static final int PORT_S3 = 9000;
	private static final String ACCESS_KEY = "test-s3";
	private static final String SECRET_KEY = "test-s3-secret";

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

	/** Stockage compatible S3 : Spring Boot n'a pas de ServiceConnection pour S3, d'où le registrar ci-dessous. */
	@Bean
	GenericContainer<?> stockageS3Container() {
		return new GenericContainer<>(DockerImageName.parse(IMAGE_STOCKAGE_S3))
				.withEnv("RUSTFS_ACCESS_KEY", ACCESS_KEY)
				.withEnv("RUSTFS_SECRET_KEY", SECRET_KEY)
				.withExposedPorts(PORT_S3)
				.waitingFor(Wait.forHttp("/health").forPort(PORT_S3));
	}

	@Bean
	DynamicPropertyRegistrar stockageS3Properties(GenericContainer<?> stockageS3Container) {
		return registre -> {
			registre.add("app.stockage.endpoint", () -> "http://%s:%d"
					.formatted(stockageS3Container.getHost(), stockageS3Container.getMappedPort(PORT_S3)));
			registre.add("app.stockage.access-key", () -> ACCESS_KEY);
			registre.add("app.stockage.secret-key", () -> SECRET_KEY);
		};
	}

}
