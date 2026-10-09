package com.melas.audit_api.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.melas.audit_api.service.DocumentEnDoublonException;
import com.melas.audit_api.service.DocumentIntrouvableException;
import com.melas.audit_api.service.DocumentNonSupprimableException;
import com.melas.audit_api.service.DossierIntrouvableException;
import com.melas.audit_api.service.FichierInvalideException;
import com.melas.audit_api.service.ReferenceDejaUtiliseeException;
import com.melas.audit_api.service.TypeFichierNonSupporteException;
import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageIndisponibleException;

/**
 * Traduit les exceptions en réponses ProblemDetail (RFC 9457). Les erreurs propres à Spring MVC (validation,
 * paramètre manquant, taille d'upload dépassée…) sont prises en charge par {@link ResponseEntityExceptionHandler}.
 */
@RestControllerAdvice
public class GestionnaireErreurs extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GestionnaireErreurs.class);

	@ExceptionHandler({ FichierInvalideException.class, MultipartException.class })
	ProblemDetail requeteInvalide(Exception e) {
		return probleme(HttpStatus.BAD_REQUEST, "Requête invalide", e.getMessage());
	}

	@ExceptionHandler({ DossierIntrouvableException.class, DocumentIntrouvableException.class })
	ProblemDetail introuvable(RuntimeException e) {
		return probleme(HttpStatus.NOT_FOUND, "Ressource introuvable", e.getMessage());
	}

	@ExceptionHandler({ ReferenceDejaUtiliseeException.class, DocumentEnDoublonException.class,
			DocumentNonSupprimableException.class })
	ProblemDetail conflit(RuntimeException e) {
		return probleme(HttpStatus.CONFLICT, "Conflit", e.getMessage());
	}

	@ExceptionHandler(TypeFichierNonSupporteException.class)
	ProblemDetail typeNonSupporte(TypeFichierNonSupporteException e) {
		return probleme(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Type de fichier non supporté", e.getMessage());
	}

	@ExceptionHandler(StockageIndisponibleException.class)
	ProblemDetail stockageIndisponible(StockageIndisponibleException e) {
		log.error("Stockage de fichiers indisponible", e);
		return probleme(HttpStatus.SERVICE_UNAVAILABLE, "Stockage indisponible",
				"Le stockage des fichiers est momentanément indisponible ; réessayez plus tard.");
	}

	@ExceptionHandler(StockageException.class)
	ProblemDetail erreurStockage(StockageException e) {
		log.error("Erreur du stockage de fichiers", e);
		return probleme(HttpStatus.INTERNAL_SERVER_ERROR, "Erreur du stockage", "Erreur lors de l'accès au fichier.");
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException e,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail corps = probleme(HttpStatus.CONTENT_TOO_LARGE, "Fichier trop volumineux",
				"Taille maximale dépassée (voir spring.servlet.multipart.max-file-size et max-request-size).");
		return handleExceptionInternal(e, corps, headers, HttpStatus.CONTENT_TOO_LARGE, request);
	}

	private static ProblemDetail probleme(HttpStatus statut, String titre, String detail) {
		ProblemDetail probleme = ProblemDetail.forStatusAndDetail(statut, detail);
		probleme.setTitle(titre);
		return probleme;
	}

}
