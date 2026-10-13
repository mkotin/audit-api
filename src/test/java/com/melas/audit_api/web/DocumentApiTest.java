package com.melas.audit_api.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.stockage.StockageFichiers;

class DocumentApiTest extends ApiTestSupport {

	@Autowired
	private DocumentRepository documentRepository;

	@Autowired
	private StockageFichiers stockage;

	@Test
	void deposeUnPdfPuisLeTelechargeAlIdentique() throws Exception {
		String dossierId = creerDossierEtRenvoyerId();
		Fichier pdf = pdf();

		ResponseEntity<String> depot = deposer(dossierId, pdf);

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((Integer) JsonPath.read(depot.getBody(), "$.length()")).isEqualTo(1);
		String documentId = JsonPath.read(depot.getBody(), "$[0].id");
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].dossierId")).isEqualTo(dossierId);
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].statut")).isEqualTo("RECU");
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].contentType")).isEqualTo("application/pdf");
		assertThat((Integer) JsonPath.read(depot.getBody(), "$[0].tailleOctets")).isEqualTo(pdf.contenu().length);
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].empreinteSha256")).isEqualTo(sha256(pdf.contenu()));
		assertThat((Object) JsonPath.read(depot.getBody(), "$[0].nombrePages")).isNull();
		assertThat(depot.getBody()).doesNotContain("cleStockage");

		// La clé de stockage ne reprend jamais le nom d'origine.
		assertThat(clesStockees(dossierId)).containsExactly("dossiers/%s/%s.pdf".formatted(dossierId, documentId));

		ResponseEntity<String> metadonnees = client.get().uri("/api/documents/{id}", documentId)
				.retrieve().toEntity(String.class);
		assertThat(metadonnees.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat((String) JsonPath.read(metadonnees.getBody(), "$.nomFichierOriginal")).isEqualTo("devis plombier.pdf");

		ResponseEntity<byte[]> telechargement = client.get().uri("/api/documents/{id}/fichier", documentId)
				.retrieve().toEntity(byte[].class);
		assertThat(telechargement.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(telechargement.getBody()).isEqualTo(pdf.contenu());
		assertThat(telechargement.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
		assertThat(telechargement.getHeaders().getContentLength()).isEqualTo(pdf.contenu().length);
		ContentDisposition disposition = telechargement.getHeaders().getContentDisposition();
		assertThat(disposition.isAttachment()).isTrue();
		assertThat(disposition.getFilename()).isEqualTo("devis plombier.pdf");
	}

	@Test
	void leTypeEstDeduitDuContenuEtNonDuNomNiDuContentTypeAnnonces() {
		String dossierId = creerDossierEtRenvoyerId();
		Fichier pngDeguiseEnPdf = ressource("image.png", "scan.pdf", "application/pdf");

		ResponseEntity<String> depot = deposer(dossierId, pngDeguiseEnPdf);

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].contentType")).isEqualTo("image/png");
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].nomFichierOriginal")).isEqualTo("scan.pdf");
		assertThat(clesStockees(dossierId)).singleElement().asString().endsWith(".png");
	}

	@Test
	void deposePlusieursFichiersDansUneRequete() {
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId, pdf(), png());

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		List<String> types = JsonPath.read(depot.getBody(), "$[*].contentType");
		assertThat(types).containsExactly("application/pdf", "image/png");
		assertThat(clesStockees(dossierId)).hasSize(2);
		assertThat(documentRepository.findAll())
				.filteredOn(document -> document.getDossier().getId().toString().equals(dossierId))
				.hasSize(2);
	}

	@Test
	void refuseUnFichierDejaPresentDansLeDossierMaisLAccepteDansUnAutre() {
		String dossierId = creerDossierEtRenvoyerId();
		assertThat(deposer(dossierId, pdf()).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		// Même contenu sous un autre nom : c'est l'empreinte qui compte.
		Fichier copie = ressource("minimal.pdf", "copie.pdf", "application/pdf");
		ResponseEntity<String> doublon = deposer(dossierId, copie);

		assertThat(doublon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(doublon.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(clesStockees(dossierId)).as("le fichier refusé a été retiré du stockage").hasSize(1);

		String autreDossierId = creerDossierEtRenvoyerId();
		assertThat(deposer(autreDossierId, copie).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void refuseUnMemeFichierRepeteDansLaRequeteSansRienCreer() {
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId, pdf(), png(), pdf());

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(clesStockees(dossierId)).isEmpty();
		assertThat(documentsDuDossier(dossierId)).isZero();
	}

	@Test
	void refuseUnFichierTexteRenommeEnPdf() {
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId,
				ressource("texte-renomme.pdf", "facture.pdf", "application/pdf"));

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat((String) JsonPath.read(depot.getBody(), "$.detail")).contains("facture.pdf");
		assertThat(clesStockees(dossierId)).isEmpty();
	}

	@Test
	void unFichierRefuseDansUnDepotMultipleAnnuleToutLeDepot() {
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId, pdf(), png(),
				ressource("texte-renomme.pdf", "facture.pdf", "application/pdf"));

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
		assertThat(clesStockees(dossierId)).isEmpty();
		assertThat(documentsDuDossier(dossierId)).isZero();
	}

	@Test
	void refuseUnFichierVide() {
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId, new Fichier("vide.pdf", "application/pdf", new byte[0]));

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(clesStockees(dossierId)).isEmpty();
	}

	@Test
	void refuseUnFichierDePlusDe20Mo() {
		String dossierId = creerDossierEtRenvoyerId();
		byte[] gros = new byte[21 * 1024 * 1024];
		System.arraycopy("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII), 0, gros, 0, 9);

		ResponseEntity<String> depot = deposer(dossierId, new Fichier("gros.pdf", "application/pdf", gros));

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
		assertThat(depot.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(clesStockees(dossierId)).isEmpty();
	}

	@Test
	void repond404PourUnDossierInexistant() {
		ResponseEntity<String> depot = deposer(UUID.randomUUID().toString(), pdf());

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(depot.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

	@Test
	void repond404PourUnDocumentInexistant() {
		UUID inconnu = UUID.randomUUID();
		assertThat(client.get().uri("/api/documents/{id}", inconnu).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(client.get().uri("/api/documents/{id}/fichier", inconnu).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void lUrlTemporairePermetDeTelechargerSansIdentifiants() throws IOException {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");
		String cle = documentRepository.findById(UUID.fromString(documentId)).orElseThrow().getCleStockage();

		URL url = stockage.genererUrlTemporaire(cle).url();

		assertThat(url.getQuery()).contains("X-Amz-Signature").contains("X-Amz-Expires=900");
		try (InputStream contenu = url.openStream()) {
			assertThat(contenu.readAllBytes()).isEqualTo(pdf().contenu());
		}
		URI sansSignature = URI.create(url.toString().substring(0, url.toString().indexOf('?')));
		assertThat(client.get().uri(sansSignature).retrieve().toEntity(String.class).getStatusCode().is4xxClientError())
				.isTrue();
	}

	private long documentsDuDossier(String dossierId) {
		return documentRepository.findAll().stream()
				.filter(document -> document.getDossier().getId().toString().equals(dossierId))
				.count();
	}

	private static String sha256(byte[] contenu) throws NoSuchAlgorithmException {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));
	}

}
