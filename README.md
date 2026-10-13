# audit-api

Backend de la plateforme d'audit de dossiers : réception des documents (PDF, images) et stockage des champs extraits par le service OCR/LLM.

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

Les tests utilisent leur propre PostgreSQL et leur propre stockage S3 via Testcontainers ; Docker doit être lancé.

## Documents

- `POST /api/dossiers` : crée un dossier ; `GET /api/dossiers/{id}` et `GET /api/dossiers?reference=…` le relisent avec ses documents.
- `POST /api/dossiers/{id}/documents` (multipart, champ `fichiers`) : dépose un ou plusieurs fichiers.
- `GET /api/documents/{id}` : métadonnées ; `GET /api/documents/{id}/fichier` : téléchargement.
- `DELETE /api/documents/{id}` : supprime un document au statut `RECU` ou `ERREUR` (204) ; un document `EN_COURS` ou `EXTRAIT` est conservé (409). La ligne est supprimée en base, puis le fichier dans le stockage ; si ce dernier échoue, la suppression reste acquise et la clé du fichier orphelin est loguée en erreur.

Règles du dépôt : PDF, JPEG, PNG ou TIFF, type déterminé d'après le contenu (sinon 415) ; 20 Mo par fichier et 100 Mo par requête (413) ; fichier vide refusé (400) ; un même contenu (empreinte SHA-256) ne peut figurer qu'une fois par dossier (409). Le dépôt est atomique : si un fichier est refusé, rien n'est créé.

Administration et développement (profil `dev` uniquement) :

- `DELETE /api/admin/dossiers/{id}` supprime le dossier, tous ses documents quel que soit leur statut, leurs champs extraits, et tous les fichiers sous `dossiers/{id}/` dans le stockage (orphelins compris).
- `GET /api/admin/documents/{id}/url-temporaire` renvoie l'URL signée du fichier et son expiration : `{"url": "…", "expireA": "2026-10-13T11:02:00Z"}`, ou 404 si le document n'existe pas. Il sert à tester audit-core à la main, en attendant l'orchestration :

  ```bash
  URL=$(curl -s http://localhost:8080/api/admin/documents/<id>/url-temporaire | jq -r .url)
  curl -s -X POST http://localhost:8000/v1/ocr -H "Content-Type: application/json" -d "{\"url\": \"$URL\"}"
  ```

Les fichiers sont stockés hors base, sous la clé `dossiers/{dossierId}/{documentId}.{extension}` ; la base ne contient que leurs métadonnées. Le code ne dépend que de l'interface `StockageFichiers` et de l'API S3 (configuration `app.stockage.*`).

## Adresse des URL signées

Les URL signées (durée `app.stockage.duree-url-temporaire`, 15 min par défaut) permettent à audit-core de télécharger un document sans identifiants S3. Leur adresse pose une contrainte : **la signature couvre l'hôte**. audit-core ne peut donc pas réécrire l'adresse : elle doit être, dès la signature, celle par laquelle audit-core joint le stockage.

Deux propriétés distinguent les deux usages :

| Propriété                      | Utilisée par                                    | Rôle                                                        |
|--------------------------------|-------------------------------------------------|-------------------------------------------------------------|
| `app.stockage.endpoint`        | `S3Client` (dépôt, lecture, suppression…)       | adresse du stockage vue par audit-api                       |
| `app.stockage.endpoint-public` | `S3Presigner` uniquement                        | adresse placée dans les URL signées ; à défaut, `endpoint`  |

Selon l'endroit où tournent les services :

- **audit-api sur la machine, audit-core dans Docker** (développement, profil `dev`) : `endpoint` vaut `http://localhost:9000` et `endpoint-public` vaut `http://host.docker.internal:9000`. Cette dernière valeur est définie dans `application-dev.properties` : `localhost` désignerait le conteneur d'audit-core lui-même.
- **Tout dans Docker** (futur service compose d'audit-api) : les deux adresses valent `http://stockage-s3:9000`, le nom du service dans le réseau compose. Elles se passent par variables d'environnement dans le service compose d'audit-api :

  ```yaml
  environment:
    APP_STOCKAGE_ENDPOINT: http://stockage-s3:9000
    APP_STOCKAGE_ENDPOINT_PUBLIC: http://stockage-s3:9000
  ```

L'hôte et le port de `endpoint-public` doivent figurer dans `CORE_HOTES_AUTORISES` côté audit-core. `host.docker.internal:9000` et `stockage-s3:9000` y sont par défaut.

## Migrations

Les scripts SQL sont dans `src/main/resources/db/migration` (`V<n>__description.sql`).
Ne jamais modifier une migration déjà appliquée : en ajouter une nouvelle.
