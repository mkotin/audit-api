package com.melas.audit_api.traitement;

import java.util.UUID;

/** La file de traitement est pleine : la demande est refusée et le document passe en ERREUR, relançable (503). */
public class FileTraitementPleineException extends RuntimeException {

	public FileTraitementPleineException(UUID id) {
		super("La file de traitement est pleine : le document %s n'a pas pu être pris en charge ; réessayez plus tard"
				.formatted(id));
	}

}
