--liquibase formatted sql
--changeset calorica:020-products
-- T00 contract 1.0.0; independent of accounts. Agent 1 adds owner FK in 025 after 010+020.
CREATE EXTENSION IF NOT EXISTS pg_trgm WITH SCHEMA public;

CREATE FUNCTION calorica.normalize_product_name(value text) RETURNS text
LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE AS $$
    SELECT btrim(regexp_replace(translate(lower(value), 'ёъь', 'е'),
        U&'[[:space:]\00A0\1680\2000-\200A\2028\2029\202F\205F\3000]+', ' ', 'g'))
$$;

CREATE TABLE calorica.products (
    id uuid PRIMARY KEY,
    owner_id uuid,
    name text NOT NULL CHECK (char_length(name) BETWEEN 1 AND 200),
    name_normalized text GENERATED ALWAYS AS (calorica.normalize_product_name(name)) STORED,
    is_deleted boolean NOT NULL DEFAULT false,
    manual_calories numeric(7,2) NOT NULL CHECK (manual_calories BETWEEN 0 AND 99999.99),
    manual_protein numeric(7,2) NOT NULL CHECK (manual_protein BETWEEN 0 AND 99999.99),
    manual_fat numeric(7,2) NOT NULL CHECK (manual_fat BETWEEN 0 AND 99999.99),
    manual_carbs numeric(7,2) NOT NULL CHECK (manual_carbs BETWEEN 0 AND 99999.99)
);
COMMENT ON TABLE calorica.products IS 'Single product entity; manual nutrition per 100 g, never persisted calculated totals';
CREATE INDEX products_visible_owner ON calorica.products(owner_id, id) WHERE NOT is_deleted;
CREATE INDEX products_visible_name_prefix ON calorica.products(name_normalized text_pattern_ops) WHERE NOT is_deleted;
CREATE INDEX products_visible_name_trgm ON calorica.products USING gin(name_normalized public.gin_trgm_ops) WHERE NOT is_deleted;

-- Source: USDA FoodData Central, SR Legacy final release April 2018.
-- https://fdc.nal.usda.gov/download-datasets/
-- https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_json_2018-04.zip
-- Archive SHA256 0fe8ae486a2c8eb42cb96413f058deb51863a46c8fb8eeb4b1fb45006dd338ef
-- Each row copies nutrient IDs 1008 (kcal), 1003/1004/1005 (protein/fat/carbs g), per 100 g.
-- Russian names retain preparation state. Source IDs/descriptions are comments, not UI fields.
-- These are reference food records, not fictitious calculated composite products.

-- FDC 173410; NDB 1001; Butter, salted
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('f35f5b9c-ee2a-5355-b47a-d4ce083b12ea', NULL, 'Масло сливочное солёное', 717, 0.85, 81.1, 0.06);

-- FDC 172217; NDB 1211; Milk, whole, 3.25% milkfat, without added vitamin A and vitamin D
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('04c03072-43b8-5e8c-8bb6-206a8eb89888', NULL, 'Молоко цельное 3,25%', 61.0, 3.15, 3.27, 4.78);

-- FDC 171284; NDB 1116; Yogurt, plain, whole milk
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('98bd37c6-ab5e-5892-a785-2faa85c60975', NULL, 'Йогурт натуральный цельномолочный', 61.0, 3.47, 3.25, 4.66);

-- FDC 173414; NDB 1009; Cheese, cheddar (Includes foods for USDA's Food Distribution Program)
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('2094002a-0b7d-5f15-af26-baffab1c9a0e', NULL, 'Сыр чеддер', 403, 22.9, 33.3, 3.37);

-- FDC 171287; NDB 1123; Egg, whole, raw, fresh
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('a314398a-2fd0-5f59-88a4-8fae89684659', NULL, 'Яйцо куриное сырое', 143, 12.6, 9.51, 0.72);

-- FDC 171077; NDB 5062; Chicken, broiler or fryers, breast, skinless, boneless, meat only, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('8982b1bf-c905-5518-b917-2fc2f0472039', NULL, 'Куриная грудка без кожи (сырая)', 120, 22.5, 2.62, 0.0);

-- FDC 175167; NDB 15236; Fish, salmon, Atlantic, farmed, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('e5bfb84c-c441-5ec8-a2f5-794428feef00', NULL, 'Лосось атлантический (сырой, выращенный)', 208, 20.4, 13.4, 0.0);

-- FDC 171955; NDB 15015; Fish, cod, Atlantic, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('115a06eb-e982-5e67-bf3f-3b77d32da52f', NULL, 'Треска атлантическая (сырая)', 82.0, 17.8, 0.67, 0.0);

-- FDC 169756; NDB 20444; Rice, white, long-grain, regular, raw, unenriched
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('7632811b-bd64-5e74-8578-28c84b3dedee', NULL, 'Рис белый длиннозёрный (сухой)', 365, 7.13, 0.66, 80.0);

-- FDC 170685; NDB 20009; Buckwheat groats, roasted, dry
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('1e332fa9-7b45-5236-9eef-3a31a45482c6', NULL, 'Гречневая крупа обжаренная (сухая)', 346, 11.7, 2.71, 75.0);

-- FDC 173904; NDB 8120; Cereals, oats, regular and quick, not fortified, dry
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('e3eb962a-9423-5493-beb8-fcd74e8215a9', NULL, 'Овсяные хлопья (сухие)', 379, 13.2, 6.52, 67.7);

-- FDC 169736; NDB 20120; Pasta, dry, enriched
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('acf751b9-6d07-5906-ad6f-7f81483c1fca', NULL, 'Макароны (сухие, обогащённые)', 371, 13.0, 1.51, 74.7);

-- FDC 169761; NDB 20481; Wheat flour, white, all-purpose, unenriched
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('d782b4f0-64b5-5c9d-9e57-0fec672dd06c', NULL, 'Мука пшеничная белая', 364, 10.3, 0.98, 76.3);

-- FDC 172684; NDB 18060; Bread, rye
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('a58e84ee-fff9-5cbf-9526-6fce108df6b9', NULL, 'Хлеб ржаной', 259, 8.5, 3.3, 48.3);

-- FDC 172688; NDB 18075; Bread, whole-wheat, commercially prepared
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('6f7f76e3-e7bd-5051-afbc-9e30c4dcea23', NULL, 'Хлеб цельнозерновой пшеничный', 252, 12.4, 3.5, 42.7);

-- FDC 170026; NDB 11352; Potatoes, flesh and skin, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('b94f68a5-fa4e-5d4e-ab9a-e3e7b83ec6d4', NULL, 'Картофель с кожурой (сырой)', 77.0, 2.05, 0.09, 17.5);

-- FDC 170393; NDB 11124; Carrots, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('0633a804-252d-5115-b7da-89f73c4022c2', NULL, 'Морковь (сырая)', 41.0, 0.93, 0.24, 9.58);

-- FDC 169145; NDB 11080; Beets, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('c275b5f1-378e-5a77-87ed-28fe080315e5', NULL, 'Свёкла (сырая)', 43.0, 1.61, 0.17, 9.56);

-- FDC 169975; NDB 11109; Cabbage, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('c466ca3e-be42-53d1-b0c4-695a8f2643f8', NULL, 'Капуста белокочанная (сырая)', 25.0, 1.28, 0.1, 5.8);

-- FDC 170000; NDB 11282; Onions, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('bf9c8f47-4ee2-5438-9a78-e16db04f6a20', NULL, 'Лук репчатый (сырой)', 40.0, 1.1, 0.1, 9.34);

-- FDC 169230; NDB 11215; Garlic, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('b6c5377d-9640-5212-9610-d5ebba0df515', NULL, 'Чеснок (сырой)', 149, 6.36, 0.5, 33.1);

-- FDC 170457; NDB 11529; Tomatoes, red, ripe, raw, year round average
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('2875bf6a-e6b0-593a-8460-5ff7e4be8c3d', NULL, 'Помидоры красные (сырые)', 18.0, 0.88, 0.2, 3.89);

-- FDC 168409; NDB 11205; Cucumber, with peel, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('a2076a24-b803-5596-9777-0d9b240e0b18', NULL, 'Огурец с кожурой (сырой)', 15.0, 0.65, 0.11, 3.63);

-- FDC 170108; NDB 11821; Peppers, sweet, red, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('d2b6953a-e75b-553f-adc1-6ee3290e5240', NULL, 'Перец сладкий красный (сырой)', 26.0, 0.99, 0.3, 6.03);

-- FDC 170379; NDB 11090; Broccoli, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('edf664f9-c816-50ac-8015-89d89ee6b9ea', NULL, 'Брокколи (сырая)', 34.0, 2.82, 0.37, 6.64);

-- FDC 169986; NDB 11135; Cauliflower, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('9d43b887-15f2-5e63-b94c-009c83cd85f5', NULL, 'Цветная капуста (сырая)', 25.0, 1.92, 0.28, 4.97);

-- FDC 168462; NDB 11457; Spinach, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('24d28009-b040-5664-bc6f-9f89bf4de4b3', NULL, 'Шпинат (сырой)', 23.0, 2.86, 0.39, 3.63);

-- FDC 169249; NDB 11253; Lettuce, green leaf, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('5b3ef11b-1461-535d-893a-1ef17644dbd9', NULL, 'Салат листовой зелёный (сырой)', 15.0, 1.36, 0.15, 2.87);

-- FDC 169251; NDB 11260; Mushrooms, white, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('24022710-6fa4-5624-950b-bd69c30bbd2d', NULL, 'Шампиньоны (сырые)', 22.0, 3.09, 0.34, 3.26);

-- FDC 171688; NDB 9003; Apples, raw, with skin (Includes foods for USDA's Food Distribution Program)
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('b8b7ce7e-856b-5957-a5a1-ee9a01f23919', NULL, 'Яблоко с кожурой', 52.0, 0.26, 0.17, 13.8);

-- FDC 173944; NDB 9040; Bananas, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('8efea28f-a3ea-5772-95e3-2df4285856ea', NULL, 'Банан', 89.0, 1.09, 0.33, 22.8);

-- FDC 169097; NDB 9200; Oranges, raw, all commercial varieties
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('7f9d0d09-bab0-5c91-aa4b-b3c5dc4b1d3f', NULL, 'Апельсин', 47.0, 0.94, 0.12, 11.8);

-- FDC 169118; NDB 9252; Pears, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('90adcfaa-764f-514e-830d-60bbd8d47509', NULL, 'Груша', 57.0, 0.36, 0.14, 15.2);

-- FDC 174683; NDB 9132; Grapes, red or green (European type, such as Thompson seedless), raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('95069e05-df04-5421-a4f0-9c56840e7146', NULL, 'Виноград красный или зелёный', 69.0, 0.72, 0.16, 18.1);

-- FDC 167762; NDB 9316; Strawberries, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('8d540295-efaa-54aa-9d9d-70dbf74f4a30', NULL, 'Клубника', 32.0, 0.67, 0.3, 7.68);

-- FDC 171711; NDB 9050; Blueberries, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('ff390262-1541-5201-bb13-b54222e1fb80', NULL, 'Голубика', 57.0, 0.74, 0.33, 14.5);

-- FDC 167746; NDB 9150; Lemons, raw, without peel
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('1a3dd9d1-3a5a-5010-a962-16d95098a206', NULL, 'Лимон без кожуры', 29.0, 1.1, 0.3, 9.32);

-- FDC 171705; NDB 9037; Avocados, raw, all commercial varieties
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('2526c102-c755-553f-9708-84592901e416', NULL, 'Авокадо', 160, 2.0, 14.7, 8.53);

-- FDC 170567; NDB 12061; Nuts, almonds
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('aa1d122f-0df9-5262-9460-bbdd9c37040e', NULL, 'Миндаль', 579, 21.2, 49.9, 21.6);

-- FDC 170187; NDB 12155; Nuts, walnuts, english
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('8281f0df-3dc5-59c4-b4e2-7e1f69a7d17f', NULL, 'Грецкий орех', 654, 15.2, 65.2, 13.7);

-- FDC 172430; NDB 16087; Peanuts, all types, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('30b04dcf-9f09-5aa4-8024-db67c8472d7c', NULL, 'Арахис (сырой)', 567, 25.8, 49.2, 16.1);

-- FDC 170562; NDB 12036; Seeds, sunflower seed kernels, dried
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('9164d6aa-3dbf-5f8f-86bb-6789c635243e', NULL, 'Семечки подсолнечника очищенные (сушёные)', 584, 20.8, 51.5, 20.0);

-- FDC 172420; NDB 16069; Lentils, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('0336a0ec-c0fe-5dcd-b599-80ee1c083360', NULL, 'Чечевица (сухая)', 352, 24.6, 1.06, 63.4);

-- FDC 173756; NDB 16056; Chickpeas (garbanzo beans, bengal gram), mature seeds, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('76678b13-fde3-55eb-9fab-7dfe959d826c', NULL, 'Нут (сухой)', 378, 20.5, 6.04, 63.0);

-- FDC 173744; NDB 16032; Beans, kidney, red, mature seeds, raw
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('6e0b1524-a4e9-5105-b31a-48576c29e189', NULL, 'Фасоль красная (сухая)', 337, 22.5, 1.06, 61.3);

-- FDC 171413; NDB 4053; Oil, olive, salad or cooking
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('c370bd57-c6cc-5596-934a-35a6cb7c745d', NULL, 'Масло оливковое', 884, 0.0, 100, 0.0);

-- FDC 171025; NDB 4506; Oil, sunflower, linoleic, (approx. 65%)
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('3b2fc505-15ca-5c77-b5c7-6f3287978479', NULL, 'Масло подсолнечное', 884, 0.0, 100, 0.0);

-- FDC 169655; NDB 19335; Sugars, granulated
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('0cbab5f7-26c4-50d8-ab4c-b521fc86f52a', NULL, 'Сахар-песок', 387, 0.0, 0.0, 100);

-- FDC 169640; NDB 19296; Honey
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('ca6b45ab-8746-501d-9175-c71cfc735a75', NULL, 'Мёд', 304, 0.3, 0.0, 82.4);

-- FDC 170273; NDB 19904; Chocolate, dark, 70-85% cacao solids
INSERT INTO calorica.products(id, owner_id, name, manual_calories, manual_protein, manual_fat, manual_carbs)
VALUES ('51b174bb-6297-5b92-b5ae-3dcfa947932e', NULL, 'Шоколад тёмный 70–85% какао', 598, 7.79, 42.6, 45.9);

-- No destructive rollback: the previous compatible app leaves product rows and references intact.
