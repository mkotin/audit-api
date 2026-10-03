-- V1__schema_initial.sql
-- Phase 1 : ingestion de dossiers et stockage des champs extraits (OCR/LLM)

CREATE TABLE dossier (
    id                 uuid         PRIMARY KEY,
    reference_externe  varchar(255) NOT NULL,
    domaine            varchar(100) NOT NULL,
    type_sinistre      varchar(100),
    date_creation      timestamptz  NOT NULL,
    date_mise_a_jour   timestamptz  NOT NULL,
    CONSTRAINT uk_dossier_reference_externe UNIQUE (reference_externe)
);

CREATE TABLE document (
    id                    uuid          PRIMARY KEY,
    dossier_id            uuid          NOT NULL,
    type_document         varchar(100),
    nom_fichier_original  varchar(500)  NOT NULL,
    content_type          varchar(255)  NOT NULL,
    taille_octets         bigint        NOT NULL,
    cle_stockage          varchar(1024) NOT NULL,
    empreinte_sha256      varchar(64)   NOT NULL,
    nombre_pages          integer,
    statut                varchar(20)   NOT NULL,
    message_erreur        text,
    date_reception        timestamptz   NOT NULL,
    date_mise_a_jour      timestamptz   NOT NULL,
    CONSTRAINT fk_document_dossier
        FOREIGN KEY (dossier_id) REFERENCES dossier (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_statut
        CHECK (statut IN ('RECU', 'EN_COURS', 'EXTRAIT', 'ERREUR')),
    CONSTRAINT ck_document_taille_octets  CHECK (taille_octets >= 0),
    CONSTRAINT ck_document_nombre_pages   CHECK (nombre_pages IS NULL OR nombre_pages >= 1),
    CONSTRAINT ck_document_empreinte_sha256
        CHECK (empreinte_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_document_dossier_id       ON document (dossier_id);
CREATE INDEX idx_document_statut           ON document (statut);
CREATE INDEX idx_document_empreinte_sha256 ON document (empreinte_sha256);

CREATE TABLE champ_extrait (
    id                uuid          PRIMARY KEY,
    document_id       uuid          NOT NULL,
    nom               varchar(100)  NOT NULL,
    valeur_brute      text          NOT NULL,
    valeur_normalisee text,
    confiance         numeric(4,3)  NOT NULL,
    page              integer,
    zone_x            double precision,
    zone_y            double precision,
    zone_largeur      double precision,
    zone_hauteur      double precision,
    extrait_par       varchar(255)  NOT NULL,
    date_extraction   timestamptz   NOT NULL,
    CONSTRAINT fk_champ_extrait_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT ck_champ_extrait_confiance CHECK (confiance BETWEEN 0 AND 1),
    CONSTRAINT ck_champ_extrait_page      CHECK (page IS NULL OR page >= 1),
    -- zone : les 4 coordonnées sont toutes présentes ou toutes absentes
    CONSTRAINT ck_champ_extrait_zone_complete CHECK (
        (zone_x IS NULL AND zone_y IS NULL AND zone_largeur IS NULL AND zone_hauteur IS NULL)
     OR (zone_x IS NOT NULL AND zone_y IS NOT NULL AND zone_largeur IS NOT NULL AND zone_hauteur IS NOT NULL)
    ),
    CONSTRAINT ck_champ_extrait_zone_bornes CHECK (
        (zone_x       IS NULL OR zone_x       BETWEEN 0 AND 1)
    AND (zone_y       IS NULL OR zone_y       BETWEEN 0 AND 1)
    AND (zone_largeur IS NULL OR zone_largeur BETWEEN 0 AND 1)
    AND (zone_hauteur IS NULL OR zone_hauteur BETWEEN 0 AND 1)
    )
);

-- Cet index sert aussi d'index sur la FK document_id (colonne de tête)
CREATE INDEX idx_champ_extrait_document_id_nom ON champ_extrait (document_id, nom);
