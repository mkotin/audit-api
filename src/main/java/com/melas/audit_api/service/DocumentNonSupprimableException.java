package com.melas.audit_api.service;

import java.util.UUID;

import com.melas.audit_api.domain.StatutDocument;

/** Le document est en cours de traitement ou déjà extrait : il doit être conservé. */
public class DocumentNonSupprimableException extends RuntimeException {

	public DocumentNonSupprimableException(UUID id, StatutDocument statut) {
		super(("Le document %s est au statut %s : un document en cours de traitement ou déjà extrait "
				+ "ne peut pas être supprimé (seuls RECU et ERREUR le permettent)").formatted(id, statut));
	}

}
