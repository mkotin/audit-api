package com.melas.audit_api.stockage;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Stockage dans un bucket S3 (AWS ou tout stockage compatible). */
@Component
class S3StockageFichiers implements StockageFichiers {

	/** Seul préfixe accepté pour une suppression groupée : le dossier d'un seul dossier métier. */
	private static final Pattern PREFIXE_DOSSIER =
			Pattern.compile("dossiers/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/");

	/** Maximum imposé par l'API S3 DeleteObjects (et taille de page du listing). */
	static final int TAILLE_LOT_MAX = 1000;

	private final S3Client s3;
	private final S3Presigner presigner;
	private final StockageProperties proprietes;
	private final int tailleLot;

	@Autowired
	S3StockageFichiers(S3Client s3, S3Presigner presigner, StockageProperties proprietes) {
		this(s3, presigner, proprietes, TAILLE_LOT_MAX);
	}

	/** Taille de lot réduite pour tester pagination et découpage sans créer des milliers d'objets. */
	S3StockageFichiers(S3Client s3, S3Presigner presigner, StockageProperties proprietes, int tailleLot) {
		if (tailleLot < 1 || tailleLot > TAILLE_LOT_MAX) {
			throw new IllegalArgumentException("Taille de lot hors de [1, %d] : %d".formatted(TAILLE_LOT_MAX, tailleLot));
		}
		this.s3 = s3;
		this.presigner = presigner;
		this.proprietes = proprietes;
		this.tailleLot = tailleLot;
	}

	@Override
	public void enregistrer(String cle, InputStream contenu, long taille, String contentType) {
		executer("enregistrer", cle, () -> s3.putObject(
				requete -> requete.bucket(proprietes.bucket()).key(cle).contentType(contentType).contentLength(taille),
				RequestBody.fromInputStream(contenu, taille)));
	}

	@Override
	public InputStream lire(String cle) {
		return executer("lire", cle, () -> s3.getObject(requete -> requete.bucket(proprietes.bucket()).key(cle)));
	}

	@Override
	public void supprimer(String cle) {
		executer("supprimer", cle, () -> s3.deleteObject(requete -> requete.bucket(proprietes.bucket()).key(cle)));
	}

	@Override
	public int supprimerParPrefixe(String prefixe) {
		if (prefixe == null || !PREFIXE_DOSSIER.matcher(prefixe).matches()) {
			throw new IllegalArgumentException(
					"Préfixe refusé pour une suppression groupée : \"%s\" (attendu : dossiers/{uuid}/)".formatted(prefixe));
		}
		return executer("supprimer par préfixe", prefixe, () -> {
			int supprimes = 0;
			List<ObjectIdentifier> lot = new ArrayList<>(tailleLot);
			// Le paginateur enchaîne les pages du listing (jetons de continuation) au fil de l'itération.
			for (S3Object objet : s3.listObjectsV2Paginator(requete -> requete
					.bucket(proprietes.bucket()).prefix(prefixe).maxKeys(tailleLot)).contents()) {
				lot.add(ObjectIdentifier.builder().key(objet.key()).build());
				if (lot.size() == tailleLot) {
					supprimes += supprimerLot(lot);
					lot.clear();
				}
			}
			if (!lot.isEmpty()) {
				supprimes += supprimerLot(lot);
			}
			return supprimes;
		});
	}

	private int supprimerLot(List<ObjectIdentifier> lot) {
		DeleteObjectsResponse reponse = s3.deleteObjects(requete -> requete
				.bucket(proprietes.bucket())
				.delete(suppression -> suppression.objects(lot).quiet(true)));
		if (reponse.hasErrors() && !reponse.errors().isEmpty()) {
			throw new StockageException("Suppression incomplète, %d fichier(s) en échec, dont %s (%s)".formatted(
					reponse.errors().size(), reponse.errors().getFirst().key(), reponse.errors().getFirst().message()),
					null);
		}
		return lot.size();
	}

	@Override
	public URL genererUrlTemporaire(String cle) {
		return presigner.presignGetObject(signature -> signature
				.signatureDuration(proprietes.dureeUrlTemporaire())
				.getObjectRequest(requete -> requete.bucket(proprietes.bucket()).key(cle)))
				.url();
	}

	private <T> T executer(String operation, String cle, Supplier<T> appel) {
		try {
			return appel.get();
		}
		catch (NoSuchKeyException e) {
			throw new FichierIntrouvableException(cle, e);
		}
		catch (S3Exception e) {
			String message = "Échec de l'opération '%s' sur %s (HTTP %d)".formatted(operation, cle, e.statusCode());
			if (e.statusCode() >= 500) {
				throw new StockageIndisponibleException(message, e);
			}
			throw new StockageException(message, e);
		}
		catch (SdkClientException e) {
			throw new StockageIndisponibleException(
					"Stockage injoignable pendant l'opération '%s' sur %s".formatted(operation, cle), e);
		}
	}

}
