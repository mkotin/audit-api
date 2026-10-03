# audit-api

Backend de la plateforme d'audit de dossiers : réception des documents (PDF, images) et stockage des champs extraits par le service OCR/LLM.

## Stack

Java 21 · Spring Boot 4 · Spring Data JPA · PostgreSQL 17 · Flyway · Testcontainers

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

La base PostgreSQL définie dans `../audit-infra/compose.yaml` est démarrée automatiquement, puis les migrations Flyway sont appliquées. La base reste active à l'arrêt de l'application.

Pour réinitialiser la base :

```bash
cd ../audit-infra
docker compose --profile infra down -v
```

## Tests

```bash
./mvnw test
```

Les tests utilisent leur propre PostgreSQL via Testcontainers ; Docker doit être lancé.

## Migrations

Les scripts SQL sont dans `src/main/resources/db/migration` (`V<n>__description.sql`).
Ne jamais modifier une migration déjà appliquée : en ajouter une nouvelle.
