-- V2__document_empreinte_unique_par_dossier.sql
-- Un même fichier (même empreinte SHA-256) ne peut figurer qu'une fois dans un dossier.
-- Garantit la règle même en cas d'uploads concurrents ; entre dossiers différents, le doublon reste permis
-- (sa détection relève des contrôles d'audit).

CREATE UNIQUE INDEX uk_document_dossier_empreinte ON document (dossier_id, empreinte_sha256);
