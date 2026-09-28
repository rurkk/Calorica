--liquibase formatted sql
--changeset calorica:001-foundation
-- Infrastructure only. Domain tables and sourced seed data belong to future changesets.
CREATE SCHEMA calorica;
COMMENT ON SCHEMA calorica IS 'Calorica domain; explicit SQL, no persisted calculated nutrition totals';
-- No automatic rollback: deploying the previous compatible application preserves the schema.
