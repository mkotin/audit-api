package com.melas.audit_api.traitement;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.melas.audit_api.domain.Document;
import com.melas.audit_api.domain.Dossier;
import com.melas.audit_api.repository.DossierRepository;

class RepriseTraitementTest extends TraitementTestSupport {

	@Autowired
	private RepriseTraitement reprise;

	@Autowired
	private DossierRepository dossiers;

	@Autowired
	private TransactionTemplate transaction;

	/** Document créé directement en base : sans passer par le dépôt, aucun traitement n'est déclenché. */
	private String documentEnBase(String dossierId, String empreinte) {
		UUID id = UUID.randomUUID();
		transaction.executeWithoutResult(statut -> {
			Dossier dossier = dossiers.findById(uuid(dossierId)).orElseThrow();
			dossier.ajouterDocument(Document.builder()
					.id(id)
					.nomFichierOriginal("facture.pdf")
					.contentType("application/pdf")
					.tailleOctets(1024)
					.cleStockage("dossiers/%s/%s.pdf".formatted(dossierId, id))
					.empreinteSha256(empreinte)
					.build());
		});
		return id.toString();
	}

	@Test
	void auDemarrageLesTraitementsInterrompusPassentEnErreurEtLesDocumentsRecusSontTraites() {
		simulerTraitementReussi();
		String dossierId = creerDossierEtRenvoyerId();
		String recu = documentEnBase(dossierId, "a".repeat(64));
		String interrompu = documentEnBase(dossierId, "b".repeat(64));
		modifierDocument(interrompu, Document::demarrerTraitement);

		reprise.reprendre();

		String documentInterrompu = document(interrompu);
		assertThat((String) JsonPath.read(documentInterrompu, "$.statut")).isEqualTo("ERREUR");
		assertThat((String) JsonPath.read(documentInterrompu, "$.messageErreur")).isEqualTo("Traitement interrompu");
		assertThat((String) JsonPath.read(attendreFinTraitement(recu), "$.statut")).isEqualTo("EXTRAIT");
	}

}
