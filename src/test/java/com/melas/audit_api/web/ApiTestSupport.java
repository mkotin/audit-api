package com.melas.audit_api.web;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.TestcontainersConfiguration;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.service.CleStockage;
import com.melas.audit_api.stockage.StockageProperties;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Base des tests de l'API : vrai serveur HTTP (les limites de taille multipart ne s'appliquent qu'avec Tomcat),
 * PostgreSQL et stockage S3 dans des conteneurs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
public abstract class ApiTestSupport {

	@LocalServerPort
	private int port;

	@Autowired
	protected S3Client s3;

	@Autowired
	protected StockageProperties proprietesStockage;

	@Autowired
	private DocumentRepository documents;

	@Autowired
	private TransactionTemplate transaction;

	protected RestClient client;

	/** Fichier à déposer : nom et Content-Type sont ceux annoncés par le client, pas forcément exacts. */
	public record Fichier(String nom, String contentType, byte[] contenu) {
	}

	@BeforeEach
	protected void initialiserClient() {
		client = RestClient.builder()
				.baseUrl("http://localhost:" + port)
				// Les tests inspectent eux-mêmes les statuts d'erreur.
				.defaultStatusHandler(statut -> true, (requete, reponse) -> {
				})
				.build();
	}

	protected static String referenceUnique(String prefixe) {
		return prefixe + "_" + UUID.randomUUID();
	}

	protected ResponseEntity<String> creerDossier(String reference) {
		return client.post().uri("/api/dossiers")
				.contentType(MediaType.APPLICATION_JSON)
				.body("""
						{"referenceExterne": "%s", "domaine": "assurance_habitation", "typeSinistre": "degat_des_eaux"}
						""".formatted(reference))
				.retrieve()
				.toEntity(String.class);
	}

	protected String creerDossierEtRenvoyerId() {
		return JsonPath.read(creerDossier(referenceUnique("dossier")).getBody(), "$.id");
	}

	protected ResponseEntity<String> deposer(String dossierId, Fichier... fichiers) {
		MultipartBodyBuilder corps = new MultipartBodyBuilder();
		for (Fichier fichier : fichiers) {
			corps.part("fichiers", new ByteArrayResource(fichier.contenu()))
					.filename(fichier.nom())
					.contentType(MediaType.parseMediaType(fichier.contentType()));
		}
		return client.post().uri("/api/dossiers/{id}/documents", dossierId)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(corps.build())
				.retrieve()
				.toEntity(String.class);
	}

	protected static Fichier ressource(String nomRessource, String nomAnnonce, String contentTypeAnnonce) {
		try (InputStream contenu = ApiTestSupport.class.getResourceAsStream("/fichiers/" + nomRessource)) {
			return new Fichier(nomAnnonce, contentTypeAnnonce, contenu.readAllBytes());
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	protected static Fichier pdf() {
		return ressource("minimal.pdf", "devis plombier.pdf", "application/pdf");
	}

	protected static Fichier png() {
		return ressource("image.png", "photo-degat.png", "image/png");
	}

	/** Clés présentes dans le stockage pour ce dossier. */
	protected List<String> clesStockees(String dossierId) {
		return s3.listObjectsV2(requete -> requete
						.bucket(proprietesStockage.bucket())
						.prefix(CleStockage.prefixeDossier(UUID.fromString(dossierId))))
				.contents().stream()
				.map(S3Object::key)
				.toList();
	}

	/** Modifie un document en base, dans une transaction (par exemple pour simuler le traitement OCR). */
	protected void modifierDocument(String documentId, Consumer<Document> modification) {
		transaction.executeWithoutResult(statut ->
				modification.accept(documents.findById(UUID.fromString(documentId)).orElseThrow()));
	}

	protected HttpStatusCode supprimerDocument(String documentId) {
		return client.delete().uri("/api/documents/{id}", documentId).retrieve().toBodilessEntity().getStatusCode();
	}

}
