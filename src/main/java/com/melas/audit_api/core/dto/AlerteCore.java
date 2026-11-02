package com.melas.audit_api.core.dto;

import java.util.List;

/** Alerte calculée par audit-core ; champs : chemins des champs concernés ("lignes[2].montant_ht"). */
public record AlerteCore(String code, String message, List<String> champs) {
}
