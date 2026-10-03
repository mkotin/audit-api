package com.melas.audit_api.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.melas.audit_api.TestcontainersConfiguration;
import com.melas.audit_api.domain.BoiteEnglobante;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
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
	private EntityManager entityManager;

	private Dossier dossierAvecUnDocumentEtDeuxChamps(String reference) {
		Dossier dossier = Dossier.builder()
				.referenceExterne(reference)
				.domaine("assurance_habitation")
				.typeSinistre("degat_des_eaux")
				.build();

		Document devis = Document.builder()
				.typeDocument("devis")
				.nomFichierOriginal("devis_plombier.pdf")
				.contentType("application/pdf")
				.tailleOctets(245_000)
				.cleStockage(reference + "/devis_plombier.pdf")
				.empreinteSha256(EMPREINTE)
				.nombrePages(2)
				.build();
		dossier.ajouterDocument(devis);

		devis.ajouterChamp(ChampExtrait.builder()
				.nom("total_ttc")
				.valeurBrute("1 451,00 €")
				.valeurNormalisee("1451.00")
				.confiance(new BigDecimal("0.970"))
				.page(2)
				.zone(new BoiteEnglobante(0.62, 0.81, 0.2, 0.03))
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build());
		devis.ajouterChamp(ChampExtrait.builder()
				.nom("date_devis")
				.valeurBrute("30/03/2026")
				.valeurNormalisee("2026-03-30")
				.confiance(new BigDecimal("0.885"))
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build());

		return dossier;
	}

	private void viderContexte() {
		entityManager.flush();
		entityManager.clear();
	}

	@Test
	void persisteEtRelitUnDossierComplet() {
		Instant avant = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		Dossier dossier = dossierRepository.save(dossierAvecUnDocumentEtDeuxChamps("dossier_001"));
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
					assertThat(champ.getDateExtraction()).isNotNull();
				})
				.anySatisfy(champ -> {
					assertThat(champ.getNom()).isEqualTo("date_devis");
					assertThat(champ.getPage()).isNull();
					assertThat(champ.getZone()).isNull();
				});
	}

	@Test
	void requetesParStatutEtParEmpreinte() {
		Dossier dossier = dossierRepository.save(dossierAvecUnDocumentEtDeuxChamps("dossier_002"));
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
		Dossier dossier = dossierRepository.save(dossierAvecUnDocumentEtDeuxChamps("dossier_003"));
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
		Dossier dossier = dossierRepository.save(dossierAvecUnDocumentEtDeuxChamps("dossier_004"));
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
	void laBaseRejetteUneConfianceSuperieureA1() {
		assertConfianceRejetee("1.500");
	}

	@Test
	void laBaseRejetteUneConfianceNegative() {
		assertConfianceRejetee("-0.100");
	}

	private void assertConfianceRejetee(String confiance) {
		Dossier dossier = dossierRepository.save(dossierAvecUnDocumentEtDeuxChamps("dossier_conf_" + confiance));
		viderContexte();

		Document document = documentRepository.findById(dossier.getDocuments().getFirst().getId()).orElseThrow();
		ChampExtrait champ = ChampExtrait.builder()
				.nom("total_ht")
				.valeurBrute("1 209,17 €")
				.confiance(new BigDecimal(confiance))
				.extraitPar("llm-extract-v1/claude-sonnet")
				.build();
		document.ajouterChamp(champ);

		assertThatThrownBy(() -> champExtraitRepository.saveAndFlush(champ))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ck_champ_extrait_confiance");
	}

}
