package com.melas.audit_api.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.repository.DocumentRepository;
import com.melas.audit_api.stockage.StockageFichiers;
import com.melas.audit_api.stockage.StockageIndisponibleException;

/**
 * Pannes partielles entre la base et le stockage :
 * <ul>
 * <li>au dépôt, si l'enregistrement en base échoue après l'écriture des fichiers, ceux-ci sont retirés du stockage ;
 * <li>à la suppression, si le fichier ne peut pas être retiré du stockage, la suppression en base reste acquise.
 * </ul>
 */
@ExtendWith(OutputCaptureExtension.class)
class DepotCompensationTest extends ApiTestSupport {

	@MockitoSpyBean
	private DocumentRepository documentRepository;

	@MockitoSpyBean
	private StockageFichiers stockage;

	@Test
	void unEchecDeLaBaseSupprimeLesFichiersDejaStockes() {
		String dossierId = creerDossierEtRenvoyerId();
		doThrow(new DataAccessResourceFailureException("panne simulée de la base"))
				.when(documentRepository).saveAllAndFlush(any());

		ResponseEntity<String> depot = deposer(dossierId, pdf(), png());

		assertThat(depot.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(clesStockees(dossierId)).isEmpty();
	}

	@Test
	void laViolationDeLIndexUniqueDonne409EtSupprimeLeFichier() {
		String dossierId = creerDossierEtRenvoyerId();
		assertThat(deposer(dossierId, pdf()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
		String cleDuPremier = clesStockees(dossierId).getFirst();

		// Simule un dépôt concurrent : la vérification applicative ne voit pas encore le premier document,
		// c'est l'index unique (dossier_id, empreinte_sha256) qui doit refuser l'insertion.
		doReturn(false).when(documentRepository).existsByDossierIdAndEmpreinteSha256(any(), any());

		ResponseEntity<String> doublon = deposer(dossierId, pdf());

		assertThat(doublon.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat((Integer) JsonPath.read(doublon.getBody(), "$.status")).isEqualTo(409);
		assertThat(clesStockees(dossierId)).as("seul le fichier du premier dépôt subsiste").containsExactly(cleDuPremier);
	}

	@Test
	void unEchecDuStockageALaSuppressionLaisseLaSuppressionEnBaseEtLogueLaCle(CapturedOutput sortie) {
		String dossierId = creerDossierEtRenvoyerId();
		String documentId = JsonPath.read(deposer(dossierId, pdf()).getBody(), "$[0].id");
		String cle = clesStockees(dossierId).getFirst();
		doThrow(new StockageIndisponibleException("panne simulée du stockage", null))
				.when(stockage).supprimer(any());

		assertThat(supprimerDocument(documentId)).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(client.get().uri("/api/documents/{id}", documentId).retrieve().toBodilessEntity().getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(clesStockees(dossierId)).as("le fichier est resté, orphelin").containsExactly(cle);
		assertThat(sortie.getOut()).containsPattern("ERROR .*Fichier orphelin dans le stockage : clé " + cle);
	}

}
