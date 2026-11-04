--liquibase formatted sql
--changeset calorica:040-composition splitStatements:false
-- Existing manual values survive leaf -> composite -> leaf. New composites need no fictitious nutrition.
ALTER TABLE calorica.products
    ALTER COLUMN manual_calories DROP NOT NULL,
    ALTER COLUMN manual_protein DROP NOT NULL,
    ALTER COLUMN manual_fat DROP NOT NULL,
    ALTER COLUMN manual_carbs DROP NOT NULL,
    ADD COLUMN manual_yield_factor numeric(16,12)
        CHECK (manual_yield_factor > 0 AND manual_yield_factor <= 9999.999999999999),
    ADD CONSTRAINT products_manual_nutrition_complete CHECK (
        (manual_calories IS NULL AND manual_protein IS NULL AND manual_fat IS NULL AND manual_carbs IS NULL)
        OR (manual_calories IS NOT NULL AND manual_protein IS NOT NULL AND manual_fat IS NOT NULL AND manual_carbs IS NOT NULL));

CREATE TABLE calorica.product_components (
    parent_id uuid NOT NULL REFERENCES calorica.products(id),
    child_id uuid NOT NULL REFERENCES calorica.products(id),
    fraction numeric(13,12) NOT NULL CHECK (fraction > 0 AND fraction <= 1),
    yield_factor numeric(16,12) CHECK (yield_factor > 0 AND yield_factor <= 9999.999999999999),
    PRIMARY KEY (parent_id, child_id),
    CHECK (parent_id <> child_id)
);
CREATE INDEX product_components_child ON calorica.product_components(child_id, parent_id);
COMMENT ON TABLE calorica.product_components IS 'Source fractions and optional cooking yields; no recipe masses or calculated nutrition';

-- One authoritative path projection for cards, diary and leaf allocations. UNION ALL preserves
-- contributions through multiple paths. Stream the recursive CTE through a bounded loop BEFORE
-- aggregation, so the 100001st path cannot become a silently truncated successful result.
CREATE FUNCTION calorica.catalog_paths(roots uuid[], viewer uuid)
RETURNS TABLE(root_id uuid, root_ordinal integer, node_id uuid, depth integer, is_leaf boolean, nutrition_weight numeric,
    allocation_weight numeric, effective_yield_factor numeric, yield_source text)
LANGUAGE plpgsql STABLE AS $function$
DECLARE
    row_data record;
    path_count integer := 0;
    seen uuid[] := ARRAY[]::uuid[];
BEGIN
    FOR row_data IN
        WITH RECURSIVE node_settings AS NOT MATERIALIZED (
            SELECT p.id, p.owner_id, p.manual_yield_factor,
                p.manual_calories, p.manual_protein, p.manual_fat, p.manual_carbs,
                count(c.child_id) AS child_count,
                CASE WHEN count(c.child_id) = 0 THEN 1::numeric
                    WHEN count(c.yield_factor) > 0 THEN sum(c.fraction * coalesce(c.yield_factor, 1))
                    ELSE coalesce(p.manual_yield_factor, 1) END AS yield_value,
                CASE WHEN count(c.child_id) = 0 THEN 'DEFAULT'
                    WHEN count(c.yield_factor) > 0 THEN 'INGREDIENTS'
                    WHEN p.manual_yield_factor IS NOT NULL THEN 'MANUAL' ELSE 'DEFAULT' END AS source,
                coalesce(sum(c.fraction), 0) AS fraction_sum
            FROM calorica.products p LEFT JOIN calorica.product_components c ON c.parent_id = p.id
            GROUP BY p.id
        ), walk(root, root_index, node, nutrient, allocation, path, cycle) AS (
            SELECT r.id, r.ordinality::integer, r.id, 1::numeric, 1::numeric, ARRAY[r.id], false
            FROM unnest(roots) WITH ORDINALITY AS r(id, ordinality)
            UNION ALL
            SELECT w.root, w.root_index, c.child_id, w.nutrient * c.fraction / s.yield_value,
                w.allocation * CASE WHEN s.source = 'INGREDIENTS'
                    THEN c.fraction * coalesce(c.yield_factor, 1) / s.yield_value ELSE c.fraction END,
                w.path || c.child_id, c.child_id = ANY(w.path)
            FROM walk w JOIN node_settings s ON s.id = w.node
                JOIN calorica.product_components c ON c.parent_id = w.node
            WHERE NOT w.cycle
        )
        SELECT w.*, s.owner_id, s.child_count, s.yield_value, s.source, s.fraction_sum,
            s.manual_calories, s.manual_protein, s.manual_fat, s.manual_carbs
        FROM walk w LEFT JOIN node_settings s ON s.id = w.node
    LOOP
        path_count := path_count + 1;
        IF path_count > 100000 THEN RAISE EXCEPTION USING ERRCODE = 'PCL01', MESSAGE = 'CALCULATION_LIMIT'; END IF;
        IF row_data.cycle THEN RAISE EXCEPTION USING ERRCODE = 'PCC01', MESSAGE = 'CYCLE_DETECTED'; END IF;
        IF row_data.child_count IS NULL OR (row_data.owner_id IS NOT NULL AND row_data.owner_id <> viewer) THEN
            RAISE EXCEPTION USING ERRCODE = 'PCN01', MESSAGE = 'NOT_FOUND';
        END IF;
        IF NOT row_data.node = ANY(seen) THEN
            seen := array_append(seen, row_data.node);
            IF cardinality(seen) > 10000 THEN RAISE EXCEPTION USING ERRCODE = 'PCL01', MESSAGE = 'CALCULATION_LIMIT'; END IF;
        END IF;
        IF (row_data.child_count > 0 AND (row_data.fraction_sum <> 1 OR row_data.child_count > 100))
            OR (row_data.child_count = 0 AND row_data.manual_calories IS NULL) THEN
            RAISE EXCEPTION USING ERRCODE = 'PCG01', MESSAGE = 'INVALID_COMPOSITION';
        END IF;
        IF row_data.yield_value <= 0 OR
            scale(row_data.nutrient) > 1000 OR scale(row_data.allocation) > 1000 OR
            length(trunc(abs(row_data.nutrient))::text) > 100 OR
            length(trunc(abs(row_data.allocation))::text) > 100 THEN
            RAISE EXCEPTION USING ERRCODE = 'PCL01', MESSAGE = 'CALCULATION_LIMIT';
        END IF;
        root_id := row_data.root; root_ordinal := row_data.root_index;
        node_id := row_data.node; depth := cardinality(row_data.path); is_leaf := row_data.child_count = 0;
        nutrition_weight := row_data.nutrient; allocation_weight := row_data.allocation;
        effective_yield_factor := row_data.yield_value; yield_source := row_data.source;
        RETURN NEXT;
    END LOOP;
END;
$function$;

-- Evaluate each node after all its children. Dividing each path separately would change numeric
-- rounding (e.g. 240/0.75 would become 320.000...00016). The domain operation is SUM(w*child)/Y
-- at each node, and each child's value already contains its own cooking yield.
CREATE FUNCTION calorica.catalog_nutrition(roots uuid[], viewer uuid)
RETURNS TABLE(root_id uuid, calories numeric, protein numeric, fat numeric, carbs numeric,
    effective_yield_factor numeric, yield_source text)
LANGUAGE plpgsql STABLE AS $function$
DECLARE
    node record;
    totals record;
    value numeric;
    cache jsonb := '{}'::jsonb;
    settings jsonb := '{}'::jsonb;
    id uuid;
BEGIN
    FOR node IN
        SELECT t.node_id, max(t.depth) AS last_depth, bool_and(t.is_leaf) AS leaf,
            max(t.effective_yield_factor) AS yield_value, max(t.yield_source) AS source
        FROM calorica.catalog_paths(roots,viewer) t
        GROUP BY t.node_id ORDER BY max(t.depth) DESC, t.node_id
    LOOP
        IF node.leaf THEN
            SELECT p.manual_calories AS calories,p.manual_protein AS protein,p.manual_fat AS fat,p.manual_carbs AS carbs
                INTO totals FROM calorica.products p WHERE p.id=node.node_id;
        ELSE
            SELECT sum(c.fraction * (cache -> c.child_id::text ->> 'calories')::numeric) / node.yield_value AS calories,
                sum(c.fraction * (cache -> c.child_id::text ->> 'protein')::numeric) / node.yield_value AS protein,
                sum(c.fraction * (cache -> c.child_id::text ->> 'fat')::numeric) / node.yield_value AS fat,
                sum(c.fraction * (cache -> c.child_id::text ->> 'carbs')::numeric) / node.yield_value AS carbs
                INTO totals FROM calorica.product_components c WHERE c.parent_id=node.node_id;
        END IF;
        FOREACH value IN ARRAY ARRAY[totals.calories,totals.protein,totals.fat,totals.carbs] LOOP
            IF value IS NULL THEN RAISE EXCEPTION USING ERRCODE='PCG01',MESSAGE='INVALID_COMPOSITION'; END IF;
            IF scale(value) > 1000 OR length(trunc(abs(value))::text) > 100 THEN
                RAISE EXCEPTION USING ERRCODE='PCL01',MESSAGE='CALCULATION_LIMIT';
            END IF;
        END LOOP;
        cache := cache || jsonb_build_object(node.node_id::text,jsonb_build_object(
            'calories',totals.calories,'protein',totals.protein,'fat',totals.fat,'carbs',totals.carbs));
        settings := settings || jsonb_build_object(node.node_id::text,jsonb_build_object('yield',node.yield_value,'source',node.source));
    END LOOP;
    FOREACH id IN ARRAY roots LOOP
        root_id:=id;
        calories:=(cache -> id::text ->> 'calories')::numeric;
        protein:=(cache -> id::text ->> 'protein')::numeric;
        fat:=(cache -> id::text ->> 'fat')::numeric;
        carbs:=(cache -> id::text ->> 'carbs')::numeric;
        effective_yield_factor:=(settings -> id::text ->> 'yield')::numeric;
        yield_source:=settings -> id::text ->> 'source';
        RETURN NEXT;
    END LOOP;
END;
$function$;
