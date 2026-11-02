package com.melas.audit_api.traitement;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.domain.ResultatOcr;
import com.melas.audit_api.repository.ChampExtraitRepository;
import com.melas.audit_api.repository.ClassificationRepository;
import com.melas.audit_api.repository.ExtractionRepository;
import com.melas.audit_api.repository.ResultatOcrRepository;

class TraitementTest extends TraitementTestSupport {

	@Autowired
	private ResultatOcrRepository resultatsOcr;

	@Autowired
	private ClassificationRepository classifications;

	@Autowired
	private ExtractionRepository extractions;

	@Autowired
	private ChampExtraitRepository champs;

	@Autowired
	private TransactionTemplate transaction;

	/** Nombre de champs de la provenance dans la réponse réelle d'extraction. */
	private static int champsDeLaFixture() {
		Map<String, Object> provenance = JsonPath.read(fixture("extraction.json"), "$.provenance");
		return provenance.size();
	}

	private ResponseEntity<String> demander(String chemin, String documentId) {
		return client.post().uri(chemin, documentId).retrieve().toEntity(String.class);
	}

	private String lireStockage(String cle) {
		return s3.getObjectAsBytes(requete -> requete.bucket(proprietesStockage.bucket()).key(cle))
				.asString(StandardCharsets.UTF_8);
	}

	private List<LoggedRequest> appels(String chemin, String documentId) {
		return core.findAll(postRequestedFor(urlEqualTo(chemin))
				.withRequestBody(matchingJsonPath("$.document_id", equalTo(documentId))));
	}

	@Test
	void traitementCompletReussi() {
		simulerTraitementReussi();
		String dossierId = creerDossierEtRenvoyerId();

		ResponseEntity<String> depot = deposer(dossierId, pdf());
		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat((String) JsonPath.read(depot.getBody(), "$[0].statut")).isEqualTo("RECU");
		String documentId = JsonPath.read(depot.getBody(), "$[0].id");

		String document = attendreFinTraitement(documentId);
		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("EXTRAIT");
		assertThat((String) JsonPath.read(document, "$.typeDocument")).isEqualTo("facture");
		assertThat((Object) JsonPath.read(document, "$.messageErreur")).isNull();
		assertThat((Double) JsonPath.read(document, "$.confianceClassification")).isEqualTo(1.0);
		assertThat((Integer) JsonPath.read(document, "$.nombrePages")).isEqualTo(1);
		assertThat((String) JsonPath.read(document, "$.dateDernierOcr")).isNotNull();
		assertThat((String) JsonPath.read(document, "$.dateDerniereClassification")).isNotNull();
		assertThat((String) JsonPath.read(document, "$.dateDerniereExtraction")).isNotNull();

		// OCR : métadonnées en base, JSON complet dans le stockage, sous la clé convenue
		ResultatOcr ocr = resultatsOcr.findFirstByDocumentIdOrderByDateCreationDesc(uuid(documentId)).orElseThrow();
		assertThat(ocr.getCleStockage())
				.isEqualTo("dossiers/%s/%s/ocr/%s.json".formatted(dossierId, documentId, ocr.getId()));
		assertThat(ocr.getVersionService()).isEqualTo("0.1.0");
		assertThat(ocr.getMethodeGlobale()).isEqualTo("natif");
		assertThat(ocr.getMoteurs()).isEqualTo("pymupdf 1.28.2");
		assertThat(ocr.getNombrePages()).isEqualTo(1);
		assertThat(lireStockage(ocr.getCleStockage())).isEqualTo(fixture("ocr.json"));

		// Classification
		var classification = classifications.findFirstByDocumentIdOrderByDateCreationDesc(uuid(documentId))
				.orElseThrow();
		assertThat(classification.getTypeDocument()).isEqualTo("facture");
		assertThat(classification.getModele()).isEqualTo("beam");
		assertThat(classification.getVersionConsigne()).isEqualTo("1");
		assertThat(classification.getEmpreinteConsigne()).hasSize(64);
		assertThat(classification.getBlocs()).extracting(b -> b.id()).containsExactly("p1-b2", "p1-b4", "p1-b53");

		// Extraction : passage rattaché au résultat d'OCR, JSON complet dans le stockage, un champ par chemin
		Extraction extraction = extractions.findFirstByDocumentIdOrderByDateCreationDesc(uuid(documentId))
				.orElseThrow();
		transaction.executeWithoutResult(statut -> assertThat(
				extractions.findById(extraction.getId()).orElseThrow().getResultatOcr().getId()).isEqualTo(ocr.getId()));
		assertThat(extraction.getCleStockage())
				.isEqualTo("dossiers/%s/%s/extraction/%s.json".formatted(dossierId, documentId, extraction.getId()));
		assertThat(lireStockage(extraction.getCleStockage())).isEqualTo(fixture("extraction.json"));
		assertThat(extraction.getConfiance()).isEqualByComparingTo("0.7");
		assertThat(extraction.getEmpreinteDomaine()).hasSize(64);
		assertThat(extraction.getJetonsEntree()).isEqualTo(6730);

		List<ChampExtrait> champsEnBase = champs.findByExtractionIdOrderByOrdreAsc(extraction.getId());
		assertThat(champsEnBase).hasSize(champsDeLaFixture());
		assertThat(champsEnBase).filteredOn(champ -> champ.getNom().equals("lignes[2].montant_ht")).singleElement()
				.satisfies(champ -> {
					assertThat(champ.getValeurNormalisee()).isEqualTo("56.00");
					assertThat(champ.getPage()).isEqualTo(1);
					assertThat(champ.getZone()).isNotNull();
					assertThat(champ.getExtraitPar()).isEqualTo("beam/consigne-1");
				});

		// GET /champs : ordre du document, chemins de liste, valeurs normalisées
		String reponse = client.get().uri("/api/documents/{id}/champs", documentId).retrieve().body(String.class);
		assertThat((String) JsonPath.read(reponse, "$.extractionId")).isEqualTo(extraction.getId().toString());
		assertThat((String) JsonPath.read(reponse, "$.typeDocument")).isEqualTo("facture");
		assertThat((Double) JsonPath.read(reponse, "$.confiance")).isEqualTo(0.7);
		assertThat((String) JsonPath.read(reponse, "$.extraitPar")).isEqualTo("beam/consigne-1");
		assertThat((List<?>) JsonPath.read(reponse, "$.alertes")).isEmpty();
		assertThat((String) JsonPath.read(reponse, "$.champs[0].nom")).isEqualTo("numero_document");
		assertThat((String) JsonPath.read(reponse, "$.champs[0].valeurBrute")).isEqualTo("F-2026-0243");
		List<String> totalTtc = JsonPath.read(reponse, "$.champs[?(@.nom == 'total_ttc')].valeurNormalisee");
		assertThat(totalTtc).containsExactly("316.80");
		List<Double> zoneX = JsonPath.read(reponse, "$.champs[?(@.nom == 'total_ttc')].zone.x");
		assertThat(zoneX).containsExactly(0.8418);
		List<String> noms = JsonPath.read(reponse, "$.champs[*].nom");
		assertThat(noms).contains("lignes[0].designation", "lignes[3].montant_ht");

		// Endpoints de développement : JSON complets
		assertThat(client.get().uri("/api/admin/documents/{id}/ocr", documentId).retrieve().body(String.class))
				.isEqualTo(fixture("ocr.json"));
		assertThat(client.get().uri("/api/admin/documents/{id}/extraction", documentId).retrieve().body(String.class))
				.isEqualTo(fixture("extraction.json"));
	}

	@Test
	void lIdentifiantDeCorrelationEstTransmisAuxTroisAppels() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);

		List<LoggedRequest> requetes = core.findAll(postRequestedFor(urlPathMatching("/v1/.*"))
				.withRequestBody(matchingJsonPath("$.document_id",
						equalTo(documentId))));
		assertThat(requetes).extracting(LoggedRequest::getUrl)
				.containsExactly("/v1/ocr", "/v1/classification", "/v1/extraction");
		List<String> identifiants = requetes.stream().map(requete -> requete.getHeader("X-Request-ID")).toList();
		assertThat(identifiants.getFirst()).matches("[0-9a-f]{32}");
		assertThat(identifiants).containsOnly(identifiants.getFirst());
	}

	@Test
	void laClassificationEstRenvoyeeAvecLeResultatDOcrIntact() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);

		String corps = appels("/v1/classification", documentId).getFirst().getBodyAsString();
		Object ocrEnvoye = JsonPath.read(corps, "$.ocr");
		assertThat(ocrEnvoye).isEqualTo(JsonPath.read(fixture("ocr.json"), "$"));
		String extraction = appels("/v1/extraction", documentId).getFirst().getBodyAsString();
		assertThat((String) JsonPath.read(extraction, "$.type_document")).isEqualTo("facture");
	}

	@Test
	void typeInconnu_documentEnErreurSansExtraction() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification_inconnu.json");
		simulerReponse("/v1/extraction", "extraction.json");
		String documentId = deposerUnPdf();

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("ERREUR");
		assertThat((String) JsonPath.read(document, "$.messageErreur")).isEqualTo("Type de document non reconnu");
		assertThat((String) JsonPath.read(document, "$.typeDocument")).isEqualTo("inconnu");
		assertThat((Double) JsonPath.read(document, "$.confianceClassification")).isEqualTo(0.0);
		assertThat(appels("/v1/extraction", documentId)).isEmpty();
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isEqualTo(1);

		ResponseEntity<String> relance = demander("/api/documents/{id}/extraction", documentId);
		assertThat(relance.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat((String) JsonPath.read(relance.getBody(), "$.detail")).contains("type");
	}

	@Test
	void confianceDeClassificationBasse_extractionFaiteAvecAlerte() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification_peu_fiable.json");
		simulerReponse("/v1/extraction", "extraction.json");
		String documentId = deposerUnPdf();

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("EXTRAIT");
		assertThat((Double) JsonPath.read(document, "$.confianceClassification")).isEqualTo(0.3);
		String reponse = client.get().uri("/api/documents/{id}/champs", documentId).retrieve().body(String.class);
		List<String> codes = JsonPath.read(reponse, "$.alertes[*].code");
		assertThat(codes).containsExactly("classification_peu_fiable");
		assertThat((String) JsonPath.read(reponse, "$.alertes[0].message")).contains("0.3").contains("0.5");
	}

	@Test
	void erreurDeLOcr_documentEnErreurAvecLeTitreDuProbleme() {
		simulerProbleme("/v1/ocr", 422, "probleme_ocr_pdf_illisible.json");
		String documentId = deposerUnPdf();

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("ERREUR");
		assertThat((String) JsonPath.read(document, "$.messageErreur"))
				.isEqualTo("OCR impossible : PDF illisible ou corrompu");
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isZero();
		assertThat(appels("/v1/classification", documentId)).isEmpty();

		// Sans résultat d'OCR, l'extraction seule ne peut pas être relancée
		assertThat(demander("/api/documents/{id}/extraction", documentId).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	@Test
	void llmNonConfigure_lOcrReussiEstConserve() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerProbleme("/v1/classification", 503, "probleme_llm_non_configure.json");
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("ERREUR");
		assertThat((String) JsonPath.read(document, "$.messageErreur"))
				.isEqualTo("Classification impossible : LLM non configuré");
		assertThat((String) JsonPath.read(document, "$.dateDernierOcr")).isNotNull();
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isEqualTo(1);
		assertThat(clesStockees(dossierId)).hasSize(2).anyMatch(cle -> cle.contains("/ocr/"));

		// Un document en erreur peut être supprimé : ses résultats de traitement disparaissent avec lui
		assertThat(supprimerDocument(documentId)).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(clesStockees(dossierId)).isEmpty();
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isZero();
	}

	@Test
	void auditCoreInjoignable() {
		core.stop();
		try {
			String documentId = deposerUnPdf();

			String document = attendreFinTraitement(documentId);

			assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("ERREUR");
			assertThat((String) JsonPath.read(document, "$.messageErreur"))
					.isEqualTo("OCR impossible : audit-core injoignable");
		}
		finally {
			core.start();
		}
	}

	@Test
	void delaiDeLectureDepasse() {
		core.stubFor(post(urlEqualTo("/v1/ocr")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(fixture("ocr.json"))
				.withFixedDelay(4_000)));
		String documentId = deposerUnPdf();

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("ERREUR");
		assertThat((String) JsonPath.read(document, "$.messageErreur"))
				.isEqualTo("OCR impossible : Délai de réponse d'audit-core dépassé");
	}

	@Test
	void delaiDuLlmDepasse_504() {
		simulerReponse("/v1/ocr", "ocr.json");
		simulerReponse("/v1/classification", "classification.json");
		core.stubFor(post(urlEqualTo("/v1/extraction")).willReturn(aResponse()
				.withStatus(504)
				.withHeader("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE)
				.withBody("""
						{"type":"about:blank","title":"Délai LLM dépassé","status":504,\
						"detail":"Pas de réponse du LLM en 180 s.","instance":"/v1/extraction"}""")));
		String documentId = deposerUnPdf();

		String document = attendreFinTraitement(documentId);

		assertThat((String) JsonPath.read(document, "$.messageErreur"))
				.isEqualTo("Extraction impossible : Délai LLM dépassé");
		assertThat((String) JsonPath.read(document, "$.typeDocument")).isEqualTo("facture");
		assertThat(client.get().uri("/api/documents/{id}/champs", documentId).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void relanceDuTraitementComplet_depuisErreurPuisDepuisExtrait() {
		simulerProbleme("/v1/ocr", 422, "probleme_ocr_pdf_illisible.json");
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);

		simulerTraitementReussi();
		ResponseEntity<String> relance = demander("/api/documents/{id}/traitement", documentId);
		assertThat(relance.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		String document = attendreFinTraitement(documentId);
		assertThat((String) JsonPath.read(document, "$.statut")).isEqualTo("EXTRAIT");
		assertThat((Object) JsonPath.read(document, "$.messageErreur")).isNull();

		assertThat(demander("/api/documents/{id}/traitement", documentId).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		attendreFinTraitement(documentId);
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isEqualTo(2);
		assertThat(extractions.findByDocumentIdOrderByDateCreationAsc(uuid(documentId))).hasSize(2);
		// Échec initial, puis les deux relances
		assertThat(appels("/v1/ocr", documentId)).hasSize(3);
	}

	@Test
	void relanceDeLExtractionSeule_sansNouvelOcr_ancienPassageConserve() {
		simulerTraitementReussi();
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);
		Extraction premiere = extractions.findFirstByDocumentIdOrderByDateCreationDesc(uuid(documentId)).orElseThrow();
		ResultatOcr ocr = resultatsOcr.findFirstByDocumentIdOrderByDateCreationDesc(uuid(documentId)).orElseThrow();
		core.resetRequests();

		ResponseEntity<String> relance = demander("/api/documents/{id}/extraction", documentId);

		assertThat(relance.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat((String) JsonPath.read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");
		assertThat(appels("/v1/ocr", documentId)).isEmpty();
		assertThat(appels("/v1/classification", documentId)).isEmpty();
		assertThat(appels("/v1/extraction", documentId)).hasSize(1);
		// Le JSON de l'OCR relu dans le stockage est renvoyé tel quel
		Object ocrEnvoye = JsonPath.read(appels("/v1/extraction", documentId).getFirst().getBodyAsString(), "$.ocr");
		assertThat(ocrEnvoye).isEqualTo(JsonPath.read(fixture("ocr.json"), "$"));

		List<Extraction> passages = extractions.findByDocumentIdOrderByDateCreationAsc(uuid(documentId));
		assertThat(passages).hasSize(2);
		assertThat(passages.getFirst().getId()).isEqualTo(premiere.getId());
		assertThat(champs.findByExtractionIdOrderByOrdreAsc(premiere.getId())).hasSize(champsDeLaFixture());
		Extraction seconde = passages.getLast();
		transaction.executeWithoutResult(statut -> assertThat(
				extractions.findById(seconde.getId()).orElseThrow().getResultatOcr().getId()).isEqualTo(ocr.getId()));
		assertThat(resultatsOcr.countByDocumentId(uuid(documentId))).isEqualTo(1);

		String reponse = client.get().uri("/api/documents/{id}/champs", documentId).retrieve().body(String.class);
		assertThat((String) JsonPath.read(reponse, "$.extractionId")).isEqualTo(seconde.getId().toString());
	}

	@Test
	void lesRelancesSontRefuseesPendantUnTraitement() {
		core.stubFor(post(urlEqualTo("/v1/ocr")).willReturn(aResponse()
				.withStatus(200)
				.withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.withBody(fixture("ocr.json"))
				.withFixedDelay(1_000)));
		simulerReponse("/v1/classification", "classification.json");
		simulerReponse("/v1/extraction", "extraction.json");
		String documentId = deposerUnPdf();
		await().until(() -> JsonPath.<String>read(document(documentId), "$.statut"),
				"EN_COURS"::equals);

		ResponseEntity<String> traitement = demander("/api/documents/{id}/traitement", documentId);
		ResponseEntity<String> extraction = demander("/api/documents/{id}/extraction", documentId);

		assertThat(traitement.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(traitement.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
		assertThat(extraction.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat((String) JsonPath.read(attendreFinTraitement(documentId), "$.statut")).isEqualTo("EXTRAIT");
	}

	@Test
	void reponses404() {
		String documentId = deposerUnPdf();
		attendreFinTraitement(documentId);
		String inconnu = UUID.randomUUID().toString();

		assertThat(demander("/api/documents/{id}/traitement", inconnu).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(demander("/api/documents/{id}/extraction", inconnu).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(client.get().uri("/api/documents/{id}/champs", inconnu).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		// Document existant, jamais extrait (aucune réponse simulée : audit-core répond 404)
		ResponseEntity<String> champsAbsents = client.get().uri("/api/documents/{id}/champs", documentId).retrieve()
				.toEntity(String.class);
		assertThat(champsAbsents.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat((String) JsonPath.read(champsAbsents.getBody(), "$.detail")).contains("Aucune extraction");
		assertThat(client.get().uri("/api/admin/documents/{id}/ocr", documentId).retrieve().toEntity(String.class)
				.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

}
