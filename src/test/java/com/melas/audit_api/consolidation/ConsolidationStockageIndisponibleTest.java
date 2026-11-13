package com.melas.audit_api.consolidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.melas.audit_api.stockage.StockageException;
import com.melas.audit_api.stockage.StockageFichiers;
import com.melas.audit_api.stockage.StockageIndisponibleException;

/** Stockage indisponible à l'écriture du JSON : pas de version, échec noté sur le dossier. */
class ConsolidationStockageIndisponibleTest extends ConsolidationTestSupport {

	@MockitoSpyBean
	private StockageFichiers espion;

	@Autowired
	private ConsolidationService service;

	@BeforeEach
	void charger() {
		chargerDossierContrat();
	}

	@Test
	void unStockageIndisponibleEstNoteSansCreerDeVersion() {
		doThrow(new StockageIndisponibleException("simulé", null)).when(espion)
				.enregistrer(contains("/consolidation/"), any(), anyLong(), anyString());
		simulerConsolidation(reponseContrat());

		assertThatThrownBy(() -> service.consolider(DOSSIER_CONTRAT)).isInstanceOf(StockageException.class);

		assertThat(jdbc.queryForObject("SELECT count(*) FROM consolidation WHERE dossier_id = ?", Integer.class,
				DOSSIER_CONTRAT)).isZero();
		assertThat(jdbc.queryForObject("SELECT consolidation_erreur FROM dossier WHERE id = ?", String.class,
				DOSSIER_CONTRAT)).isEqualTo("Consolidation impossible : stockage des fichiers indisponible");
	}

}
