package com.binitech.elosys.adapters.outbound.persistence.read;

import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.AI_REVIEW_COLUMNS;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.AI_REVIEW_JOIN;

import com.binitech.elosys.application.ports.outbound.ReadModelPorts.DiscourseFilter;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.SignalReadPort;
import com.binitech.elosys.domain.SourceValues;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SignalReadJdbcAdapter implements SignalReadPort {
  private static final String CIRCULAR_RULE = "circular_donations";
  private static final Map<String, String> CIRCULAR_SORT =
      Map.of(
          "severity", "CASE s.severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END, s.id",
          "amount", "coalesce(s.amount_cents, 0) DESC, s.id",
          "path_length", "coalesce(s.path_length, 0) ASC, coalesce(s.amount_cents, 0) DESC, s.id");
  private static final Map<String, String> RULE_LABEL =
      Map.of(
          "circular_donations", "doação circular",
          "disproportionate_expense", "despesa desproporcional");
  static final List<String> DISCOURSE_GROUP_CATEGORIES =
      List.of(
          "lgbtfobia",
          "racismo",
          "misoginia",
          "capacitismo",
          "xenofobia",
          "regionalismo",
          "aporofobia",
          "gordofobia",
          "antissemitismo",
          "intolerancia_religiosa",
          "etarismo_saude");
  private static final String DISCOURSE_FROM =
      """
       FROM social_post_review r
       JOIN social_post p ON p.id = r.social_post_id
       JOIN social_account a ON a.id = p.social_account_id
       LEFT JOIN people pe ON pe.id = a.person_id
      """;

  private final ReadSupport db;
  private final PoliticianReadJdbcAdapter politicians;
  private final JsonMapper json = JsonMapper.builder().build();

  public SignalReadJdbcAdapter(ReadSupport db, PoliticianReadJdbcAdapter politicians) {
    this.db = db;
    this.politicians = politicians;
  }

  @Override
  @Cacheable(cacheNames = "circular-summary")
  public Map<String, Object> circularSummary() {
    Map<String, Object> bySeverity = new LinkedHashMap<>();
    long total = 0;
    for (Map<String, Object> r :
        db.list(
            "SELECT s.severity, count(*) AS n FROM signal s JOIN rule_run rr ON rr.id ="
                + " s.rule_run_id WHERE rr.rule = ? GROUP BY s.severity",
            CIRCULAR_RULE)) {
      bySeverity.put((String) r.get("severity"), r.get("n"));
      total += ReadSupport.longOf(r.get("n"));
    }
    Map<String, Object> last =
        db.one(
            "SELECT rule_version, params::text AS params, run_at FROM rule_run WHERE rule = ?"
                + " ORDER BY id DESC LIMIT 1",
            CIRCULAR_RULE);
    Object maxDepth = null;
    if (last != null && last.get("params") != null) {
      try {
        maxDepth = json.readTree((String) last.get("params")).path("max_depth").numberValue();
      } catch (JacksonException e) {
        maxDepth = null;
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", total);
    out.put("bySeverity", bySeverity);
    out.put("ruleVersion", last == null ? null : last.get("rule_version"));
    out.put("maxDepth", maxDepth);
    out.put("runAt", last == null ? null : last.get("run_at"));
    return out;
  }

  @Override
  @Cacheable(cacheNames = "circular-signals")
  public List<Map<String, Object>> circularSignals(
      String severity, String sort, int limit, int offset) {
    String orderBy =
        CIRCULAR_SORT.getOrDefault(sort == null ? "severity" : sort, CIRCULAR_SORT.get("severity"));
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT s.id, s.severity, s.explanation, coalesce(s.amount_cents, 0) AS "amountCents",
                   coalesce(s.path_length, 0) AS "pathLength",
            """
                + AI_REVIEW_COLUMNS
                + " FROM signal s JOIN rule_run rr ON rr.id = s.rule_run_id "
                + AI_REVIEW_JOIN
                + " WHERE rr.rule = ? AND (?::text IS NULL OR s.severity = ?)"
                + " ORDER BY "
                + orderBy
                + " LIMIT ? OFFSET ?",
            CIRCULAR_RULE,
            severity,
            severity,
            limit,
            offset);
    if (rows.isEmpty()) return rows;
    Map<Long, List<Map<String, Object>>> actors = new HashMap<>();
    for (Map<String, Object> a :
        db.list(
            """
            SELECT sa.signal_id AS "signalId", sa.type,
                   CASE WHEN sa.type = 'person' THEN p.cpf ELSE c.cnpj END AS "cpfCnpj",
                   CASE WHEN sa.type = 'person' THEN p.canonical_name
                        ELSE coalesce(cr.legal_name, c.legal_name) END AS label
            FROM signal_actor sa
            LEFT JOIN people p ON sa.type = 'person' AND p.id = sa.actor_id
            LEFT JOIN companies c ON sa.type = 'company' AND c.id = sa.actor_id
            LEFT JOIN company_registry cr ON cr.company_id = c.id
            WHERE sa.signal_id = ANY(?)
            ORDER BY sa.signal_id, sa.ctid
            """,
            (Object) ReadSupport.longs(rows, "id").toArray(Long[]::new))) {
      if (a.get("cpfCnpj") == null) continue;
      Map<String, Object> actor = new LinkedHashMap<>();
      actor.put("cpfCnpj", a.get("cpfCnpj"));
      actor.put("label", a.get("label") != null ? a.get("label") : a.get("cpfCnpj"));
      actor.put("type", a.get("type"));
      actors
          .computeIfAbsent(ReadSupport.longOf(a.get("signalId")), k -> new ArrayList<>())
          .add(actor);
    }
    for (Map<String, Object> r : rows) {
      Map<String, Object> ai = ReadSupport.aiReview(r);
      r.put("actors", actors.getOrDefault(ReadSupport.longOf(r.get("id")), List.of()));
      r.put("aiReview", ai);
    }
    return rows;
  }

  @Override
  @Cacheable(cacheNames = "ai-review-summary")
  public Map<String, Object> aiReviewSummary() {
    Map<String, Object> byVerdict = new LinkedHashMap<>();
    long total = 0;
    for (Map<String, Object> r :
        db.list("SELECT verdict, count(*) AS n FROM signal_ai_review GROUP BY verdict")) {
      byVerdict.put((String) r.get("verdict"), r.get("n"));
      total += ReadSupport.longOf(r.get("n"));
    }
    Map<String, Object> latest =
        db.one("SELECT model FROM signal_ai_review ORDER BY reviewed_at DESC LIMIT 1");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", total);
    out.put("byVerdict", byVerdict);
    out.put("model", latest == null ? null : latest.get("model"));
    return out;
  }

  @Override
  @Cacheable(cacheNames = "ai-review-count")
  public long aiReviewCount(String verdict, String rule) {
    return db.count(
        """
        SELECT count(*) FROM signal_ai_review ar
        JOIN signal s ON s.id = ar.signal_id JOIN rule_run rr ON rr.id = s.rule_run_id
        WHERE (?::text IS NULL OR ar.verdict = ?) AND (?::text IS NULL OR rr.rule = ?)
        """,
        verdict,
        verdict,
        rule,
        rule);
  }

  @Override
  @Cacheable(cacheNames = "ai-reviews")
  public List<Map<String, Object>> aiReviews(String verdict, String rule, int limit, int offset) {
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT * FROM (
              SELECT DISTINCT ON (ar.signal_id) ar.signal_id AS "signalId", rr.rule,
                     s.explanation AS "signalExplanation",
                     coalesce(s.amount_cents, (
                       SELECT ce.amount_cents FROM signal_evidence se
                       JOIN campaign_expense ce ON ce.id = se.record_id
                       WHERE se.signal_id = s.id AND se.table_name = 'campaign_expense' LIMIT 1
                     ), 0) AS "signalAmountCents",
                     ar.verdict, ar.confidence, ar.explanation, ar.facts::text AS facts,
                     ar.model, ar.reviewed_at AS "reviewedAt"
              FROM signal_ai_review ar
              JOIN signal s ON s.id = ar.signal_id
              JOIN rule_run rr ON rr.id = s.rule_run_id
              WHERE (?::text IS NULL OR ar.verdict = ?) AND (?::text IS NULL OR rr.rule = ?)
              ORDER BY ar.signal_id, ar.reviewed_at DESC
            ) x
            ORDER BY CASE verdict WHEN 'bizarro' THEN 0 WHEN 'inconclusivo' THEN 1 ELSE 2 END,
                     "signalAmountCents" DESC, "signalId"
            LIMIT ? OFFSET ?
            """,
            verdict,
            verdict,
            rule,
            rule,
            limit,
            offset);
    List<Long> cycleIds = new ArrayList<>();
    for (Map<String, Object> r : rows)
      if (CIRCULAR_RULE.equals(r.get("rule"))) cycleIds.add(ReadSupport.longOf(r.get("signalId")));
    Map<Long, List<String>> graphIds = politicians.graphIds(cycleIds);
    for (Map<String, Object> r : rows) {
      r.put("ruleLabel", RULE_LABEL.getOrDefault((String) r.get("rule"), (String) r.get("rule")));
      r.put("facts", stringArray((String) r.get("facts")));
      r.put("graphIds", graphIds.get(ReadSupport.longOf(r.get("signalId"))));
    }
    return rows;
  }

  @Override
  @Cacheable(cacheNames = "supplier-partner-summary")
  public Map<String, Object> supplierPartnerSummary() {
    Map<String, Object> row =
        db.one(
            """
            SELECT count(*) AS total, count(*) FILTER (WHERE paid_by_self) AS self,
                   coalesce(sum(payments_total_cents), 0) AS "totalCents"
            FROM candidate_supplier_partner
            """);
    long total = ReadSupport.longOf(row.get("total"));
    long self = ReadSupport.longOf(row.get("self"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("total", total);
    out.put("self", self);
    out.put("others", total - self);
    out.put("totalCents", row.get("totalCents"));
    return out;
  }

  private record Where(String sql, List<Object> args) {}

  private static Where supplierPartnerWhere(String filter, String rawQ) {
    List<String> clauses = new ArrayList<>();
    List<Object> args = new ArrayList<>();
    if ("self".equals(filter)) clauses.add("csp.paid_by_self");
    if ("others".equals(filter)) clauses.add("NOT csp.paid_by_self");
    String q = rawQ == null ? "" : rawQ.strip();
    if (!q.isEmpty()) {
      String norm = SourceValues.normalizeName(q);
      clauses.add("(p.canonical_name LIKE ? OR coalesce(cr.legal_name, c.legal_name) ILIKE ?)");
      args.add(ReadSupport.like(norm == null ? "" : norm));
      args.add(ReadSupport.like(q));
    }
    return new Where(clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses), args);
  }

  private static final String SUPPLIER_PARTNER_FROM =
      """
       FROM candidate_supplier_partner csp
       JOIN people p ON p.id = csp.person_id
       JOIN companies c ON c.id = csp.company_id
       LEFT JOIN company_registry cr ON cr.company_id = c.id
      """;

  @Override
  @Cacheable(cacheNames = "supplier-partner-count")
  public long supplierPartnerCount(String filter, String q) {
    Where w = supplierPartnerWhere(filter, q);
    return db.count("SELECT count(*)" + SUPPLIER_PARTNER_FROM + w.sql(), w.args().toArray());
  }

  @Override
  @Cacheable(cacheNames = "supplier-partners")
  public List<Map<String, Object>> supplierPartners(
      String filter, String q, int limit, int offset) {
    Where w = supplierPartnerWhere(filter, q);
    List<Object> args = new ArrayList<>(w.args());
    args.add(limit);
    args.add(offset);
    return db.list(
        """
        SELECT csp.person_id AS "personId", p.canonical_name AS "personName",
               c.cnpj AS "companyCnpj", coalesce(cr.legal_name, c.legal_name) AS "companyName",
               csp.partner_role AS "partnerRole", csp.partner_since AS "partnerSince",
               csp.payments_total_cents AS "paymentsTotalCents",
               csp.payments_count AS "paymentsCount", csp.payer_candidacies AS "payerCandidacies",
               csp.paid_by_self AS "paidBySelf"
        """
            + SUPPLIER_PARTNER_FROM
            + w.sql()
            + " ORDER BY csp.payments_total_cents DESC, csp.id LIMIT ? OFFSET ?",
        args.toArray());
  }

  @Override
  @Cacheable(cacheNames = "discourse-summary")
  public Map<String, Object> discourseSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("reviewed", db.count("SELECT count(*) FROM social_post_review"));
    out.put("total", db.count("SELECT count(*) FROM social_post_review WHERE is_offensive"));
    out.put(
        "accounts",
        db.count(
            "SELECT count(DISTINCT p.social_account_id) FROM social_post_review r"
                + " JOIN social_post p ON p.id = r.social_post_id WHERE r.is_offensive"));
    Map<String, Object> bySeverity = new LinkedHashMap<>();
    for (Map<String, Object> r :
        db.list(
            "SELECT severity, count(*) AS n FROM social_post_review WHERE is_offensive"
                + " AND severity IS NOT NULL GROUP BY severity"))
      bySeverity.put((String) r.get("severity"), r.get("n"));
    out.put("bySeverity", bySeverity);
    Map<String, Object> byCategory = new LinkedHashMap<>();
    for (Map<String, Object> r :
        db.list(
            "SELECT c AS category, count(*) AS n FROM social_post_review r,"
                + " jsonb_array_elements_text(CASE WHEN jsonb_typeof(r.categories) = 'array'"
                + " THEN r.categories ELSE '[]'::jsonb END) c"
                + " WHERE r.is_offensive GROUP BY c"))
      byCategory.put((String) r.get("category"), r.get("n"));
    out.put("byCategory", byCategory);
    return out;
  }

  private static Where discourseWhere(DiscourseFilter f) {
    List<String> clauses = new ArrayList<>(List.of("r.is_offensive"));
    List<Object> args = new ArrayList<>();
    if (f.personId() != null) {
      clauses.add("a.person_id = ?");
      args.add(f.personId());
    }
    if (f.category() != null && !f.category().isEmpty()) {
      clauses.add("jsonb_exists(r.categories, ?)");
      args.add(f.category());
    } else if (f.group()) {
      clauses.add("jsonb_exists_any(r.categories, ?)");
      args.add(DISCOURSE_GROUP_CATEGORIES.toArray(String[]::new));
    }
    if (f.severity() != null && List.of("high", "medium", "low").contains(f.severity())) {
      clauses.add("r.severity = ?");
      args.add(f.severity());
    }
    String h =
        f.handle() == null
            ? ""
            : f.handle().strip().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
    if (!h.isEmpty()) {
      clauses.add("a.handle = ?");
      args.add(h);
    }
    String q = f.q() == null ? "" : f.q().strip();
    if (!q.isEmpty()) {
      String norm = SourceValues.normalizeName(q);
      clauses.add("(p.text ILIKE ? OR pe.canonical_name LIKE ? OR a.handle LIKE ?)");
      args.add(ReadSupport.like(q));
      args.add(ReadSupport.like(norm == null ? "" : norm));
      args.add(ReadSupport.like(q.toLowerCase(Locale.ROOT)));
    }
    return new Where(" WHERE " + String.join(" AND ", clauses), args);
  }

  @Override
  @Cacheable(cacheNames = "discourse-count")
  public long discourseCount(DiscourseFilter filter) {
    Where w = discourseWhere(filter);
    return db.count("SELECT count(*)" + DISCOURSE_FROM + w.sql(), w.args().toArray());
  }

  @Override
  @Cacheable(cacheNames = "discourse-signals")
  public List<Map<String, Object>> discourseSignals(DiscourseFilter filter, int limit, int offset) {
    Where w = discourseWhere(filter);
    List<Object> args = new ArrayList<>(w.args());
    args.add(limit);
    args.add(offset);
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT p.id AS "postId", a.handle, a.person_id AS "personId",
                   pe.canonical_name AS "personName", el.party_abbr AS party, el.state,
                   p.kind, p.text, p.url, p.posted_at AS "postedAt",
                   p.matched_terms::text AS "matchedTerms", p.reply_to_handle AS "replyToHandle",
                   r.severity, r.categories::text AS categories, r.quote, r.explanation
            """
                + DISCOURSE_FROM
                + """
                 LEFT JOIN LATERAL (
                   SELECT ph.party_abbr, ph.state FROM politician_history ph
                   WHERE ph.person_id = a.person_id AND ph.result LIKE 'ELEITO%'
                   ORDER BY ph.year DESC LIMIT 1
                 ) el ON true
                """
                + w.sql()
                + """
                 ORDER BY CASE r.severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
                          p.posted_at DESC NULLS LAST, p.id DESC
                 LIMIT ? OFFSET ?
                """,
            args.toArray());
    for (Map<String, Object> r : rows) {
      r.put("matchedTerms", stringArray((String) r.get("matchedTerms")));
      r.put("categories", stringArray((String) r.get("categories")));
    }
    return rows;
  }

  @Override
  @Cacheable(cacheNames = "disproportionate-count")
  public long disproportionateExpenseCount() {
    return db.count(
        "SELECT count(*) FROM signal s JOIN rule_run rr ON rr.id = s.rule_run_id"
            + " WHERE rr.rule = 'disproportionate_expense'");
  }

  private List<String> stringArray(String jsonText) {
    if (jsonText == null) return List.of();
    try {
      var node = json.readTree(jsonText);
      if (!node.isArray()) return List.of();
      List<String> out = new ArrayList<>();
      node.forEach(n -> out.add(n.isString() ? n.asString() : n.toString()));
      return out;
    } catch (JacksonException e) {
      return List.of();
    }
  }
}
