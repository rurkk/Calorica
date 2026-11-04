--liquibase formatted sql
--changeset calorica:060-product-labels
CREATE TABLE calorica.product_labels (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200 AND name = btrim(name))
);
CREATE TABLE calorica.product_label_links (
    product_id uuid NOT NULL REFERENCES calorica.products(id) ON DELETE CASCADE,
    label_id uuid NOT NULL REFERENCES calorica.product_labels(id) ON DELETE CASCADE,
    PRIMARY KEY (product_id, label_id)
);
CREATE INDEX product_label_links_label ON calorica.product_label_links(label_id, product_id);
COMMENT ON TABLE calorica.product_label_links IS 'Retained across kind transitions; only allocated leaves contribute to label groups';

INSERT INTO calorica.product_labels(id,name) VALUES
    ('20000000-0000-0000-0000-000000000001','Молочные продукты'),
    ('20000000-0000-0000-0000-000000000002','Мясо'),
    ('20000000-0000-0000-0000-000000000003','Рыба и морепродукты'),
    ('20000000-0000-0000-0000-000000000004','Яйца'),
    ('20000000-0000-0000-0000-000000000005','Крупы и зерновые'),
    ('20000000-0000-0000-0000-000000000006','Овощи и фрукты'),
    ('20000000-0000-0000-0000-000000000007','Бобовые'),
    ('20000000-0000-0000-0000-000000000008','Сладкое');
