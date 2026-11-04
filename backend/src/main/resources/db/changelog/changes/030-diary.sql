--liquibase formatted sql
--changeset calorica:030-diary
CREATE TABLE calorica.meal_labels (
    id uuid PRIMARY KEY,
    name varchar(200) NOT NULL UNIQUE,
    position smallint NOT NULL UNIQUE
);
-- Standard MVP meal labels: docs/requirements/domain/product-and-meal-labels.md.
INSERT INTO calorica.meal_labels (id, name, position) VALUES
    ('00000000-0000-4000-8000-000000000006', 'Завтрак', 0),
    ('00000000-0000-4000-8000-000000000007', 'Обед', 1),
    ('00000000-0000-4000-8000-000000000008', 'Ужин', 2),
    ('00000000-0000-4000-8000-000000000009', 'Перекус', 3);
CREATE TABLE calorica.diary_entries (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES calorica.accounts(id),
    product_id uuid NOT NULL REFERENCES calorica.products(id),
    consumed_grams numeric(10,3) NOT NULL CHECK (consumed_grams > 0),
    occurred_at timestamptz NOT NULL CHECK (occurred_at >= timestamptz '0001-01-01 00:00:00+00'
        AND occurred_at < timestamptz '10000-01-01 00:00:00+00')
);
CREATE INDEX diary_entries_owner_time ON calorica.diary_entries(owner_id, occurred_at, id);
CREATE INDEX diary_entries_product ON calorica.diary_entries(product_id);
CREATE TABLE calorica.diary_entry_labels (
    entry_id uuid NOT NULL REFERENCES calorica.diary_entries(id) ON DELETE CASCADE,
    label_id uuid NOT NULL REFERENCES calorica.meal_labels(id) ON DELETE CASCADE,
    PRIMARY KEY (entry_id, label_id)
);
CREATE INDEX diary_entry_labels_label ON calorica.diary_entry_labels(label_id);
-- No nutrition snapshot, original unit or original quantity; history follows the current catalog.
