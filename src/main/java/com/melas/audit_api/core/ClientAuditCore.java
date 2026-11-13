package com.melas.audit_api.core;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URL;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.melas.audit_api.core.dto.DocumentExtrait;
import com.melas.audit_api.core.dto.ProblemeCore;
import com.melas.audit_api.core.dto.ReponseConsolidation;
import com.melas.audit_api.core.dto.ReponseOcr;
import com.melas.audit_api.core.dto.RequeteConsolidation;
import com.melas.audit_api.core.dto.RequeteClassification;
import com.melas.audit_api.core.dto.RequeteExtraction;
import com.melas.audit_api.core.dto.RequeteOcr;
import com.melas.audit_api.core.dto.ResultatClassification;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Appels à audit-core. audit-core est sans état : il ne lit ni n'écrit la base, et ne lit le stockage que par l'URL
 * signée qu'on lui transmet ; tout ce qu'il renvoie est enregistré par l'appelant.
 *
 * <p>Les erreurs sont traduites en {@link AuditCoreException} : {@link DocumentIllisibleException},
 * {@link LlmNonConfigureException}, {@link LlmIndisponibleException}, {@link DelaiDepasseException},
 * {@link AuditCoreInjoignableException}, ou {@link AuditCoreErreurException} pour les autres cas.
 */
@Component
public class ClientAuditCore {

	private static final Logger log = LoggerFactory.getLogger(ClientAuditCore.class);

	/** Titre du problème renvoyé par audit-core quand CORE_LLM_MODELE est vide (LlmNonConfigure). */
	static final String TITRE_LLM_NON_CONFIGURE = "LLM non configuré";

	private enum Appel {
		OCR("/v1/ocr"),
		CLASSIFICATION("/v1/classification"),
		EXTRACTION("/v1/extraction"),
		CONSOLIDATION("/v1/consolidation");

		private final String chemin;

		Appel(String chemin) {
			this.chemin = chemin;
		}
	}

	private record ReponseHttp(int statut, byte[] corps) {
	}

	private final RestClient client;
	private final RestClient clientConsolidation;
	private final JsonMapper json;

	public ClientAuditCore(@Qualifier("restClientCore") RestClient client,
			@Qualifier("restClientConsolidation") RestClient clientConsolidation) {
		this.client = client;
		this.clientConsolidation = clientConsolidation;
		this.json = CoreConfiguration.jsonMapperCore();
	}

	/**
	 * Consolide le dossier entier (POST /v1/consolidation). Les octets de la réponse sont conservés tels quels : leur
	 * empreinte décide d'une nouvelle version. Une réponse pour un autre dossier que celui demandé est refusée.
	 */
	public ReponseBrute<ReponseConsolidation> consolider(RequeteConsolidation requete) {
		byte[] corps = appeler(Appel.CONSOLIDATION, requete);
		ReponseConsolidation reponse = lire(Appel.CONSOLIDATION, corps, ReponseConsolidation.class);
		if (!requete.dossierId().equals(reponse.dossierId())) {
			throw new AuditCoreErreurException("Réponse d'audit-core pour un autre dossier",
					"POST %s : dossier %s demandé, %s reçu".formatted(Appel.CONSOLIDATION.chemin, requete.dossierId(),
							reponse.dossierId()),
					(Integer) null);
		}
		return new ReponseBrute<>(reponse, corps);
	}

	/** Extrait le texte du document désigné par une URL signée (POST /v1/ocr). */
	public ReponseBrute<ReponseOcr> ocr(URL urlSignee, UUID documentId) {
		byte[] corps = appeler(Appel.OCR, new RequeteOcr(urlSignee.toString(), documentId));
		return new ReponseBrute<>(lire(Appel.OCR, corps, ReponseOcr.class), corps);
	}

	/** Détermine le type du document à partir du JSON d'un résultat d'OCR (POST /v1/classification). */
	public ResultatClassification classifier(byte[] ocr, UUID documentId) {
		byte[] corps = appeler(Appel.CLASSIFICATION, new RequeteClassification(json.readTree(ocr), documentId));
		return lire(Appel.CLASSIFICATION, corps, ResultatClassification.class);
	}

	/** Extrait les champs du document à partir du JSON d'un résultat d'OCR et de son type (POST /v1/extraction). */
	public ReponseBrute<DocumentExtrait> extraire(byte[] ocr, String typeDocument, UUID documentId) {
		byte[] corps = appeler(Appel.EXTRACTION, new RequeteExtraction(json.readTree(ocr), typeDocument, documentId));
		return new ReponseBrute<>(lire(Appel.EXTRACTION, corps, DocumentExtrait.class), corps);
	}

	private byte[] appeler(Appel appel, Object requete) {
		long debut = System.nanoTime();
		ReponseHttp reponse;
		try {
			// Corps en tableau d'octets : envoyé avec Content-Length, qu'exige POST /v1/consolidation
			reponse = (appel == Appel.CONSOLIDATION ? clientConsolidation : client).post()
					.uri(appel.chemin)
					.contentType(MediaType.APPLICATION_JSON)
					.accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
					.body(json.writeValueAsBytes(requete))
					.exchange((req, res) -> new ReponseHttp(res.getStatusCode().value(), res.getBody().readAllBytes()));
		}
		catch (ResourceAccessException e) {
			throw traduireErreurReseau(appel, e);
		}
		long dureeMs = (System.nanoTime() - debut) / 1_000_000;
		if (reponse.statut() >= 400) {
			AuditCoreException erreur = traduireErreurHttp(appel, reponse);
			log.warn("audit-core POST {} : HTTP {} en {} ms ({})", appel.chemin, reponse.statut(), dureeMs,
					erreur.getMessage());
			throw erreur;
		}
		log.info("audit-core POST {} : HTTP {} en {} ms", appel.chemin, reponse.statut(), dureeMs);
		return reponse.corps();
	}

	private <T> T lire(Appel appel, byte[] corps, Class<T> type) {
		try {
			return json.readValue(corps, type);
		}
		catch (JacksonException e) {
			throw new AuditCoreErreurException("Réponse illisible d'audit-core",
					"POST %s : %s".formatted(appel.chemin, e.getOriginalMessage()), e);
		}
	}

	private AuditCoreException traduireErreurHttp(Appel appel, ReponseHttp reponse) {
		int statut = reponse.statut();
		ProblemeCore probleme = lireProbleme(reponse.corps());
		String titre = probleme != null && probleme.title() != null ? probleme.title() : "HTTP " + statut;
		String detail = probleme != null ? probleme.detail() : texte(reponse.corps());
		if (appel == Appel.CONSOLIDATION) {
			// Calcul sans document ni LLM : 400, 411, 413 ou 500 signalent un défaut d'audit-api ou d'audit-core
			return statut == 504
					? new DelaiDepasseException(titre, detail, statut)
					: new AuditCoreErreurException(titre, detail, statut);
		}
		boolean appelLlm = appel != Appel.OCR;
		return switch (statut) {
			case 413, 415 -> new DocumentIllisibleException(titre, detail, statut);
			// 422 de /v1/extraction : type de document inconnu d'audit-core, pas un défaut du document
			case 422 -> appel == Appel.OCR
					? new DocumentIllisibleException(titre, detail, statut)
					: new AuditCoreErreurException(titre, detail, statut);
			// 502 de /v1/ocr : audit-core n'a pas pu joindre le stockage
			case 502 -> appelLlm
					? new LlmIndisponibleException(titre, detail, statut)
					: new AuditCoreErreurException(titre, detail, statut);
			// 503 : LLM non configuré, ou crédits et quota épuisés (même statut, distingués par le titre)
			case 503 -> {
				if (TITRE_LLM_NON_CONFIGURE.equals(titre)) {
					yield new LlmNonConfigureException(titre, detail, statut);
				}
				yield appelLlm
						? new LlmIndisponibleException(titre, detail, statut)
						: new AuditCoreErreurException(titre, detail, statut);
			}
			case 504 -> new DelaiDepasseException(titre, detail, statut);
			default -> new AuditCoreErreurException(titre, detail, statut);
		};
	}

	private ProblemeCore lireProbleme(byte[] corps) {
		try {
			return json.readValue(corps, ProblemeCore.class);
		}
		catch (JacksonException e) {
			return null;
		}
	}

	private static String texte(byte[] corps) {
		String texte = new String(corps, StandardCharsets.UTF_8).strip();
		return texte.length() > 200 ? texte.substring(0, 200) + "…" : texte;
	}

	private static AuditCoreException traduireErreurReseau(Appel appel, ResourceAccessException e) {
		String detail = "POST %s : %s".formatted(appel.chemin, e.getMostSpecificCause());
		for (Throwable cause = e; cause != null; cause = cause.getCause()) {
			// HttpConnectTimeoutException hérite de HttpTimeoutException : à tester d'abord
			if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException) {
				return new AuditCoreInjoignableException(detail, e);
			}
			if (cause instanceof HttpTimeoutException) {
				return new DelaiDepasseException("Délai de réponse d'audit-core dépassé", detail, e);
			}
		}
		if (e.getCause() instanceof IOException) {
			return new AuditCoreInjoignableException(detail, e);
		}
		return new AuditCoreErreurException("Erreur de communication avec audit-core", detail, e);
	}

}
