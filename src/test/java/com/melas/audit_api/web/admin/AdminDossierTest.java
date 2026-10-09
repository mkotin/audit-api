package com.melas.audit_api.web.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.domain.ChampExtrait;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.repository.ChampExtraitRepository;
import com.melas.audit_api.web.ApiTestSupport;

import software.amazon.awssdk.core.sync.RequestBody;

@ActiveProfiles("dev")
class AdminDossierTest extends ApiTestSupport {

	@Autowired
	private ChampExtraitRepository champExtraitRepository;

	private HttpStatusCode supprimerDossier(String dossierId) {
		return client.delete().uri("/api/admin/dossiers/{id}", dossierId).retrieve().toBodilessEntity().getStatusCode();
	}

	/** Variante du PDF de test (contenu, donc empreinte, différents). */
	private static Fichier autrePdf() {
		Fichier pdf = pdf();
		byte[] suffixe = "%variante\n".getBytes(StandardCharsets.US_ASCII);
		byte[] contenu = Arrays.copyOf(pdf.contenu(), pdf.contenu().length + suffixe.length);
		System.arraycopy(suffixe, 0, contenu, pdf.contenu().length, suffixe.length);
		return new Fichier("rapport-expert.pdf", "application/pdf", contenu);
	}

	@Test
	void supprimeLeDossierSesDocumentsQuelQueSoitLeurStatutEtTousSesFichiers() {
		String dossierId = creerDossierEtRenvoyerId();
		ResponseEntity<String> depot = deposer(dossierId, pdf(), png(), autrePdf());
		List<String> documentIds = JsonPath.read(depot.getBody(), "$[*].id");
		String recu = documentIds.get(0);
		String enCours = documentIds.get(1);
		String extrait = documentIds.get(2);
		modifierDocument(enCours, Document::demarrerTraitement);
		modifierDocument(extrait, document -> {
			document.demarrerTraitement();
			document.ajouterChamp(ChampExtrait.builder()
					.nom("total_ttc")
					.valeurBrute("1 451,00 €")
					.confiance(new BigDecimal("0.970"))
					.extraitPar("test")
					.build());
			document.marquerExtrait();
		});
		assertThat(champExtraitRepository.findByDocumentId(UUID.fromString(extrait))).hasSize(1);

		// Un fichier orphelin (non référencé en base) sous le préfixe du dossier doit aussi disparaître.
		String orphelin = "dossiers/%s/%s.pdf".formatted(dossierId, UUID.randomUUID());
		s3.putObject(requete -> requete.bucket(proprietesStockage.bucket()).key(orphelin),
				RequestBody.fromString("orphelin"));
		assertThat(clesStockees(dossierId)).hasSize(4);

		// Un autre dossier ne doit pas être touché.
		String autreDossierId = creerDossierEtRenvoyerId();
		deposer(autreDossierId, pdf());

		assertThat(supprimerDossier(dossierId)).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(client.get().uri("/api/dossiers/{id}", dossierId).retrieve().toBodilessEntity().getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		for (String documentId : List.of(recu, enCours, extrait)) {
			assertThat(client.get().uri("/api/documents/{id}", documentId).retrieve().toBodilessEntity()
					.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		}
		assertThat(champExtraitRepository.findByDocumentId(UUID.fromString(extrait))).isEmpty();
		assertThat(clesStockees(dossierId)).isEmpty();

		assertThat(clesStockees(autreDossierId)).hasSize(1);
		assertThat(client.get().uri("/api/dossiers/{id}", autreDossierId).retrieve().toBodilessEntity()
				.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void repond404PourUnDossierInexistant() {
		ResponseEntity<String> reponse = client.delete().uri("/api/admin/dossiers/{id}", UUID.randomUUID())
				.retrieve().toEntity(String.class);

		assertThat(reponse.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(reponse.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
	}

}
