# audit-api

Backend de la plateforme d'audit de dossiers : réception des documents (PDF, images), orchestration de leur traitement par [`audit-core`](../audit-core) (OCR, classification, extraction des champs) et enregistrement des résultats.

## Stack

Java 21 · Spring Boot 4 · Spring Data JPA · PostgreSQL 17 · Flyway · stockage objet S3 (SDK AWS v2) · Testcontainers

## Prérequis

- JDK 21
- Docker Desktop
- Le dépôt [`audit-infra`](../audit-infra) cloné à côté de celui-ci :

```
code/
├── audit-api/
└── audit-infra/
```

## Lancer l'application

```bash
./mvnw spring-boot:run
```

`spring-boot:run` active le profil **`dev`** (configuré dans le `spring-boot-maven-plugin` du `pom.xml`, sans effet sur le jar packagé). Ce profil charge `application-dev.properties` et active les endpoints d'administration (voir plus bas). Depuis un IDE, l'activer avec la variable d'environnement `SPRING_PROFILES_ACTIVE=dev`, en lançant `AuditApiApplication` avec `audit-api` comme répertoire de travail (le compose est référencé en relatif). Sans ce profil — en particulier dans les tests et en production — l'administration n'existe pas.

Les services définis dans `../audit-infra/compose.yaml` (PostgreSQL, stockage S3) sont démarrés automatiquement, puis les migrations Flyway sont appliquées et le bucket `documents` est créé s'il n'existe pas. Les services restent actifs à l'arrêt de l'application.

- Swagger UI : http://localhost:8080/swagger-ui.html
- Console du stockage S3 : http://localhost:9001 (`audit-s3` / `audit-s3-secret`)

Pour réinitialiser la base et les fichiers :

```bash
cd ../audit-infra
docker compose --profile infra down -v
```

## Tests

```bash
./mvnw test
```

Les tests utilisent leur propre PostgreSQL et leur propre stockage S3 via Testcontainers ; Docker doit être lancé. audit-core est simulé par WireMock, avec de vraies réponses d'audit-core capturées sur la facture `dossier_001_doc03` du jeu de test (`src/test/resources/core`).

Le client HTTP des tests, comme celui d'audit-core en production, est celui du JDK : il ne rejoue jamais une requête de lui-même. Apache HttpClient 5, présent par le SDK S3 et que Spring choisirait par défaut, rejoue une requête (même un POST) qui reçoit 503 ou 429 : en production, un POST d'extraction rejoué serait un appel au LLM payé deux fois (`ClientAuditCoreSansNouvelleTentativeTest`).

## Image Docker

Le `Dockerfile` (multi-étapes : construction Maven, puis JRE seul, utilisateur non root) est utilisé par le service `api` du profil `full` d'audit-infra : `docker compose --profile full up -d --build` depuis `../audit-infra` lance tout le système.

## Documents

- `POST /api/dossiers` : crée un dossier ; `GET /api/dossiers/{id}` et `GET /api/dossiers?reference=…` le relisent avec ses documents.
- `POST /api/dossiers/{id}/documents` (multipart, champ `fichiers`) : dépose un ou plusieurs fichiers.
- `GET /api/documents/{id}` : métadonnées et état du traitement (statut, message d'erreur, type détecté, confiance de la classification, dates des derniers passages) ; `GET /api/documents/{id}/fichier` : téléchargement.
- `DELETE /api/documents/{id}` : supprime un document au statut `RECU` ou `ERREUR` (204) ; un document `EN_COURS` ou `EXTRAIT` est conservé (409). La ligne est supprimée en base (avec ses passages de traitement), puis le fichier et les résultats de traitement dans le stockage ; si ce dernier échoue, la suppression reste acquise et les clés orphelines sont loguées en erreur.

Règles du dépôt : PDF, JPEG, PNG ou TIFF, type déterminé d'après le contenu (sinon 415) ; 20 Mo par fichier et 100 Mo par requête (413) ; fichier vide refusé (400) ; un même contenu (empreinte SHA-256) ne peut figurer qu'une fois par dossier (409). Le dépôt est atomique : si un fichier est refusé, rien n'est créé.

## Traitement des documents

Après chaque dépôt, une fois celui-ci validé en base, chaque document est traité en arrière-plan ; l'upload répond immédiatement, document au statut `RECU`. Le traitement appelle successivement audit-core :

1. `RECU` → `EN_COURS` ;
2. `POST /v1/ocr` avec une URL signée du fichier ; la réponse complète est conservée dans le stockage (`dossiers/{dossierId}/{documentId}/ocr/{resultatOcrId}.json`) et référencée par un `ResultatOcr` ;
3. `POST /v1/classification` : une `Classification`, et le type recopié dans le document. Type `inconnu` : `ERREUR` (« Type de document non reconnu »), sans extraction. Confiance sous `app.traitement.seuil-classification` (0,5) : extraction faite quand même, avec l'alerte `classification_peu_fiable` ;
4. `POST /v1/extraction` : une `Extraction` (réponse complète conservée sous `…/extraction/{extractionId}.json`) et un `ChampExtrait` par champ de la provenance, nommé par son chemin (`total_ttc`, `lignes[2].montant_ht`) ;
5. `EN_COURS` → `EXTRAIT`.

Chaque étape enregistre son résultat dans sa propre transaction, jamais ouverte pendant un appel HTTP. En cas d'échec, le document passe en `ERREUR` avec un message sans détail technique (« OCR impossible : PDF illisible ou corrompu », « Classification impossible : LLM non configuré »…), et les résultats déjà obtenus sont conservés. Chaque passage est gardé : le plus récent fait foi.

Chaque traitement reçoit un identifiant de corrélation, présent dans les logs (`[…]` après le nom du thread) et transmis à audit-core dans `X-Request-ID`, qu'audit-core reprend dans ses propres logs.

- `POST /api/documents/{id}/traitement` : relance le traitement complet d'un document reçu, en erreur ou extrait (202, document `EN_COURS`) ; 409 si un traitement est en cours.
- `POST /api/documents/{id}/extraction` : relance seulement l'extraction, à partir du dernier résultat d'OCR et du type connu (202) ; 409 si un traitement est en cours, sans résultat d'OCR ou sans type reconnu.
- `GET /api/documents/{id}/champs` : champs de la dernière extraction (valeur brute et normalisée, confiance, page, zone, alertes), avec les alertes et la confiance globale du document ; 404 s'il n'a jamais été extrait.

Exécution : `app.traitement.parallelisme` documents à la fois (2), file de `app.traitement.taille-file` documents (100). File pleine : après un dépôt, le document reste `RECU` (signalé dans les logs) ; pour une relance, réponse 503 et document en `ERREUR`, à relancer plus tard. Au démarrage, les documents restés `EN_COURS` (application arrêtée pendant leur traitement) passent en `ERREUR` (« Traitement interrompu ») et les documents `RECU` sont remis en file. Cette reprise suppose une seule instance d'audit-api.

audit-core est joint à `app.core.url` (`http://localhost:8000`), avec les délais `app.core.delai-connexion` (5 s) et `app.core.delai-lecture` (5 min : OCR d'un scan, appels au LLM). La classification et l'extraction demandent un LLM configuré côté audit-core (`CORE_LLM_MODELE`, voir le README d'audit-infra). `app.traitement.automatique=false` désactive le traitement après dépôt et la reprise (les relances restent possibles).

## Consolidation du dossier

audit-core (`POST /v1/consolidation`) rassemble les extractions de toutes les pièces d'un dossier en un dossier consolidé : faits, intervenants, liens entre pièces, valeur retenue, contradictions. Il constate, il ne juge pas. La consolidation est recalculée automatiquement après chaque changement du dossier. Les décisions de l'intégration sont détaillées dans `docs/consolidation.md` d'audit-core (section « Intégration avec audit-api (étape 7) »).

### Déclenchement automatique

- **Demande durable.** Chaque changement qui modifie la requête envoyée à audit-core incrémente le compteur `consolidation_demande_seq` du dossier, **dans la transaction même du changement** : un arrêt de l'application ne perd aucune demande. Changements concernés : dépôt (une demande par dépôt, quel que soit le nombre de fichiers), relance du traitement ou de l'extraction, fin de traitement (`EXTRAIT` ou `ERREUR`), suppression d'un document, passage en `ERREUR` à la reprise au démarrage ou quand la file de traitement est pleine. Le démarrage automatique d'un document reçu (`RECU` → `EN_COURS`) n'en demande pas : audit-core lit ces deux statuts de la même façon. Un seul endroit incrémente le compteur : `DemandeConsolidation`, qui documente aussi l'ordre des verrous (le dossier, puis le document).
- **Poller.** Toutes les `app.consolidation.intervalle` (2 s), une requête lit les dossiers en attente (compteur de demande plus grand que le compteur traité, nouvelle tentative éventuelle due) et les confie à un exécuteur dédié (`app.consolidation.parallelisme`, 2 ; file de `app.consolidation.taille-file`, 50), distinct de celui du traitement. Les demandes se regroupent : dix documents qui finissent ensemble donnent un ou deux calculs, pas dix.
- **Un seul calcul à la fois par dossier**, poller et endpoint d'administration compris (celui-ci répond 409 « Consolidation en cours » plutôt que d'en lancer un second). Ensemble en mémoire : une seule instance d'audit-api est supposée.
- **Garde-fous.** À la fin d'un calcul, le compteur traité n'avance que jusqu'au compteur lu au début : une demande arrivée pendant le calcul reste en attente et provoque un nouveau calcul. Chaque version enregistre le compteur lu ; un résultat calculé sur un compteur plus ancien que celui de la dernière version est écarté.
- **Échecs.** Une erreur technique (audit-core injoignable, délai dépassé, 502 ou 503 d'audit-core, stockage ou base indisponible) est réessayée après un délai croissant (`app.consolidation.delais-tentatives`, 30 s, 2 min, 10 min, puis 30 min indéfiniment) ; un nouveau changement du dossier ne raccourcit pas cette attente (l'endpoint d'administration force un calcul). Toute autre erreur (400, 500 d'audit-core, réponse illisible, bug) n'est pas réessayée : la demande est marquée traitée et le prochain changement du dossier redéclenchera un calcul. Dans les deux cas, l'erreur est notée sur le dossier (statut `en_erreur`) jusqu'au succès suivant.
- **Redémarrage.** Rien de particulier : les demandes en attente sont en base et le poller les reprend. Un calcul interrompu n'a pas avancé le compteur traité ; s'il l'a été entre l'écriture du JSON dans le stockage et l'enregistrement de la version, ce fichier reste orphelin.
- Chaque calcul a son identifiant de corrélation (journaux, et `X-Request-ID` vers audit-core) ; le MDC est vidé après chaque tâche de l'exécuteur.
- `app.consolidation.automatique=false` désactive le poller : les demandes s'accumulent, seul l'endpoint d'administration consolide.

### Un calcul

Déroulement d'un calcul, sans transaction ouverte pendant l'appel HTTP ou l'accès au stockage :

1. lecture du dossier entier (trois requêtes SQL quel que soit le nombre de documents) : chaque document avec son statut, et la dernière extraction de chaque document `EXTRAIT` ;
2. appel à audit-core (délai de lecture `app.core.delai-lecture-consolidation`, 30 s : le calcul est déterministe, sans LLM) ; empreinte SHA-256 des octets de la réponse ;
3. si l'empreinte est celle de la dernière version, rien n'est créé ; sinon le JSON complet est écrit dans le stockage (`dossiers/{dossierId}/consolidation/{consolidationId}.json`), puis une `Consolidation` de numéro suivant (1, 2, 3…) est enregistrée sous verrou du dossier, avec les comptes et l'index des contradictions (`jsonb`). Le fichier est supprimé si la ligne n'est finalement pas créée.

Un dossier sans document et jamais consolidé n'a pas de version (pas d'appel) ; un dossier qui a déjà une version et n'a plus de document en reçoit une nouvelle, sans pièce.

### Consultation

- `GET /api/dossiers/{id}` : champ `consolidation` avec `statut` (`aucune`, `en_attente`, `a_jour`, `en_erreur`), `numero`, `date` et `nombreContradictions` de la dernière version.
- `GET /api/dossiers/{id}/consolidations` : historique des versions (numéros, versions et empreintes du calcul et du domaine, comptes, durée, identifiant de corrélation), de la plus récente à la plus ancienne.
- `GET /api/dossiers/{id}/consolidations/derniere` et `GET /api/dossiers/{id}/consolidations/{numero}` : JSON complet, tel qu'audit-core l'a renvoyé, numéro dans l'en-tête `X-Consolidation-Numero` ; 404 s'il n'existe pas.

Les journaux de la consolidation ne contiennent que des comptes, des versions, des durées et des identifiants, jamais une valeur extraite.

Contrat avec audit-core : `src/test/resources/core/consolidation_requete.json` et `consolidation_reponse.json` sont copiés d'audit-core (`tests/contrat`, dépôts clonés côte à côte) par :

```powershell
.\scripts\copier-contrat-consolidation.ps1
```

Ils sont exclus de toute conversion de fin de ligne (`.gitattributes`) ; un test vérifie qu'ils sont identiques, à l'octet près, à ceux d'audit-core.

## Administration et développement

Profil `dev` uniquement :

- `POST /api/admin/dossiers/{id}/consolidation` : recalcul synchrone de la consolidation ; renvoie `{"resultat": "nouvelle_version" | "inchangee" | "perimee" | "dossier_vide", "numero": …, "dureeMs": …}`. Calcul déjà en cours pour ce dossier : 409. audit-core injoignable : 503 ; délai dépassé : 504 ; autre erreur d'audit-core : 502.

- `DELETE /api/admin/dossiers/{id}` supprime le dossier, tous ses documents quel que soit leur statut, leurs champs extraits, et tous les fichiers sous `dossiers/{id}/` dans le stockage (orphelins compris).
- `GET /api/admin/documents/{id}/ocr` et `GET /api/admin/documents/{id}/extraction` renvoient le JSON complet du dernier OCR et de la dernière extraction, tel qu'audit-core l'a produit ; 404 s'il n'y en a pas.
- `GET /api/admin/documents/{id}/url-temporaire` renvoie l'URL signée du fichier et son expiration : `{"url": "…", "expireA": "2026-10-13T11:02:00Z"}`, ou 404 si le document n'existe pas. Il sert à tester audit-core à la main :

  ```bash
  URL=$(curl -s http://localhost:8080/api/admin/documents/<id>/url-temporaire | jq -r .url)
  curl -s -X POST http://localhost:8000/v1/ocr -H "Content-Type: application/json" -d "{\"url\": \"$URL\"}"
  ```

Les fichiers sont stockés hors base, sous la clé `dossiers/{dossierId}/{documentId}.{extension}`, et les résultats de traitement sous `dossiers/{dossierId}/{documentId}/` ; la base ne contient que leurs métadonnées. Le code ne dépend que de l'interface `StockageFichiers` et de l'API S3 (configuration `app.stockage.*`).

## Adresse des URL signées

Les URL signées (durée `app.stockage.duree-url-temporaire`, 15 min par défaut) permettent à audit-core de télécharger un document sans identifiants S3. Leur adresse pose une contrainte : **la signature couvre l'hôte**. audit-core ne peut donc pas réécrire l'adresse : elle doit être, dès la signature, celle par laquelle audit-core joint le stockage.

Deux propriétés distinguent les deux usages :

| Propriété                      | Utilisée par                                    | Rôle                                                        |
|--------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| `app.stockage.endpoint`        | `S3Client` (dépôt, lecture, suppression…)       | adresse du stockage vue par audit-api                       |
| `app.stockage.endpoint-public` | `S3Presigner` uniquement                        | adresse placée dans les URL signées ; à défaut, `endpoint`  |

Selon l'endroit où tournent les services :

- **audit-api sur la machine, audit-core dans Docker** (développement, profil `dev`) : `endpoint` vaut `http://localhost:9000` et `endpoint-public` vaut `http://host.docker.internal:9000`. Cette dernière valeur est définie dans `application-dev.properties` : `localhost` désignerait le conteneur d'audit-core lui-même.
- **Tout dans Docker** (service `api` du profil `full` d'audit-infra) : les deux adresses valent `http://stockage-s3:9000`, le nom du service dans le réseau compose. Elles sont passées par variables d'environnement dans le service compose :

  ```yaml
  environment:
    APP_STOCKAGE_ENDPOINT: http://stockage-s3:9000
    APP_STOCKAGE_ENDPOINT_PUBLIC: http://stockage-s3:9000
  ```

L'hôte et le port de `endpoint-public` doivent figurer dans `CORE_HOTES_AUTORISES` côté audit-core. `host.docker.internal:9000` et `stockage-s3:9000` y sont par défaut.

## Migrations

Les scripts SQL sont dans `src/main/resources/db/migration` (`V<n>__description.sql`).
Ne jamais modifier une migration déjà appliquée : en ajouter une nouvelle.
