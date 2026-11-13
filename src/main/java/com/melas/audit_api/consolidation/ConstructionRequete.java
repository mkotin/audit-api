package com.melas.audit_api.consolidation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.melas.audit_api.core.dto.RequeteConsolidation;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.domain.StatutDocument;
import com.melas.audit_api.repository.ChampExtraitRepository;
import com.melas.audit_api.repository.DossierRepository;
import com.melas.audit_api.repository.ExtractionRepository;
import com.melas.audit_api.service.DossierIntrouvableException;

/**
 * Construit la requête de consolidation : le dossier entier, avec la dernière extraction de chaque document EXTRAIT.
 * Trois requêtes SQL, quel que soit le nombre de documents (pas de N+1) : le dossier avec ses documents, les dernières
 * extractions, puis leurs champs.
 *
 * <p>À appeler dans une transaction (lecture seule) : les identifiants des associations paresseuses sont lus sur leurs
 * proxys, sans requête supplémentaire.
 */
@Component
class ConstructionRequete {

	/** Requête construite, et le compteur de demande du dossier lu dans la même transaction. */
	record Construction(RequeteConsolidation requete, long demandeSeq) {

		boolean dossierVide() {
			return requete.pieces().isEmpty();
		}

	}

	private final DossierRepository dossiers;
	private final ExtractionRepository extractions;
	private final ChampExtraitRepository champs;

	ConstructionRequete(DossierRepository dossiers, ExtractionRepository extractions, ChampExtraitRepository champs) {
		this.dossiers = dossiers;
		this.extractions = extractions;
		this.champs = champs;
	}

	Construction construire(UUID dossierId) {
		// Requête 1 : le dossier et ses documents
		Dossier dossier = dossiers.findAvecDocumentsById(dossierId)
				.orElseThrow(() -> new DossierIntrouvableException(dossierId));
		// Même ordre qu'audit-core (identifiants comparés en texte) : la requête ne dépend pas de l'ordre de lecture
		List<Document> documents = dossier.getDocuments().stream()
				.sorted(Comparator.comparing(document -> document.getId().toString()))
				.toList();

		// Requête 2 : la dernière extraction de chaque document EXTRAIT (seulement les EXTRAIT)
		List<UUID> extraits = documents.stream()
				.filter(document -> document.getStatut() == StatutDocument.EXTRAIT)
				.map(Document::getId)
				.toList();
		Map<UUID, Extraction> extractionParDocument = extraits.isEmpty() ? Map.of()
				: extractions.findDernieresParDocumentIdIn(extraits).stream()
						.collect(Collectors.toMap(extraction -> extraction.getDocument().getId(), Function.identity()));

		// Requête 3 : les champs de ces extractions, dans l'ordre de chacune
		Map<UUID, List<ChampExtrait>> champsParExtraction = extractionParDocument.isEmpty() ? Map.of()
				: champs.findByExtractionIdIn(extractionParDocument.values().stream().map(Extraction::getId).toList())
						.stream()
						.collect(Collectors.groupingBy(champ -> champ.getExtraction().getId(), LinkedHashMap::new,
								Collectors.toList()));

		List<RequeteConsolidation.Piece> pieces = documents.stream()
				.map(document -> piece(document, extractionParDocument.get(document.getId()), champsParExtraction))
				.toList();
		return new Construction(
				new RequeteConsolidation(dossier.getId(), dossier.getReferenceExterne(), pieces),
				dossier.getConsolidationDemandeSeq());
	}

	private static RequeteConsolidation.Piece piece(Document document, Extraction extraction,
			Map<UUID, List<ChampExtrait>> champsParExtraction) {
		return new RequeteConsolidation.Piece(document.getId(), document.getNomFichierOriginal(),
				document.getStatut().name(), document.getMessageErreur(), document.getTypeDocument(),
				extraction == null ? null : extraction(extraction, champsParExtraction.getOrDefault(extraction.getId(),
						List.of())));
	}

	private static RequeteConsolidation.Extraction extraction(Extraction extraction, List<ChampExtrait> champs) {
		return new RequeteConsolidation.Extraction(extraction.getId(), extraction.getTypeDocument(),
				extraction.getVersionDomaine(), extraction.getEmpreinteDomaine(), extraction.getVersionConsigne(),
				extraction.getEmpreinteConsigne(), extraction.getModele(),
				champs.stream()
						.map(champ -> new RequeteConsolidation.Champ(champ.getNom(), champ.getValeurBrute(),
								champ.getValeurNormalisee(), champ.getConfiance().doubleValue()))
						.toList());
	}

}
