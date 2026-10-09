package com.melas.audit_api.service;

import java.util.Arrays;
import java.util.Optional;

/** Types de fichiers acceptés au dépôt, identifiés par leur type MIME réel. */
public enum TypeFichier {

	PDF("application/pdf", "pdf"),
	JPEG("image/jpeg", "jpg"),
	PNG("image/png", "png"),
	TIFF("image/tiff", "tiff");

	private final String mimeType;
	private final String extension;

	TypeFichier(String mimeType, String extension) {
		this.mimeType = mimeType;
		this.extension = extension;
	}

	public String mimeType() {
		return mimeType;
	}

	public String extension() {
		return extension;
	}

	public static Optional<TypeFichier> depuisMimeType(String mimeType) {
		return Arrays.stream(values()).filter(type -> type.mimeType.equals(mimeType)).findFirst();
	}

}
