-- V3__traitement_documents.sql
-- Phase 1, étape 7 : passages d'OCR, de classification et d'extraction (audit-core).
-- Chaque passage est conservé : le plus récent fait foi. Les alertes sont des listes {code, message, champs}.
-- Les valeurs propres au contrat d'audit-core (méthodes, types de document) ne sont pas contraintes par des CHECK :
-- une valeur nouvelle côté audit-core ne doit pas faire échouer l'enregistrement.

CREATE TABLE resultat_ocr (
    id                       uuid          PRIMARY KEY,
    document_id              uuid          NOT NULL,
    -- JSON complet de la réponse : dossiers/{dossierId}/{documentId}/ocr/{id}.json
    cle_stockage             varchar(1024) NOT NULL,
    version_service          varchar(50)   NOT NULL,
    type_fichier             varchar(10)   NOT NULL,
    methode_globale          varchar(10)   NOT NULL,
    -- moteurs distincts des pages, ex. "pymupdf 1.28.2, tesseract 5.5.0"
    moteurs                  varchar(255),
    nombre_pages             integer       NOT NULL,
    duree_telechargement_ms  integer       NOT NULL,
    duree_extraction_ms      integer       NOT NULL,
    date_creation            timestamptz   NOT NULL,
    CONSTRAINT fk_resultat_ocr_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT uk_resultat_ocr_cle_stockage UNIQUE (cle_stockage),
    CONSTRAINT ck_resultat_ocr_nombre_pages CHECK (nombre_pages >= 0),
    CONSTRAINT ck_resultat_ocr_durees CHECK (duree_telechargement_ms >= 0 AND duree_extraction_ms >= 0)
);
-- Sert aussi d'index sur la FK document_id ; ordonné pour trouver le passage le plus récent
CREATE INDEX idx_resultat_ocr_document_id_date ON resultat_ocr (document_id, date_creation DESC);

CREATE TABLE classification (
    id                  uuid          PRIMARY KEY,
    document_id         uuid          NOT NULL,
    resultat_ocr_id     uuid          NOT NULL,
    -- type choisi, ou 'inconnu'
    type_document       varchar(100)  NOT NULL,
    confiance           numeric(4,3)  NOT NULL,
    -- type proposé par le classifieur par indices d'audit-core
    type_reference      varchar(100)  NOT NULL,
    raison              text,
    -- blocs cités : [{id, page}]
    blocs               jsonb         NOT NULL,
    alertes             jsonb         NOT NULL,
    pages_analysees     integer       NOT NULL,
    modele              varchar(255)  NOT NULL,
    fournisseur         varchar(100)  NOT NULL,
    version_consigne    varchar(50)   NOT NULL,
    empreinte_consigne  varchar(100)  NOT NULL,
    version_domaine     varchar(50)   NOT NULL,
    jetons_entree       integer,
    jetons_sortie       integer,
    duree_ms            integer       NOT NULL,
    date_creation       timestamptz   NOT NULL,
    CONSTRAINT fk_classification_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    -- NO ACTION (contrôlée en fin d'instruction) : la suppression en cascade depuis document aboutit
    CONSTRAINT fk_classification_resultat_ocr
        FOREIGN KEY (resultat_ocr_id) REFERENCES resultat_ocr (id),
    CONSTRAINT ck_classification_confiance CHECK (confiance BETWEEN 0 AND 1)
);
CREATE INDEX idx_classification_document_id_date ON classification (document_id, date_creation DESC);
CREATE INDEX idx_classification_resultat_ocr_id  ON classification (resultat_ocr_id);

CREATE TABLE extraction (
    id                  uuid          PRIMARY KEY,
    document_id         uuid          NOT NULL,
    resultat_ocr_id     uuid          NOT NULL,
    -- JSON complet de la réponse : dossiers/{dossierId}/{documentId}/extraction/{id}.json
    cle_stockage        varchar(1024) NOT NULL,
    type_document       varchar(100)  NOT NULL,
    -- confiance globale du document
    confiance           numeric(4,3)  NOT NULL,
    -- alertes du document (celles des champs sont dans champ_extrait), dont classification_peu_fiable
    alertes             jsonb         NOT NULL,
    pages_analysees     integer       NOT NULL,
    modele              varchar(255)  NOT NULL,
    fournisseur         varchar(100)  NOT NULL,
    version_consigne    varchar(50)   NOT NULL,
    empreinte_consigne  varchar(100)  NOT NULL,
    version_domaine     varchar(50)   NOT NULL,
    empreinte_domaine   varchar(100)  NOT NULL,
    jetons_entree       integer,
    jetons_sortie       integer,
    duree_ms            integer       NOT NULL,
    tentatives          integer       NOT NULL,
    date_creation       timestamptz   NOT NULL,
    CONSTRAINT fk_extraction_document
        FOREIGN KEY (document_id) REFERENCES document (id) ON DELETE CASCADE,
    CONSTRAINT fk_extraction_resultat_ocr
        FOREIGN KEY (resultat_ocr_id) REFERENCES resultat_ocr (id),
    CONSTRAINT uk_extraction_cle_stockage UNIQUE (cle_stockage),
    CONSTRAINT ck_extraction_confiance CHECK (confiance BETWEEN 0 AND 1)
);
CREATE INDEX idx_extraction_document_id_date ON extraction (document_id, date_creation DESC);
CREATE INDEX idx_extraction_resultat_ocr_id  ON extraction (resultat_ocr_id);

-- La table est vide à ce stade (aucun extracteur avant cette étape) ; si ce n'était pas le cas, le NOT NULL ferait
-- échouer la migration plutôt que de laisser des champs sans passage d'extraction.
ALTER TABLE champ_extrait
    ADD COLUMN extraction_id uuid    NOT NULL,
    ADD COLUMN alertes       jsonb   NOT NULL DEFAULT '[]',
    -- rang dans la provenance d'audit-core (ordre des champs du type de document)
    ADD COLUMN ordre         integer,
    ADD CONSTRAINT fk_champ_extrait_extraction
        FOREIGN KEY (extraction_id) REFERENCES extraction (id) ON DELETE CASCADE;
CREATE INDEX idx_champ_extrait_extraction_id ON champ_extrait (extraction_id, ordre);
