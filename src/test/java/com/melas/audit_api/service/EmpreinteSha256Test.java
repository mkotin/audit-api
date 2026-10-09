package com.melas.audit_api.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.Test;

class EmpreinteSha256Test {

	@Test
	void vecteursDeReference() throws IOException {
		assertThat(EmpreinteSha256.calculer(InputStream.nullInputStream()))
				.isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
		assertThat(EmpreinteSha256.calculer(new ByteArrayInputStream("abc".getBytes(StandardCharsets.US_ASCII))))
				.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	@Test
	void calculeLEmpreintePendantQuUnAutreConsommateurLitLeFlux() throws IOException, NoSuchAlgorithmException {
		byte[] contenu = new byte[3 * 1024 * 1024 + 17];
		new Random(42).nextBytes(contenu);
		String attendu = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenu));

		EmpreinteSha256 empreinte = new EmpreinteSha256();
		// Le consommateur (ici, une copie par blocs) lit le flux ; l'empreinte se calcule au passage.
		try (InputStream flux = empreinte.envelopper(new ByteArrayInputStream(contenu))) {
			flux.transferTo(OutputStream.nullOutputStream());
		}

		assertThat(empreinte.valeurHex()).isEqualTo(attendu).hasSize(64).matches("[0-9a-f]+");
	}

	@Test
	void leFluxEnveloppeRefuseMarkReset() {
		InputStream flux = new EmpreinteSha256().envelopper(new ByteArrayInputStream(new byte[10]));
		assertThat(flux.markSupported()).isFalse();
	}

}
