package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.exception.NotFoundException;
import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.core.ClientAuditCore;
import com.melas.audit_api.web.ApiTestSupport;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bout en bout de la consolidation, avec la <b>vraie image audit-core:latest</b> (et PostgreSQL et S3 en conteneurs) :
 * les extractions de dossiers du jeu de test sont injectées en base à partir de la vérité terrain canonique
 * (../dataset/data/canonique), comme le fait requete_canonique dans les tests d'audit-core, puis le poller consolide.
 *
 * <p>Chaque document est enregistré dans sa propre transaction avec sa demande de consolidation, comme les fins de
 * traitement d'un dépôt groupé : un seul passage du poller doit donner un seul calcul. Un second calcul, forcé, ne
 * doit pas créer de nouvelle version.
 *
 * <p>Lent (démarrage d'audit-core) : exclu de {@code ./mvnw test}, lancé par {@code ./mvnw test -Pbout-en-bout}.
 * L'image doit être construite d'abord, et reconstruite après toute modification d'audit-core.
 */
@Tag("bout-en-bout")
@ActiveProfiles("dev")
class ConsolidationBoutEnBoutTest extends ApiTestSupport {

	static final String IMAGE = "audit-core:latest";
	private static final int PORT = 8000;
	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static GenericContainer<?> auditCore;
	private static Path canonique;
	private static String versionDomaine;
	private static String empreinteDomaine;

	@MockitoSpyBean
	private ClientAuditCore clientAuditCore;

	@Autowired
	private PollerConsolidation poller;

	@Autowired
	private ConsolidationsEnCours enCours;

	@Autowired
	private DemandeConsolidation demande;

	@Autowired
	private TransactionTemplate transaction;

	@Autowired
	private JdbcTemplate jdbc;

	// --- Conteneur audit-core et jeu de test, vérifiés avant tout -----------------------------------------------

	@BeforeAll
	static void demarrerAuditCore() throws Exception {
		canonique = Path.of(System.getenv().getOrDefault("AUDIT_DATASET", "../dataset/data")).resolve("canonique");
		if (!Files.isRegularFile(canonique.resolve("dossier_004.json"))) {
			throw new IllegalStateException("Jeu de test introuvable : %s (dépôt dataset cloné à côté d'audit-api, ou "
					.formatted(canonique.toAbsolutePath().normalize()) + "variable AUDIT_DATASET vers son dossier data)");
		}
		if (auditCore != null) {
			return;
		}
		try {
			DockerClientFactory.instance().client().inspectImageCmd(IMAGE).exec();
		}
		catch (NotFoundException e) {
			throw new IllegalStateException("Image " + IMAGE + " absente : construire l'image d'abord "
					+ "(docker build -t audit-core ../audit-core), puis relancer ce test", e);
		}
		GenericContainer<?> conteneur = new GenericContainer<>(DockerImageName.parse(IMAGE))
				// jamais de téléchargement : une image « audit-core » publique n'est pas la nôtre
				.withImagePullPolicy(image -> false)
				.withEnv("LANGSMITH_TRACING", "false")
				.withEnv("LANGSMITH_TRACING_V2", "false")
				.withEnv("CORE_LLM_MODELE", "")
				.withExposedPorts(PORT)
				.waitingFor(Wait.forHttp("/health").forPort(PORT).withStartupTimeout(Duration.ofMinutes(3)));
		conteneur.start();
		auditCore = conteneur;

		HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
		HttpResponse<String> consolidation = http.send(HttpRequest.newBuilder(uri("/v1/consolidation"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{}"))
				.build(), HttpResponse.BodyHandlers.ofString());
		if (consolidation.statusCode() == 404) {
			throw new IllegalStateException("Image " + IMAGE + " trop ancienne (pas de POST /v1/consolidation) : "
					+ "la reconstruire (docker build -t audit-core ../audit-core)");
		}
		JsonNode domaine = JSON.readTree(http.send(HttpRequest.newBuilder(uri("/v1/domaines")).build(),
				HttpResponse.BodyHandlers.ofString()).body()).get(0);
		versionDomaine = domaine.get("version").stringValue();
		empreinteDomaine = domaine.get("empreinte").stringValue();
	}

	private static URI uri(String chemin) {
		return URI.create("http://%s:%d%s".formatted(auditCore.getHost(), auditCore.getMappedPort(PORT), chemin));
	}

	@DynamicPropertySource
	static void urlAuditCore(DynamicPropertyRegistry registre) {
		registre.add("app.core.url", () -> uri("").toString());
	}

	// --- Injection de la vérité terrain canonique -------------------------------------------------------------

	/** Valeurs canoniques aplaties en chemins (lignes[2].montant_ht), comme _aplatir d'audit-core. */
	private static void aplatir(JsonNode valeurs, String prefixe, Map<String, String> champs) {
		for (Map.Entry<String, JsonNode> entree : valeurs.properties()) {
			JsonNode valeur = entree.getValue();
			if (valeur.isArray()) {
				for (int i = 0; i < valeur.size(); i++) {
					aplatir(valeur.get(i), "%s%s[%d].".formatted(prefixe, entree.getKey(), i), champs);
				}
			}
			else if (!valeur.isNull()) {
				champs.put(prefixe + entree.getKey(), forme(valeur));
			}
		}
	}

	/** Forme textuelle, comme _forme d'audit-core : booléens en "true" / "false". */
	private static String forme(JsonNode valeur) {
		if (valeur.isBoolean()) {
			return valeur.booleanValue() ? "true" : "false";
		}
		return valeur.isString() ? valeur.stringValue() : valeur.toString();
	}

	/**
	 * Crée le dossier par l'API, puis enregistre chaque document du dossier canonique (EXTRAIT, avec un résultat
	 * d'OCR factice, son extraction et ses champs) dans sa propre transaction, avec sa demande de consolidation.
	 */
	private UUID injecter(String dossier) throws IOException {
		UUID dossierId = UUID.fromString(JsonPath.read(creerDossier(referenceUnique(dossier)).getBody(), "$.id"));
		JsonNode canon = JSON.readTree(Files.readString(canonique.resolve(dossier + ".json")));
		int rang = 0;
		for (JsonNode document : canon.get("documents")) {
			int ordreDocument = rang++;
			transaction.executeWithoutResult(statut -> {
				demande.demander(dossierId); // en premier : ordre des verrous (DemandeConsolidation)
				enregistrer(dossierId, document, ordreDocument);
			});
		}
		return dossierId;
	}

	private void enregistrer(UUID dossierId, JsonNode document, int rang) {
		String nom = document.get("document_id").stringValue();
		String type = document.get("type").stringValue();
		UUID documentId = UUID.randomUUID();
		UUID ocrId = UUID.randomUUID();
		UUID extractionId = UUID.randomUUID();
		Timestamp maintenant = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO document (id, dossier_id, type_document, nom_fichier_original, content_type, "
				+ "taille_octets, cle_stockage, empreinte_sha256, statut, date_reception, date_mise_a_jour) "
				+ "VALUES (?, ?, ?, ?, 'application/pdf', 1000, ?, ?, 'EXTRAIT', now(), now())",
				documentId, dossierId, type, nom + ".pdf", "dossiers/%s/%s.pdf".formatted(dossierId, documentId),
				"%064x".formatted(rang + 1));
		jdbc.update("INSERT INTO resultat_ocr (id, document_id, cle_stockage, version_service, type_fichier, "
				+ "methode_globale, nombre_pages, duree_telechargement_ms, duree_extraction_ms, date_creation) "
				+ "VALUES (?, ?, ?, 'verite_terrain', 'pdf', 'natif', 1, 0, 0, ?)", ocrId, documentId,
				"verite_terrain/ocr/" + ocrId + ".json", maintenant);
		jdbc.update("INSERT INTO extraction (id, document_id, resultat_ocr_id, cle_stockage, type_document, confiance, "
				+ "alertes, pages_analysees, modele, fournisseur, version_consigne, empreinte_consigne, version_domaine, "
				+ "empreinte_domaine, duree_ms, tentatives, date_creation) VALUES (?, ?, ?, ?, ?, 1, '[]'::jsonb, 1, "
				+ "'verite_terrain', 'verite_terrain', 'verite_terrain', 'verite_terrain', ?, ?, 0, 1, ?)",
				extractionId, documentId, ocrId, "verite_terrain/extraction/" + extractionId + ".json", type,
				versionDomaine, empreinteDomaine, maintenant);
		Map<String, String> champs = new LinkedHashMap<>();
		aplatir(document.get("valeurs"), "", champs);
		int ordre = 0;
		for (Map.Entry<String, String> champ : champs.entrySet()) {
			jdbc.update("INSERT INTO champ_extrait (id, document_id, extraction_id, nom, valeur_brute, "
					+ "valeur_normalisee, confiance, extrait_par, date_extraction, alertes, ordre) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, 'verite_terrain', now(), '[]'::jsonb, ?)", UUID.randomUUID(),
					documentId, extractionId, champ.getKey(), champ.getValue(), champ.getValue(), BigDecimal.ONE,
					ordre++);
		}
	}

	// --- Consolidation -----------------------------------------------------------------------------------------

	/**
	 * Un passage du poller pour toutes les demandes du dépôt groupé : un seul calcul, version 1, à jour. Puis un
	 * recalcul forcé : résultat inchangé, pas de version 2. Renvoie le dossier consolidé.
	 */
	private JsonNode consoliderUneFois(UUID dossierId) {
		assertThat(jdbc.queryForObject("SELECT consolidation_demande_seq FROM dossier WHERE id = ?", Long.class,
				dossierId)).as("une demande par document").isGreaterThan(1);

		assertThat(poller.examiner()).isGreaterThanOrEqualTo(1);
		await().atMost(Duration.ofSeconds(60)).until(() -> !enCours.estEnCours(dossierId));

		verify(clientAuditCore, times(1)).consolider(argThat(requete -> requete.dossierId().equals(dossierId)));
		String dossier = client.get().uri("/api/dossiers/{id}", dossierId).retrieve().body(String.class);
		assertThat(JsonPath.<String>read(dossier, "$.consolidation.statut")).isEqualTo("a_jour");
		assertThat(JsonPath.<Integer>read(dossier, "$.consolidation.numero")).isEqualTo(1);

		ResponseEntity<String> recalcul = client.post().uri("/api/admin/dossiers/{id}/consolidation", dossierId)
				.retrieve().toEntity(String.class);
		assertThat(recalcul.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(JsonPath.<String>read(recalcul.getBody(), "$.resultat")).isEqualTo("inchangee");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class,
				dossierId)).isEqualTo(1);

		byte[] json = client.get().uri("/api/dossiers/{id}/consolidations/derniere", dossierId).retrieve()
				.body(byte[].class);
		JsonNode consolide = JSON.readTree(json);
		for (JsonNode piece : consolide.get("pieces")) {
			assertThat(piece.get("motif").isNull()).as("pièce %s exploitable", piece.get("nom_fichier")).isTrue();
		}
		return consolide;
	}

	@ParameterizedTest
	@CsvSource({ "dossier_004, 36 rue des Sorbiers-Blancs", "dossier_009, 18 rue des Brasseurs-Flamands" })
	void lAdresseRetenueEstLaJuste(String dossier, String juste) throws IOException {
		JsonNode consolide = consoliderUneFois(injecter(dossier));

		JsonNode adresse = consolide.get("faits").get("bien").get("adresse");
		assertThat(adresse.get("statut").stringValue()).isEqualTo("contradictoire");
		assertThat(adresse.get("retenue").get("valeur").stringValue()).contains(juste);
		List<String> chemins = new ArrayList<>();
		consolide.get("contradictions").forEach(c -> chemins.add(c.get("chemin").stringValue()));
		assertThat(chemins).contains("faits.bien.adresse");
	}

	@Test
	void leConstatDuDossier008EstRattacheAuTiers() throws IOException {
		JsonNode consolide = consoliderUneFois(injecter("dossier_008"));

		List<JsonNode> tiers = new ArrayList<>();
		consolide.get("intervenants").forEach(i -> {
			if ("tiers".equals(i.get("role").stringValue())) {
				tiers.add(i);
			}
		});
		assertThat(tiers).hasSize(1);
		JsonNode tiersDuConstat = tiers.getFirst();
		assertThat(tiersDuConstat.get("nom").get("valeurs").get(0).get("valeur").stringValue())
				.isEqualTo("Olivier Castagnola");
		assertThat(tiersDuConstat.get("pieces")).as("déclaration et constat").hasSize(2);
		assertThat(tiersDuConstat.get("assureur").get("statut").stringValue()).isEqualTo("concordant");
	}

}
