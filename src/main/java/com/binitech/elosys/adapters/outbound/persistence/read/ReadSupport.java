package com.binitech.elosys.adapters.outbound.persistence.read;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class ReadSupport {
  private static final DateTimeFormatter ISO_SECONDS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'");

  static final String PROVENANCE_JOIN =
      """
       JOIN parse pa ON pa.id = t.provenance_id
       JOIN collection col ON col.id = pa.collection_id
       JOIN source src ON src.id = col.source_id
      """;

  static final String PROVENANCE_COLUMNS =
      """
       src.name AS "srcSourceName", src.agency AS "srcAgency",
       src.legal_basis AS "srcLegalBasis", col.url AS "srcUrl",
       col.accessed_at AS "srcAccessedAt", col.payload_sha256 AS "srcSha256",
       pa.parser_name AS "srcParserName", pa.parser_version AS "srcParserVersion"
      """;

  private final JdbcClient jdbc;

  public ReadSupport(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  JdbcClient jdbc() {
    return jdbc;
  }

  List<Map<String, Object>> list(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query((rs, i) -> row(rs)).list();
  }

  Map<String, Object> one(String sql, Object... params) {
    return jdbc.sql(sql).params(params).query((rs, i) -> row(rs)).optional().orElse(null);
  }

  long count(String sql, Object... params) {
    Number n = jdbc.sql(sql).params(params).query(Number.class).single();
    return n == null ? 0 : n.longValue();
  }

  static Map<String, Object> row(ResultSet rs) throws SQLException {
    ResultSetMetaData md = rs.getMetaData();
    Map<String, Object> out = new LinkedHashMap<>();
    for (int i = 1; i <= md.getColumnCount(); i++) out.put(md.getColumnLabel(i), value(rs, i));
    return out;
  }

  private static Object value(ResultSet rs, int i) throws SQLException {
    Object v = rs.getObject(i);
    if (v == null) return null;
    if (v instanceof OffsetDateTime t) return iso(t);
    if (v instanceof java.sql.Timestamp ts) return iso(ts.toInstant().atOffset(ZoneOffset.UTC));
    if (v instanceof BigDecimal d) {
      if (d.scale() <= 0 || d.stripTrailingZeros().scale() <= 0) {
        try {
          return d.longValueExact();
        } catch (ArithmeticException e) {
          return d;
        }
      }
      return d.doubleValue();
    }
    if (v instanceof Integer n) return n.longValue();
    if (v instanceof org.postgresql.util.PGobject pg) return pg.getValue();
    return v;
  }

  static String iso(OffsetDateTime t) {
    return t.withOffsetSameInstant(ZoneOffset.UTC).format(ISO_SECONDS);
  }

  static Map<String, Object> withProvenance(Map<String, Object> row) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("sourceName", row.remove("srcSourceName"));
    p.put("agency", row.remove("srcAgency"));
    p.put("legalBasis", row.remove("srcLegalBasis"));
    p.put("url", row.remove("srcUrl"));
    p.put("accessedAt", row.remove("srcAccessedAt"));
    p.put("sha256", row.remove("srcSha256"));
    p.put("parserName", row.remove("srcParserName"));
    p.put("parserVersion", row.remove("srcParserVersion"));
    row.put("provenance", p);
    return row;
  }

  static Map<String, Object> provenanceOnly(Map<String, Object> row) {
    return row == null ? null : (Map<String, Object>) withProvenance(row).get("provenance");
  }

  Map<Long, String> photoUrls(Collection<Long> personIds) {
    LinkedHashSet<Long> ids = new LinkedHashSet<>();
    for (Long id : personIds) if (id != null) ids.add(id);
    Map<Long, String> out = new HashMap<>();
    if (ids.isEmpty()) return out;
    jdbc.sql(
            """
            SELECT DISTINCT ON (person_id) person_id, photo_url FROM candidate_photo
            WHERE person_id = ANY(?) ORDER BY person_id, year DESC NULLS LAST
            """)
        .param(ids.toArray(Long[]::new))
        .query(
            rs -> {
              out.put(rs.getLong(1), rs.getString(2));
            });
    return out;
  }

  static Long longOf(Object v) {
    return v instanceof Number n ? n.longValue() : null;
  }

  static List<Long> longs(List<Map<String, Object>> rows, String key) {
    List<Long> out = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      Long v = longOf(r.get(key));
      if (v != null) out.add(v);
    }
    return out;
  }

  static String digitsOnly(String value) {
    return value == null ? "" : value.replaceAll("\\D", "");
  }

  static String like(String q) {
    return "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
  }

  static String formatCnpj(String cnpj) {
    return cnpj.length() == 14
        ? cnpj.substring(0, 2)
            + "."
            + cnpj.substring(2, 5)
            + "."
            + cnpj.substring(5, 8)
            + "/"
            + cnpj.substring(8, 12)
            + "-"
            + cnpj.substring(12)
        : cnpj;
  }

  static Map<String, Object> page(List<Map<String, Object>> rows, long total) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rows", rows);
    out.put("total", total);
    return out;
  }

  static Map<String, Object> aiReview(Map<String, Object> row) {
    Object verdict = row.remove("aiVerdict");
    Object confidence = row.remove("aiConfidence");
    Object explanation = row.remove("aiExplanation");
    Object model = row.remove("aiModel");
    if (verdict == null) return null;
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("verdict", verdict);
    out.put("confidence", confidence);
    out.put("explanation", explanation == null ? "" : explanation);
    out.put("model", model == null ? "" : model);
    return out;
  }

  static final String AI_REVIEW_COLUMNS =
      """
       ar.verdict AS "aiVerdict", ar.confidence AS "aiConfidence",
       ar.explanation AS "aiExplanation", ar.model AS "aiModel"
      """;

  static final String AI_REVIEW_JOIN =
      """
       LEFT JOIN LATERAL (
         SELECT x.verdict, x.confidence, x.explanation, x.model FROM signal_ai_review x
         WHERE x.signal_id = s.id ORDER BY x.reviewed_at DESC LIMIT 1
       ) ar ON true
      """;

  static final Map<String, List<String>> EXPENSE_CATEGORY_SPELLINGS = new LinkedHashMap<>();

  static {
    String[][] categories = {
      {"CANETA"},
      {"LAPIS", "LÁPIS"},
      {"LAPISEIRA"},
      {"BORRACHA"},
      {"APONTADOR"},
      {"ADESIVO"},
      {"CRACHA", "CRACHÁ"},
      {"ETIQUETA"},
      {"CLIPS"},
      {"GRAMPO"},
      {"GRAMPEADOR"},
      {"REGUA", "RÉGUA"},
      {"BLOCO DE ANOTA"},
      {"ENVELOPE"},
      {"MARCADOR DE TEXTO"},
      {"PRANCHETA"},
      {"PERFURADOR"},
      {"ELASTICO", "ELÁSTICO"}
    };
    for (String[] c : categories) EXPENSE_CATEGORY_SPELLINGS.put(c[0], List.of(c));
  }

  static List<String> spellings(String category) {
    if (category == null)
      return EXPENSE_CATEGORY_SPELLINGS.values().stream().flatMap(List::stream).toList();
    return EXPENSE_CATEGORY_SPELLINGS.get(category);
  }
}
