package com.melas.audit_api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.melas.audit_api.TestcontainersConfiguration;
import com.melas.audit_api.domain.Alerte;
import com.melas.audit_api.domain.BoiteEnglobante;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.domain.Extraction;
import com.melas.audit_api.domain.ResultatOcr;
import com.melas.audit_api.domain.StatutDocument;

import jakarta.persistence.EntityManager;

/**
 * Tests sur un vrai PostgreSQL (Testcontainers). Le démarrage du contexte valide à lui seul
 * que les migrations Flyway s'appliquent et que ddl-auto=validate accepte le mapping.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class PersistanceTest {

	private static final String EMPREINTE = "3f".repeat(32);

	@Autowired
	private DossierRepository dossierRepository;

	@Autowired
	private DocumentRepository documentRepository;

	@Autowired
	private ChampExtraitRepository champExtraitRepository;

	@Autowired
	private ResultatOcrRepository resultatOcrRepository;

	@Autowired
	private ExtractionRepository extractionRepository;

	@Autowired
	private EntityManager entityManager;

	private Dossier dossierAvecUnDocument(String reference) {
		Dossier dossier = Dossier.builder()
				.referenceExterne(reference)
				.domaine("assurance_habitation")
				.build();

		Document devis = Document.builder()
				.id(UUID.randomUUID())
				.typeDocument("devis")
				.nomFichierOriginal("devis_plombier.pdf")
				.contentType("application/pdf")
				.tailleOctets(245_000)
				.cleStockage(reference + "/devis_plombier.pdf")
				.empreinteSha256(EMPREINTE)
				.nombrePages(2)
				.build();
		dossier.ajouterDocument(devis);
		return dossier;
	}

	/** Enregistre le dossier, puis un passage d'OCR et un passage d'extraction de deux champs pour son document. */
	private Dossier enregistrerAvecDeuxChamps(String reference) {
		Dossier dossier = dossierRepository.save(dossierAvecUnDocument(reference));
		Document devis = dossier.getDocuments().getFirst();
		ResultatOcr ocr = resultatOcrRepository.save(ResultatOcr.builder()
				.id(UUID.randomUUID())
				.document(devis)
				.cleStockage(reference + "/ocr.json")
				.versionService("0.1.0")
				.typeFichier("pdf")
				.methodeGlobale("natif")
				.moteurs("pymupdf 1.28.2")
				.nombrePages(2)
				.dureeTelechargementMs(23)
				.dureeExtractionMs(9)
				.build());
		Extraction extraction = Extraction.builder()
				.id(UUID.randomUUID())
				.document(devis)
				.resultatOcr(ocr)
				.cleStockage(reference + "/extraction.json")
				.typeDocument("devis")
				.confiance(0.885)
				.alertes(List.of(new Alerte("champ_obligatoire_absent", "Champ absent", List.of("emetteur_siret"))))
				.pagesAnalysees(2)
				.modele("beam")
				.fournisseur("ollama")
				.versionConsigne("1")
				.empreinteConsigne("a".repeat(64))
				.versionDomaine("1.3")
				.empreinteDomaine("b".repeat(64))
				.dureeMs(8807)
				.tentatives(1)
				.build();

		extraction.ajouterChamp(ChampExtrait.builder()
				.nom("total_ttc")
				.valeurBrute("1 451,00 €")
				.valeurNormalisee("1451.00")
				.confiance(new BigDecimal("0.970"))
				.page(2)
				.zone(new BoiteEnglobante(0.62, 0.81, 0.2, 0.03))
				.alertes(List.of(new Alerte("bloc_corrige", "Bloc corrigé", List.of("total_ttc"))))
				.ordre(0)
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build());
		extraction.ajouterChamp(ChampExtrait.builder()
				.nom("date_devis")
				.valeurBrute("30/03/2026")
				.valeurNormalisee("2026-03-30")
				.confiance(new BigDecimal("0.885"))
				.ordre(1)
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build());
		extractionRepository.save(extraction);
		return dossier;
	}

	private void viderContexte() {
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	void persisteEtRelitUnDossierComplet() {
		Instant avant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_001");
		viderContexte();

		Dossier relu = dossierRepository.findByReferenceExterne("dossier_001").orElseThrow();
		assertThat(relu.getId()).isEqualTo(dossier.getId());
		assertThat(relu).isEqualTo(dossier);
		assertThat(relu.getDomaine()).isEqualTo("assurance_habitation");
		assertThat(relu.getDateCreation()).isAfterOrEqualTo(avant);
		assertThat(relu.getDateMiseAJour()).isNotNull();
		assertThat(relu.getDocuments()).hasSize(1);

		Document document = relu.getDocuments().getFirst();
		assertThat(document.getDossier()).isEqualTo(relu);
		assertThat(document.getStatut()).isEqualTo(StatutDocument.RECU);
		assertThat(document.getDateReception()).isNotNull();
		assertThat(document.getNombrePages()).isEqualTo(2);

		assertThat(champExtraitRepository.findByDocumentId(document.getId()))
				.hasSize(2)
				.anySatisfy(champ -> {
					assertThat(champ.getNom()).isEqualTo("total_ttc");
					assertThat(champ.getValeurNormalisee()).isEqualTo("1451.00");
					assertThat(champ.getConfiance()).isEqualByComparingTo("0.970");
					assertThat(champ.getPage()).isEqualTo(2);
					assertThat(champ.getZone()).isEqualTo(new BoiteEnglobante(0.62, 0.81, 0.2, 0.03));
					assertThat(champ.getExtraitPar()).isEqualTo("llm-extract-v1/claude-sonnet");
					assertThat(champ.getAlertes())
							.containsExactly(new Alerte("bloc_corrige", "Bloc corrigé", List.of("total_ttc")));
					assertThat(champ.getOrdre()).isZero();
					assertThat(champ.getExtraction().getAlertes()).containsExactly(
							new Alerte("champ_obligatoire_absent", "Champ absent", List.of("emetteur_siret")));
					assertThat(champ.getDateExtraction()).isNotNull();
				})
				.anySatisfy(champ -> {
					assertThat(champ.getNom()).isEqualTo("date_devis");
					assertThat(champ.getPage()).isNull();
					assertThat(champ.getZone()).isNull();
					assertThat(champ.getAlertes()).isEmpty();
				});
	}

	@Test
	void requetesParStatutEtParEmpreinte() {
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_002");
		Document document = dossier.getDocuments().getFirst();
		document.demarrerTraitement();
		viderContexte();

		assertThat(documentRepository.findByStatut(StatutDocument.EN_COURS))
				.extracting(Document::getId).contains(document.getId());
		assertThat(documentRepository.findByStatut(StatutDocument.RECU))
				.extracting(Document::getId).doesNotContain(document.getId());
		assertThat(documentRepository.findByEmpreinteSha256(EMPREINTE))
				.extracting(Document::getId).contains(document.getId());
	}

	@Test
	void laSuppressionDUnDossierSupprimeDocumentsEtChamps() {
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_003");
		UUID documentId = dossier.getDocuments().getFirst().getId();
		viderContexte();

		dossierRepository.delete(dossierRepository.findById(dossier.getId()).orElseThrow());
		viderContexte();

		assertThat(dossierRepository.findById(dossier.getId())).isEmpty();
		assertThat(documentRepository.findById(documentId)).isEmpty();
		assertThat(champExtraitRepository.findByDocumentId(documentId)).isEmpty();
	}

	@Test
	void laBaseSupprimeEnCascadeMemeSansPasserParJpa() {
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_004");
		UUID documentId = dossier.getDocuments().getFirst().getId();
		viderContexte();

		entityManager.createNativeQuery("DELETE FROM dossier WHERE id = :id")
				.setParameter("id", dossier.getId())
				.executeUpdate();

		Number documents = (Number) entityManager
				.createNativeQuery("SELECT count(*) FROM document WHERE id = :id")
				.setParameter("id", documentId).getSingleResult();
		Number champs = (Number) entityManager
				.createNativeQuery("SELECT count(*) FROM champ_extrait WHERE document_id = :id")
				.setParameter("id", documentId).getSingleResult();
		assertThat(documents.longValue()).isZero();
		assertThat(champs.longValue()).isZero();
	}

	@Test
	void laColonneTypeSinistreNExistePlus() {
		// Champ legacy supprimé par la migration V4
		Number colonnes = (Number) entityManager
				.createNativeQuery("SELECT count(*) FROM information_schema.columns "
						+ "WHERE table_name = 'dossier' AND column_name = 'type_sinistre'")
				.getSingleResult();
		assertThat(colonnes.longValue()).isZero();
	}

	@Test
	void unDocumentEnregistreNEstPlusConsidereCommeNouveau() {
		Dossier dossier = dossierAvecUnDocument("dossier_005");
		Document document = dossier.getDocuments().getFirst();
		assertThat(document.isNew()).isTrue();

		dossierRepository.save(dossier);
		viderContexte();

		assertThat(document.isNew()).isFalse();
		assertThat(documentRepository.findById(document.getId()).orElseThrow().isNew()).isFalse();
	}

	@Test
	void laBaseRejetteLeMemeFichierDeuxFoisDansUnDossier() {
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_006");
		viderContexte();

		Dossier relu = dossierRepository.findById(dossier.getId()).orElseThrow();
		Document copie = Document.builder()
				.id(UUID.randomUUID())
				.nomFichierOriginal("copie.pdf")
				.contentType("application/pdf")
				.tailleOctets(245_000)
				.cleStockage("dossiers/copie.pdf")
				.empreinteSha256(EMPREINTE)
				.build();
		relu.ajouterDocument(copie);

		assertThatThrownBy(() -> documentRepository.saveAndFlush(copie))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("uk_document_dossier_empreinte");
	}

	@Test
	void laBaseRejetteUneConfianceSuperieureA1() {
		assertConfianceRejetee("1.500");
	}

	@Test
	void laBaseRejetteUneConfianceNegative() {
		assertConfianceRejetee("-0.100");
	}

	private void assertConfianceRejetee(String confiance) {
		Dossier dossier = enregistrerAvecDeuxChamps("dossier_conf_" + confiance);
		viderContexte();

		Extraction extraction = extractionRepository
				.findFirstByDocumentIdOrderByDateCreationDesc(dossier.getDocuments().getFirst().getId()).orElseThrow();
		ChampExtrait champ = ChampExtrait.builder()
				.nom("total_ht")
				.valeurBrute("1 209,17 €")
				.confiance(new BigDecimal(confiance))
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build();
		extraction.ajouterChamp(champ);

		assertThatThrownBy(() -> champExtraitRepository.saveAndFlush(champ))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_champ_extrait_confiance");
	}

}
