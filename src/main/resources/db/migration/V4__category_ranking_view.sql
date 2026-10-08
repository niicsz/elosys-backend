CREATE MATERIALIZED VIEW mv_category_ranking AS
WITH base AS (
  SELECT m.expense_id, m.category, m.year, m.amount_cents, co.person_id, co.office, co.state
  FROM mv_category_expense m
  JOIN campaign_org co ON co.id = m.campaign_org_id
  WHERE co.person_id IS NOT NULL
),
any_category AS (
  SELECT DISTINCT ON (expense_id) expense_id, '*'::text AS category, year, amount_cents,
         person_id, office, state
  FROM base ORDER BY expense_id
),
expense_rows AS (
  SELECT * FROM base
  UNION ALL
  SELECT * FROM any_category
),
spend AS (
  SELECT person_id, category,
         CASE WHEN GROUPING(year) = 1 THEN 0 ELSE year END AS year,
         sum(amount_cents)::bigint AS category_cents, count(*) AS category_count,
         max(office) AS office, max(state) AS state
  FROM expense_rows
  GROUP BY GROUPING SETS ((person_id, category, year), (person_id, category))
),
revenue AS (
  SELECT person_id, year, revenue_cents FROM mv_person_revenue
  UNION ALL
  SELECT person_id, 0, sum(revenue_cents)::bigint FROM mv_person_revenue GROUP BY person_id
),
share AS (
  SELECT s.*, coalesce(r.revenue_cents, 0) AS revenue_cents,
         CASE WHEN coalesce(r.revenue_cents, 0) > 0
              THEN s.category_cents * 100.0 / r.revenue_cents END AS share_pct
  FROM spend s
  LEFT JOIN revenue r ON r.person_id = s.person_id AND r.year = s.year
),
peer_group AS (
  SELECT category, year, office, state, sum(share_pct) AS sum_share, count(*) AS n
  FROM share WHERE share_pct IS NOT NULL
  GROUP BY category, year, office, state
)
SELECT s.category, s.year, s.person_id, s.office, s.state,
       s.category_cents, s.category_count, s.revenue_cents,
       s.share_pct::float8 AS share_pct,
       (CASE
          WHEN s.share_pct IS NOT NULL AND pg.n > 1 THEN (pg.sum_share - s.share_pct) / (pg.n - 1)
          WHEN s.share_pct IS NULL AND pg.n > 0 THEN pg.sum_share / pg.n
        END)::float8 AS peer_avg_share_pct,
       (CASE WHEN s.share_pct IS NOT NULL THEN coalesce(pg.n, 1) - 1
             ELSE coalesce(pg.n, 0) END)::int AS peer_count,
       row_number() OVER (PARTITION BY s.category, s.year
                          ORDER BY s.category_cents DESC, s.person_id) AS rank
FROM share s
LEFT JOIN peer_group pg
  ON pg.category = s.category AND pg.year = s.year AND pg.office = s.office AND pg.state = s.state;

CREATE UNIQUE INDEX ux_mv_category_ranking ON mv_category_ranking (category, year, person_id);
CREATE INDEX ix_mv_category_ranking_rank ON mv_category_ranking (category, year, rank);
