package com.binitech.elosys.adapters.outbound.persistence.read;

import com.binitech.elosys.application.ports.outbound.ReadModelPorts.RankingReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.SignalReadPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class RankingReadJdbcAdapter implements RankingReadPort {
  private final ReadSupport db;
  private final SignalReadPort signals;

  public RankingReadJdbcAdapter(ReadSupport db, @Lazy SignalReadPort signals) {
    this.db = db;
    this.signals = signals;
  }

  @Override
  @Cacheable(cacheNames = "expense-years")
  public List<Long> expenseYears() {
    return db.jdbc()
        .sql("SELECT year FROM mv_home_stats WHERE expenses_count > 0 ORDER BY year DESC")
        .query(Long.class)
        .list();
  }

  @Override
  @Cacheable(cacheNames = "asset-years")
  public List<Long> assetYears() {
    return db.jdbc()
        .sql("SELECT DISTINCT year FROM mv_asset_totals ORDER BY year DESC")
        .query(Long.class)
        .list();
  }

  @Override
  @Cacheable(cacheNames = "assets-ranking")
  public Map<String, Object> assetsRanking(Integer year, boolean ascending, int limit, int offset) {
    String source =
        year != null ? "mv_asset_totals a WHERE a.year = ?" : "mv_asset_latest a WHERE true";
    Object[] yearArgs = year != null ? new Object[] {year} : new Object[] {};
    long total = db.count("SELECT count(*) FROM " + source, yearArgs);
    List<Object> args = new ArrayList<>();
    if (year != null) args.add(year);
    if (year != null) args.add(year);
    args.add(limit);
    args.add(offset);
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT a.person_id AS "personId", p.canonical_name AS name,
                   a.asset_count AS "assetCount", a.total_cents AS "assetTotalCents",
                   ph.office, ph.party_abbr AS "partyAbbr", ph.state, ph.year
            FROM (SELECT * FROM %s ORDER BY a.total_cents %s, a.person_id LIMIT ? OFFSET ?) a
            JOIN people p ON p.id = a.person_id
            LEFT JOIN LATERAL (
              SELECT ph2.office, ph2.party_abbr, ph2.state, ph2.year FROM politician_history ph2
              WHERE ph2.person_id = a.person_id %s
              ORDER BY ph2.year DESC, ph2.id DESC LIMIT 1
            ) ph ON true
            ORDER BY a.total_cents %s, a.person_id
            """
                .formatted(
                    source,
                    ascending ? "ASC" : "DESC",
                    year != null ? "AND ph2.year = ?" : "",
                    ascending ? "ASC" : "DESC"),
            reorder(args, year != null));
    withPhotos(rows);
    return ReadSupport.page(rows, total);
  }

  private static Object[] reorder(List<Object> args, boolean hasYear) {
    if (!hasYear) return args.toArray();
    return new Object[] {args.get(0), args.get(2), args.get(3), args.get(1)};
  }

  @Override
  @Cacheable(cacheNames = "assets-growth")
  public Map<String, Object> assetsGrowth(boolean ascending, int limit, int offset) {
    String dir = ascending ? "ASC" : "DESC";
    long total = db.count("SELECT count(*) FROM mv_asset_growth");
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT g.person_id AS "personId", p.canonical_name AS name, ph.office,
                   ph.party_abbr AS "partyAbbr", ph.state, g.first_year AS "firstYear",
                   g.last_year AS "lastYear", g.first_cents AS "firstCents",
                   g.last_cents AS "lastCents", g.growth_cents AS "growthCents"
            FROM (SELECT * FROM mv_asset_growth ORDER BY growth_cents %s, person_id
                  LIMIT ? OFFSET ?) g
            JOIN people p ON p.id = g.person_id
            LEFT JOIN LATERAL (
              SELECT ph2.office, ph2.party_abbr, ph2.state FROM politician_history ph2
              WHERE ph2.person_id = g.person_id ORDER BY ph2.year DESC, ph2.id DESC LIMIT 1
            ) ph ON true
            ORDER BY g.growth_cents %s, g.person_id
            """
                .formatted(dir, dir),
            limit,
            offset);
    for (Map<String, Object> r : rows) {
      long first = ReadSupport.longOf(r.get("firstCents"));
      long last = ReadSupport.longOf(r.get("lastCents"));
      r.put("growthPct", first > 0 ? ((double) (last - first) / first) * 100 : null);
    }
    withPhotos(rows);
    return ReadSupport.page(rows, total);
  }

  @Override
  @Cacheable(cacheNames = "top-suppliers")
  public List<Map<String, Object>> topSuppliers(Integer year, int limit) {
    List<Map<String, Object>> rows =
        year != null
            ? db.list(
                """
                SELECT cnpj, name, total_cents AS "totalCents", payment_count AS "paymentCount",
                       candidacy_count AS "candidacyCount"
                FROM mv_supplier_year WHERE year = ? ORDER BY total_cents DESC LIMIT ?
                """,
                year,
                limit)
            : db.list(
                """
                SELECT cnpj, max(name) AS name, sum(total_cents) AS "totalCents",
                       sum(payment_count) AS "paymentCount",
                       sum(candidacy_count) AS "candidacyCount"
                FROM mv_supplier_year GROUP BY cnpj ORDER BY "totalCents" DESC LIMIT ?
                """,
                limit);
    for (Map<String, Object> r : rows)
      if (r.get("name") == null) r.put("name", "(nome não disponível)");
    return rows;
  }

  @Override
  public List<String> expenseCategories() {
    return List.copyOf(ReadSupport.EXPENSE_CATEGORY_SPELLINGS.keySet());
  }

  @Override
  @Cacheable(cacheNames = "expense-category-ranking")
  public Map<String, Object> expenseCategoryRanking(
      String category, Integer year, int limit, int offset) {
    if (category != null && !ReadSupport.EXPENSE_CATEGORY_SPELLINGS.containsKey(category))
      return ReadSupport.page(List.of(), 0);
    String c = category == null ? "*" : category;
    int y = year == null ? 0 : year;
    long total =
        db.count("SELECT count(*) FROM mv_category_ranking WHERE category = ? AND year = ?", c, y);
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT r.person_id AS "personId", p.canonical_name AS name, r.office, r.state,
                   r.category_cents AS "categoryCents", r.category_count AS "categoryCount",
                   r.revenue_cents AS "revenueCents", r.share_pct AS "sharePct",
                   r.peer_avg_share_pct AS "peerAvgSharePct", r.peer_count AS "peerCount"
            FROM mv_category_ranking r
            JOIN people p ON p.id = r.person_id
            WHERE r.category = ? AND r.year = ? AND r.rank > ? AND r.rank <= ?
            ORDER BY r.rank
            """,
            c,
            y,
            offset,
            (long) offset + limit);
    withPhotos(rows);
    return ReadSupport.page(rows, total);
  }

  @Override
  @Cacheable(cacheNames = "home-stats")
  public Map<String, Object> homeStats(Integer year) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (year != null) {
      Map<String, Object> r = db.one("SELECT * FROM mv_home_stats WHERE year = ?", year);
      out.put("people", r == null ? 0L : r.get("people"));
      out.put("candidacies", r == null ? 0L : r.get("candidacies"));
      out.put("campaignOrgs", r == null ? 0L : r.get("campaign_orgs"));
      out.put("socialMedia", r == null ? 0L : r.get("social_media"));
      out.put("donationsTotalCents", r == null ? 0L : r.get("donations_cents"));
      out.put("expensesTotalCents", r == null ? 0L : r.get("expenses_cents"));
      out.put("years", String.valueOf(year));
      return out;
    }
    Map<String, Object> t =
        db.one(
            """
            SELECT coalesce(sum(candidacies), 0) AS candidacies,
                   coalesce(sum(campaign_orgs), 0) AS campaign_orgs,
                   coalesce(sum(social_media), 0) AS social_media,
                   coalesce(sum(donations_cents), 0) AS donations_cents,
                   coalesce(sum(expenses_cents), 0) AS expenses_cents,
                   min(year) FILTER (WHERE candidacies > 0) AS lo,
                   max(year) FILTER (WHERE candidacies > 0) AS hi
            FROM mv_home_stats
            """);
    out.put("people", db.count("SELECT count(*) FROM people"));
    out.put("candidacies", t.get("candidacies"));
    out.put("campaignOrgs", t.get("campaign_orgs"));
    out.put("socialMedia", t.get("social_media"));
    out.put("donationsTotalCents", t.get("donations_cents"));
    out.put("expensesTotalCents", t.get("expenses_cents"));
    out.put(
        "years",
        t.get("lo") != null && t.get("hi") != null ? t.get("lo") + "–" + t.get("hi") : "—");
    return out;
  }

  @Override
  @Cacheable(cacheNames = "sidebar-counts")
  public Map<String, Object> sidebarCounts() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("circularDonations", signals.circularSummary().get("total"));
    out.put("supplierPartner", signals.supplierPartnerSummary().get("total"));
    out.put("aiReview", signals.aiReviewSummary().get("total"));
    out.put("discourse", signals.discourseSummary().get("total"));
    out.put("disproportionateExpense", signals.disproportionateExpenseCount());
    return out;
  }

  private void withPhotos(List<Map<String, Object>> rows) {
    Map<Long, String> photos = db.photoUrls(ReadSupport.longs(rows, "personId"));
    for (Map<String, Object> r : rows)
      r.put("photoUrl", photos.get(ReadSupport.longOf(r.get("personId"))));
  }
}
