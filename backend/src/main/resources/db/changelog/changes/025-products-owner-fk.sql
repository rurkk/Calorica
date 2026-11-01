--liquibase formatted sql
--changeset calorica:025-products-owner-fk
-- Agent 1 integration after the independently owned 010 and 020 changesets.
ALTER TABLE calorica.products
    ADD CONSTRAINT products_owner_account_fk
    FOREIGN KEY (owner_id) REFERENCES calorica.accounts(id);
-- Base products retain NULL owner. No cascade deletion of products or user data.
