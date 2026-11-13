-- V4__suppression_type_sinistre.sql
-- Suppression du champ legacy dossier.type_sinistre, sans utilité : la nature du sinistre est un fait du dossier,
-- établi par la consolidation à partir des pièces (sinistre.nature), pas une saisie à la création du dossier.

ALTER TABLE dossier DROP COLUMN type_sinistre;
