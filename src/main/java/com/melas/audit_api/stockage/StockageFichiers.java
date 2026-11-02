package com.melas.audit_api.stockage;

import java.io.InputStream;

/**
 * Stockage des fichiers déposés, hors base de données. Le reste de l'application ne dépend que de cette interface.
 *
 * <p>Les erreurs sont signalées par des {@link StockageException} :
 * {@link StockageIndisponibleException} si le stockage ne répond pas,
 * {@link FichierIntrouvableException} si la clé n'existe pas.
 */
public interface StockageFichiers {

	/** Écrit le contenu sous la clé donnée (le flux est lu jusqu'à {@code taille} octets, mais n'est pas fermé). */
	void enregistrer(String cle, InputStream contenu, long taille, String contentType);

	/** Ouvre le fichier en lecture ; l'appelant doit fermer le flux. */
	InputStream lire(String cle);

	/** Supprime le fichier ; sans effet si la clé n'existe pas. */
	void supprimer(String cle);

	/**
	 * Supprime tous les fichiers d'un dossier, ou les résultats de traitement d'un document. Par sécurité, seul un
	 * préfixe de la forme exacte {@code dossiers/{uuid}/} ou {@code dossiers/{uuid}/{uuid}/} est accepté : jamais la
	 * racine, ni un préfixe qui couvrirait plusieurs dossiers ou documents.
	 *
	 * @return le nombre de fichiers supprimés
	 * @throws IllegalArgumentException si le préfixe ne correspond à aucun de ces motifs
	 */
	int supprimerParPrefixe(String prefixe);

	/** URL signée donnant un accès en lecture temporaire au fichier, sans identifiants. */
	UrlTemporaire genererUrlTemporaire(String cle);

}
