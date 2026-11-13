-- V5__consolidation.sql
-- Versions de la consolidation d'un dossier (POST /v1/consolidation d'audit-core) et suivi des demandes de recalcul.
-- Chaque version est conservée ; une version n'est créée que si l'empreinte du résultat change. Le JSON complet est
-- dans le stockage : dossiers/{dossierId}/consolidation/{id}.json.

-- Suivi de la consolidation sur le dossier. Les demandes et les nouvelles tentatives automatiques arrivent à
-- l'étape 7d ; à l'étape 7c (recalcul manuel), seuls consolidation_seq_traitee et l'erreur sont utilisés.
ALTER TABLE dossier
    -- compteur incrémenté dans la transaction de chaque changement du dossier (7d)
    ADD COLUMN consolidation_demande_seq            bigint      NOT NULL DEFAULT 0,
    -- dernier compteur traité : une demande est en attente si consolidation_demande_seq > consolidation_seq_traitee
    ADD COLUMN consolidation_seq_traitee            bigint      NOT NULL DEFAULT 0,
    -- dernier échec (titre sans détail technique) ; effacé au succès suivant
    ADD COLUMN consolidation_erreur                 text,
    ADD COLUMN consolidation_erreur_le              timestamptz,
    -- nouvelles tentatives automatiques avec un délai croissant (7d)
    ADD COLUMN consolidation_prochaine_tentative_le timestamptz,
    ADD COLUMN consolidation_tentatives             integer     NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_dossier_consolidation_seq
        CHECK (consolidation_demande_seq >= 0 AND consolidation_seq_traitee >= 0),
    ADD CONSTRAINT ck_dossier_consolidation_tentatives CHECK (consolidation_tentatives >= 0);

CREATE TABLE consolidation (
    id                       uuid          PRIMARY KEY,
    dossier_id               uuid          NOT NULL,
    -- 1, 2, 3… par dossier, attribué sous verrou du dossier
    numero                   integer       NOT NULL,
    cle_stockage             varchar(1024) NOT NULL,
    -- SHA-256 des octets de la réponse d'audit-core (déterministe)
    empreinte_resultat       varchar(64)   NOT NULL,
    -- consolidation_demande_seq lu avant l'appel : une version plus ancienne n'écrase jamais une plus récente
    demande_seq              bigint        NOT NULL,
    version_consolidation    varchar(20)   NOT NULL,
    empreinte_consolidation  varchar(100)  NOT NULL,
    version_domaine          varchar(50)   NOT NULL,
    empreinte_domaine        varchar(100)  NOT NULL,
    nombre_pieces            integer       NOT NULL,
    nombre_non_exploitables  integer       NOT NULL,
    nombre_liens             integer       NOT NULL,
    nombre_contradictions    integer       NOT NULL,
    -- index des contradictions, pour le requêter : [{id, code, chemin, lienId, intervenantIds, resume}]
    contradictions           jsonb         NOT NULL,
    -- durée de l'appel à audit-core, mesurée par audit-api
    duree_ms                 integer       NOT NULL,
    -- identifiant transmis à audit-core (X-Request-ID), pour retrouver les journaux des deux services
    correlation_id           varchar(64),
    date_creation            timestamptz   NOT NULL,
    CONSTRAINT fk_consolidation_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossier (id) ON DELETE CASCADE,
    -- sert aussi d'index sur la FK dossier_id, et pour trouver la dernière version
    CONSTRAINT uk_consolidation_dossier_numero UNIQUE (dossier_id, numero),
    CONSTRAINT uk_consolidation_cle_stockage UNIQUE (cle_stockage),
    CONSTRAINT ck_consolidation_numero CHECK (numero >= 1),
    CONSTRAINT ck_consolidation_empreinte_resultat CHECK (empreinte_resultat ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_consolidation_comptes CHECK (
        nombre_pieces >= 0 AND nombre_non_exploitables >= 0 AND nombre_liens >= 0
        AND nombre_contradictions >= 0 AND duree_ms >= 0)
);
