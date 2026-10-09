package com.melas.audit_api.service;

import java.util.UUID;

/** Aucun document ne correspond à l'identifiant demandé. */
public class DocumentIntrouvableException extends RuntimeException {

	public DocumentIntrouvableException(UUID id) {
		super("Document introuvable : " + id);
	}

}
