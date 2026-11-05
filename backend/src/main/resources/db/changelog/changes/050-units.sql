--liquibase formatted sql
--changeset calorica:050-units
CREATE TABLE calorica.units (
    code text PRIMARY KEY CHECK (code IN ('g','ml'))
);
INSERT INTO calorica.units(code) VALUES ('g'),('ml');

-- Grams are implicit at factor one for every product; only explicit millilitre conversions are stored.
CREATE TABLE calorica.product_units (
    product_id uuid NOT NULL REFERENCES calorica.products(id),
    unit_code text NOT NULL REFERENCES calorica.units(code) CHECK (unit_code='ml'),
    grams_per_unit numeric(16,12) NOT NULL CHECK (grams_per_unit>0 AND grams_per_unit<=9999.999999999999),
    PRIMARY KEY (product_id,unit_code)
);
COMMENT ON TABLE calorica.product_units IS 'Explicit product conversions to grams; unrelated to cooking yield';
