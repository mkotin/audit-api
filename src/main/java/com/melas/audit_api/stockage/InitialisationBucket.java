package com.melas.audit_api.stockage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Crée le bucket au démarrage s'il n'existe pas. Si le stockage est injoignable, l'application démarre quand
 * même (les opérations de stockage répondront 503) : on ne bloque pas l'API pour un service annexe.
 */
@Component
@ConditionalOnBooleanProperty("app.stockage.creer-bucket-au-demarrage")
class InitialisationBucket implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(InitialisationBucket.class);

	private final S3Client s3;
	private final String bucket;

	InitialisationBucket(S3Client s3, StockageProperties proprietes) {
		this.s3 = s3;
		this.bucket = proprietes.bucket();
	}

	@Override
	public void run(ApplicationArguments arguments) {
		try {
			if (bucketExiste()) {
				log.info("Bucket de stockage '{}' présent", bucket);
				return;
			}
			s3.createBucket(requete -> requete.bucket(bucket));
			log.info("Bucket de stockage '{}' créé", bucket);
		}
		catch (SdkClientException e) {
			log.warn("Stockage injoignable au démarrage : bucket '{}' non vérifié ({})", bucket, e.getMessage());
		}
	}

	private boolean bucketExiste() {
		try {
			s3.headBucket(requete -> requete.bucket(bucket));
			return true;
		}
		catch (NoSuchBucketException e) {
			return false;
		}
		catch (S3Exception e) {
			if (e.statusCode() == 404) {
				return false;
			}
			throw e;
		}
	}

}
