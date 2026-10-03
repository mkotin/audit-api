package com.melas.audit_api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocumentStatutTest {

	private static Document nouveauDocument() {
		return Document.builder()
				.nomFichierOriginal("devis.pdf")
				.contentType("application/pdf")
				.tailleOctets(1024)
				.cleStockage("dossier_001/devis.pdf")
				.empreinteSha256("a".repeat(64))
				.build();
	}

	@Test
	void unNouveauDocumentEstRecu() {
		assertThat(nouveauDocument().getStatut()).isEqualTo(StatutDocument.RECU);
	}

	@Test
	void cheminNominal_recu_enCours_extrait() {
		Document document = nouveauDocument();

		document.demarrerTraitement();
		assertThat(document.getStatut()).isEqualTo(StatutDocument.EN_COURS);

		document.marquerExtrait();
		assertThat(document.getStatut()).isEqualTo(StatutDocument.EXTRAIT);
	}

	@Test
	void erreurPuisRetraitement_effaceLeMessage() {
		Document document = nouveauDocument();
		document.demarrerTraitement();

		document.marquerErreur("OCR illisible");
		assertThat(document.getStatut()).isEqualTo(StatutDocument.ERREUR);
		assertThat(document.getMessageErreur()).isEqualTo("OCR illisible");

		document.demarrerTraitement();
		assertThat(document.getStatut()).isEqualTo(StatutDocument.EN_COURS);
		assertThat(document.getMessageErreur()).isNull();
	}

	@Test
	void marquerExtrait_depuisRecu_estRefuse() {
		Document document = nouveauDocument();

		assertThatThrownBy(document::marquerExtrait)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("RECU");
	}

	@Test
	void marquerErreur_depuisRecu_estRefuse() {
		Document document = nouveauDocument();

		assertThatThrownBy(() -> document.marquerErreur("x"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void demarrerTraitement_depuisEnCours_estRefuse() {
		Document document = nouveauDocument();
		document.demarrerTraitement();

		assertThatThrownBy(document::demarrerTraitement)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("EN_COURS");
	}

	@Test
	void aucuneTransition_depuisExtrait() {
		Document document = nouveauDocument();
		document.demarrerTraitement();
		document.marquerExtrait();

		assertThatThrownBy(document::demarrerTraitement).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(document::marquerExtrait).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> document.marquerErreur("x")).isInstanceOf(IllegalStateException.class);
		assertThat(document.getStatut()).isEqualTo(StatutDocument.EXTRAIT);
	}

	@Test
	void marquerExtrait_depuisErreur_estRefuse() {
		Document document = nouveauDocument();
		document.demarrerTraitement();
		document.marquerErreur("timeout");

		assertThatThrownBy(document::marquerExtrait).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void lEmpreinteEstNormaliseeEnMinuscules() {
		Document document = Document.builder()
				.nomFichierOriginal("f.pdf")
				.contentType("application/pdf")
				.cleStockage("k")
				.empreinteSha256("ABCDEF" + "0".repeat(58))
				.build();

		assertThat(document.getEmpreinteSha256()).isEqualTo("abcdef" + "0".repeat(58));
	}

	@Test
	void deuxEntitesNonPersisteesNeSontPasEgales() {
		assertThat(nouveauDocument()).isNotEqualTo(nouveauDocument());
	}

}
