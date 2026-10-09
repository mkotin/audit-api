package com.melas.audit_api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DetecteurTypeFichierTest {

	private final DetecteurTypeFichier detecteur = new DetecteurTypeFichier();

	private Optional<TypeFichier> detecter(byte[] contenu) throws IOException {
		return detecteur.detecter(new ByteArrayInputStream(contenu));
	}

	private Optional<TypeFichier> detecterRessource(String nom) throws IOException {
		try (InputStream contenu = getClass().getResourceAsStream("/fichiers/" + nom)) {
			return detecteur.detecter(contenu);
		}
	}

	@Test
	void reconnaitUnPdf() throws IOException {
		assertThat(detecterRessource("minimal.pdf")).contains(TypeFichier.PDF);
	}

	@Test
	void reconnaitUnPng() throws IOException {
		assertThat(detecterRessource("image.png")).contains(TypeFichier.PNG);
	}

	@Test
	void reconnaitUnJpegParSaSignature() throws IOException {
		byte[] jpeg = enteteSuiviDeZeros(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00);
		assertThat(detecter(jpeg)).contains(TypeFichier.JPEG);
	}

	@Test
	void reconnaitUnTiffPetitEtGrandBoutiste() throws IOException {
		assertThat(detecter(enteteSuiviDeZeros('I', 'I', 0x2A, 0x00))).contains(TypeFichier.TIFF);
		assertThat(detecter(enteteSuiviDeZeros('M', 'M', 0x00, 0x2A))).contains(TypeFichier.TIFF);
	}

	@Test
	void refuseUnFichierTexteMemeNommeEnPdf() throws IOException {
		assertThat(detecterRessource("texte-renomme.pdf")).isEmpty();
	}

	@Test
	void refuseUnTypeNonAccepte() throws IOException {
		byte[] zip = enteteSuiviDeZeros('P', 'K', 0x03, 0x04);
		byte[] html = "<!DOCTYPE html><html><body>%PDF-1.4</body></html>".getBytes(StandardCharsets.UTF_8);
		assertThat(detecter(zip)).isEmpty();
		assertThat(detecter(html)).isEmpty();
	}

	@Test
	void neLitQueLEnTeteDuFlux() throws IOException {
		byte[] gros = new byte[100_000];
		System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, gros, 0, 9);
		ByteArrayInputStream flux = new ByteArrayInputStream(gros);

		assertThat(detecteur.detecter(flux)).contains(TypeFichier.PDF);
		assertThat(flux.available()).isEqualTo(100_000 - 8 * 1024);
	}

	private static byte[] enteteSuiviDeZeros(int... octets) {
		byte[] contenu = new byte[64];
		for (int i = 0; i < octets.length; i++) {
			contenu[i] = (byte) octets[i];
		}
		return contenu;
	}

}
