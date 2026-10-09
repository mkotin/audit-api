package com.melas.audit_api.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Calcule l'empreinte SHA-256 d'un flux au fil de sa lecture, sans le charger en mémoire.
 *
 * <pre>{@code
 * EmpreinteSha256 empreinte = new EmpreinteSha256();
 * consommateur.lire(empreinte.envelopper(flux));
 * String hex = empreinte.valeurHex();
 * }</pre>
 */
public final class EmpreinteSha256 {

	private final MessageDigest digest;

	public EmpreinteSha256() {
		try {
			this.digest = MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 est garanti par la plateforme Java", e);
		}
	}

	/**
	 * Enveloppe le flux pour que chaque octet lu alimente l'empreinte. Le flux renvoyé ne supporte pas
	 * mark/reset : un consommateur qui relirait des octets fausserait l'empreinte.
	 */
	public InputStream envelopper(InputStream flux) {
		return new DigestInputStream(flux, digest) {
			@Override
			public boolean markSupported() {
				return false;
			}
		};
	}

	/** Empreinte des octets lus jusqu'ici, en hexadécimal minuscule ; à n'appeler qu'une fois. */
	public String valeurHex() {
		return HexFormat.of().formatHex(digest.digest());
	}

	/** Lit entièrement le flux (sans le fermer) et renvoie son empreinte. */
	public static String calculer(InputStream flux) throws IOException {
		EmpreinteSha256 empreinte = new EmpreinteSha256();
		empreinte.envelopper(flux).transferTo(OutputStream.nullOutputStream());
		return empreinte.valeurHex();
	}

}
