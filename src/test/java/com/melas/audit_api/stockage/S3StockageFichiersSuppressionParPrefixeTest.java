package com.melas.audit_api.stockage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.melas.audit_api.TestcontainersConfiguration;
import com.melas.audit_api.service.CleStockage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Suppression groupée sur un vrai stockage S3 (conteneur), avec ses garde-fous. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class S3StockageFichiersSuppressionParPrefixeTest {

	@Autowired
	private S3Client s3;

	@Autowired
	private S3Presigner presigner;

	@Autowired
	private StockageProperties proprietes;

	@Autowired
	private StockageFichiers stockage;

	private UUID dossierA;
	private UUID dossierB;

	@BeforeEach
	void deuxDossiersAvecDesFichiers() {
		dossierA = UUID.randomUUID();
		dossierB = UUID.randomUUID();
		IntStream.range(0, 5).forEach(i -> deposerObjet(dossierA, "a" + i + ".pdf"));
		IntStream.range(0, 2).forEach(i -> deposerObjet(dossierB, "b" + i + ".pdf"));
	}

	private void deposerObjet(UUID dossierId, String nom) {
		s3.putObject(requete -> requete.bucket(proprietes.bucket()).key(CleStockage.prefixeDossier(dossierId) + nom),
				RequestBody.fromString(nom));
	}

	private List<String> cles(UUID dossierId) {
		return s3.listObjectsV2(requete -> requete.bucket(proprietes.bucket())
						.prefix(CleStockage.prefixeDossier(dossierId)))
				.contents().stream().map(S3Object::key).toList();
	}

	@Test
	void supprimeTousLesFichiersDuDossierSansToucherAuxAutres() {
		int supprimes = stockage.supprimerParPrefixe(CleStockage.prefixeDossier(dossierA));

		assertThat(supprimes).isEqualTo(5);
		assertThat(cles(dossierA)).isEmpty();
		assertThat(cles(dossierB)).hasSize(2);
	}

	@Test
	void parcourtToutesLesPagesDuListingEtSupprimeParLots() {
		// Lots de 2 : 5 fichiers imposent plusieurs pages de listing et 3 lots de suppression (2 + 2 + 1).
		S3Client s3Espion = spy(s3);
		S3StockageFichiers stockageAPetitsLots = new S3StockageFichiers(s3Espion, presigner, proprietes, 2);

		int supprimes = stockageAPetitsLots.supprimerParPrefixe(CleStockage.prefixeDossier(dossierA));

		assertThat(supprimes).isEqualTo(5);
		assertThat(cles(dossierA)).isEmpty();
		assertThat(cles(dossierB)).hasSize(2);

		ArgumentCaptor<DeleteObjectsRequest> lots = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
		verify(s3Espion, atLeast(1)).deleteObjects(lots.capture());
		assertThat(lots.getAllValues())
				.extracting(lot -> lot.delete().objects().size())
				.containsExactly(2, 2, 1);
		verify(s3Espion, atLeast(3)).listObjectsV2(any(ListObjectsV2Request.class));
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = {
			"",                                                    // vide : tout le bucket
			"dossiers/",                                           // tous les dossiers
			"dossiers",
			"dossiers/0b7c5d0e-3c8f-4a59-9a8e-2f3d1c6b9e10",       // sans slash final : couvrirait 0b7c…10*
			"dossiers/pas-un-uuid/",                               // UUID invalide
			"dossiers/0b7c5d0e-3c8f-4a59-9a8e-2f3d1c6b9e1/",       // UUID tronqué
			"dossiers/0B7C5D0E-3C8F-4A59-9A8E-2F3D1C6B9E10/",      // forme non canonique (majuscules)
			"dossiers/0b7c5d0e-3c8f-4a59-9a8e-2f3d1c6b9e10/sous/", // sous-préfixe
			"autre/0b7c5d0e-3c8f-4a59-9a8e-2f3d1c6b9e10/",
			"dossiers/../",
			" dossiers/0b7c5d0e-3c8f-4a59-9a8e-2f3d1c6b9e10/"
	})
	void refuseToutPrefixeQuiNEstPasExactementCeluiDUnDossier(String prefixe) {
		S3Client s3NonUtilise = mock(S3Client.class);
		S3StockageFichiers stockageIsole = new S3StockageFichiers(s3NonUtilise, presigner, proprietes);

		assertThatIllegalArgumentException().isThrownBy(() -> stockageIsole.supprimerParPrefixe(prefixe))
				.withMessageContaining("Préfixe refusé");
		verifyNoInteractions(s3NonUtilise);
	}

	@Test
	void unPrefixeRefuseNeSupprimeRien() {
		assertThatIllegalArgumentException().isThrownBy(() -> stockage.supprimerParPrefixe("dossiers/"));

		assertThat(cles(dossierA)).hasSize(5);
		assertThat(cles(dossierB)).hasSize(2);
	}

	@Test
	void laTailleDeLotEstBorneeA1000() {
		assertThatIllegalArgumentException().isThrownBy(() -> new S3StockageFichiers(s3, presigner, proprietes, 1001));
		assertThatIllegalArgumentException().isThrownBy(() -> new S3StockageFichiers(s3, presigner, proprietes, 0));
		assertThat(S3StockageFichiers.TAILLE_LOT_MAX).isEqualTo(1000);
	}

}
