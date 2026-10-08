CREATE MATERIALIZED VIEW mv_home_stats AS
WITH ph AS (
  SELECT year, count(*) AS candidacies, count(DISTINCT person_id) AS people
  FROM politician_history GROUP BY year
),
co AS (SELECT year, count(*) AS n FROM campaign_org GROUP BY year),
sm AS (SELECT year, count(*) AS n FROM social_media GROUP BY year),
d AS (SELECT year, coalesce(sum(amount_cents), 0) AS s FROM campaign_donation GROUP BY year),
e AS (SELECT year, coalesce(sum(amount_cents), 0) AS s, count(*) AS n FROM campaign_expense GROUP BY year),
years AS (
  SELECT year FROM ph UNION SELECT year FROM co UNION SELECT year FROM sm
  UNION SELECT year FROM d UNION SELECT year FROM e
)
SELECT y.year,
       coalesce(ph.people, 0) AS people,
       coalesce(ph.candidacies, 0) AS candidacies,
       coalesce(co.n, 0) AS campaign_orgs,
       coalesce(sm.n, 0) AS social_media,
       coalesce(d.s, 0)::bigint AS donations_cents,
       coalesce(e.s, 0)::bigint AS expenses_cents,
       coalesce(e.n, 0) AS expenses_count
FROM years y
LEFT JOIN ph ON ph.year = y.year
LEFT JOIN co ON co.year = y.year
LEFT JOIN sm ON sm.year = y.year
LEFT JOIN d ON d.year = y.year
LEFT JOIN e ON e.year = y.year;
CREATE UNIQUE INDEX ux_mv_home_stats ON mv_home_stats (year);

CREATE MATERIALIZED VIEW mv_supplier_year AS
SELECT year, supplier_cpf_cnpj AS cnpj,
       max(coalesce(supplier_name_rfb, supplier_name)) AS name,
       coalesce(sum(amount_cents), 0)::bigint AS total_cents,
       count(*) AS payment_count,
       count(DISTINCT tse_candidacy_id) AS candidacy_count
FROM campaign_expense
WHERE supplier_company_id IS NOT NULL
GROUP BY year, supplier_cpf_cnpj;
CREATE UNIQUE INDEX ux_mv_supplier_year ON mv_supplier_year (year, cnpj);
CREATE INDEX ix_mv_supplier_year_total ON mv_supplier_year (year, total_cents DESC);

CREATE MATERIALIZED VIEW mv_asset_totals AS
SELECT person_id, year, count(*) AS asset_count,
       coalesce(sum(value_cents), 0)::bigint AS total_cents
FROM declared_assets
WHERE person_id IS NOT NULL
GROUP BY person_id, year;
CREATE UNIQUE INDEX ux_mv_asset_totals ON mv_asset_totals (person_id, year);
CREATE INDEX ix_mv_asset_totals_year ON mv_asset_totals (year, total_cents DESC);

CREATE MATERIALIZED VIEW mv_asset_latest AS
SELECT DISTINCT ON (person_id) person_id, year, asset_count, total_cents
FROM mv_asset_totals
ORDER BY person_id, year DESC;
CREATE UNIQUE INDEX ux_mv_asset_latest ON mv_asset_latest (person_id);
CREATE INDEX ix_mv_asset_latest_total ON mv_asset_latest (total_cents DESC);

CREATE MATERIALIZED VIEW mv_asset_growth AS
WITH bounds AS (
  SELECT person_id, min(year) AS first_year, max(year) AS last_year
  FROM mv_asset_totals GROUP BY person_id HAVING count(*) >= 2
)
SELECT b.person_id, b.first_year, b.last_year,
       f.total_cents AS first_cents, l.total_cents AS last_cents,
       (l.total_cents - f.total_cents) AS growth_cents
FROM bounds b
JOIN mv_asset_totals f ON f.person_id = b.person_id AND f.year = b.first_year
JOIN mv_asset_totals l ON l.person_id = b.person_id AND l.year = b.last_year;
CREATE UNIQUE INDEX ux_mv_asset_growth ON mv_asset_growth (person_id);
CREATE INDEX ix_mv_asset_growth_value ON mv_asset_growth (growth_cents DESC);

CREATE MATERIALIZED VIEW mv_category_expense AS
SELECT DISTINCT ce.id AS expense_id, ce.campaign_org_id, ce.year, ce.amount_cents, c.category
FROM campaign_expense ce
JOIN (VALUES
  ('CANETA', '%CANETA%'), ('LAPIS', '%LAPIS%'), ('LAPIS', '%LÁPIS%'),
  ('LAPISEIRA', '%LAPISEIRA%'), ('BORRACHA', '%BORRACHA%'), ('APONTADOR', '%APONTADOR%'),
  ('ADESIVO', '%ADESIVO%'), ('CRACHA', '%CRACHA%'), ('CRACHA', '%CRACHÁ%'),
  ('ETIQUETA', '%ETIQUETA%'), ('CLIPS', '%CLIPS%'), ('GRAMPO', '%GRAMPO%'),
  ('GRAMPEADOR', '%GRAMPEADOR%'), ('REGUA', '%REGUA%'), ('REGUA', '%RÉGUA%'),
  ('BLOCO DE ANOTA', '%BLOCO DE ANOTA%'), ('ENVELOPE', '%ENVELOPE%'),
  ('MARCADOR DE TEXTO', '%MARCADOR DE TEXTO%'), ('PRANCHETA', '%PRANCHETA%'),
  ('PERFURADOR', '%PERFURADOR%'), ('ELASTICO', '%ELASTICO%'), ('ELASTICO', '%ELÁSTICO%')
) AS c(category, pattern) ON ce.description ILIKE c.pattern
WHERE ce.campaign_org_id IS NOT NULL;
CREATE UNIQUE INDEX ux_mv_category_expense ON mv_category_expense (expense_id, category);
CREATE INDEX ix_mv_category_expense_cat ON mv_category_expense (category, year);

CREATE MATERIALIZED VIEW mv_person_revenue AS
SELECT co.person_id, d.year, coalesce(sum(d.amount_cents), 0)::bigint AS revenue_cents
FROM campaign_donation d JOIN campaign_org co ON co.id = d.campaign_org_id
WHERE co.person_id IS NOT NULL
GROUP BY co.person_id, d.year;
CREATE UNIQUE INDEX ux_mv_person_revenue ON mv_person_revenue (person_id, year);

CREATE MATERIALIZED VIEW mv_earmark_company_payments AS
WITH agg AS (
  SELECT earmark_code, beneficiary_doc, max(beneficiary_name) AS beneficiary_name,
         sum(amount_cents)::bigint AS amount_cents
  FROM parliamentary_earmark_beneficiary
  WHERE beneficiary_type ILIKE 'Pessoa Jur%' AND earmark_code <> 'Sem informação'
    AND beneficiary_doc <> ALL (ARRAY['00000000000191', '00360305000104', '00038166000105'])
    AND NOT (beneficiary_name ILIKE ANY (ARRAY[
      'MUNICIPIO D%', 'ESTADO D%', 'PREFEITURA%', 'GOVERNO D%', 'CAMARA MUNICIPAL%',
      'ASSEMBLEIA LEGISLATIVA%', 'UNIAO FEDERAL%', '%MINISTERIO%', '%SECRETARIA%',
      '%FUNDO ESTADUAL%', '%FUNDO MUNICIPAL%', '%FUNDO NACIONAL%', '%FUNDO ESPECIAL%',
      '%FUNDO DE SAUDE%', '%DEPARTAMENTO DE ESTRADAS%', 'CONSORCIO INTERFEDERATIVO%',
      'CONSORCIO PUBLICO%', 'CONSORCIO INTERMUNICIPAL%']))
  GROUP BY earmark_code, beneficiary_doc
),
author AS (
  SELECT DISTINCT ON (earmark_code) earmark_code, author_name, author_person_id, year
  FROM parliamentary_earmark ORDER BY earmark_code, id
)
SELECT agg.earmark_code, agg.beneficiary_doc AS company_cnpj, agg.beneficiary_name AS company_name,
       agg.amount_cents, author.author_name, author.author_person_id, author.year
FROM agg LEFT JOIN author ON author.earmark_code = agg.earmark_code;
CREATE UNIQUE INDEX ux_mv_earmark_company ON mv_earmark_company_payments (earmark_code, company_cnpj);
CREATE INDEX ix_mv_earmark_company_amount ON mv_earmark_company_payments (amount_cents DESC);
CREATE INDEX ix_mv_earmark_company_name_trgm
    ON mv_earmark_company_payments USING gin (company_name gin_trgm_ops);
CREATE INDEX ix_mv_earmark_author_name_trgm
    ON mv_earmark_company_payments USING gin (author_name gin_trgm_ops);
