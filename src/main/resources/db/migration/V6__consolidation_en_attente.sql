-- V6__consolidation_en_attente.sql
-- Index partiel pour le poller de consolidation, qui cherche toutes les 2 s les dossiers dont une demande est en
-- attente (compteur de demande plus grand que le compteur traité) et dont la nouvelle tentative éventuelle est due.
-- L'index ne contient que ces dossiers : il reste minuscule quel que soit le nombre total de dossiers.
CREATE INDEX ix_dossier_consolidation_en_attente
    ON dossier (consolidation_prochaine_tentative_le NULLS FIRST, id)
    WHERE consolidation_demande_seq > consolidation_seq_traitee;
