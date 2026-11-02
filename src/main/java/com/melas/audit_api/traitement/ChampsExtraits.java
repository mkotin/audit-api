package com.melas.audit_api.traitement;

import java.util.List;

import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Extraction;

/** Dernière extraction d'un document et ses champs, dans l'ordre du type de document. */
public record ChampsExtraits(Extraction extraction, List<ChampExtrait> champs) {
}
