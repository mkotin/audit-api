package com.melas.audit_api.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.apache.tika.Tika;
import org.springframework.stereotype.Component;

/**
 * Détermine le type réel d'un fichier d'après sa signature (premiers octets), sans tenir compte du nom
 * ni du Content-Type annoncés par le client.
 */
@Component
public class DetecteurTypeFichier {

	/** Les signatures recherchées se trouvent toutes dans les premiers octets du fichier. */
	private static final int TAILLE_ENTETE = 8 * 1024;

	private final Tika tika = new Tika();

	/** Lit au plus l'en-tête du flux (sans le fermer) ; vide si le type n'est pas accepté. */
	public Optional<TypeFichier> detecter(InputStream contenu) throws IOException {
		byte[] entete = contenu.readNBytes(TAILLE_ENTETE);
		return TypeFichier.depuisMimeType(tika.detect(entete));
	}

}
