package com.binitech.elosys.adapters.outbound.persistence.read;

import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.AI_REVIEW_COLUMNS;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.AI_REVIEW_JOIN;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.PROVENANCE_COLUMNS;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.PROVENANCE_JOIN;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.withProvenance;

import com.binitech.elosys.application.ports.outbound.ReadModelPorts.PoliticianReadPort;
import com.binitech.elosys.domain.SourceValues;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class PoliticianReadJdbcAdapter implements PoliticianReadPort {
  private static final Pattern CYCLE_CHAIN = Pattern.compile("(\\d{11}|\\d{14}) \\(([^)]*)\\)");

  private static final int MAX_PER_LEVEL = 6;

  private final ReadSupport db;

  public PoliticianReadJdbcAdapter(ReadSupport db) {
    this.db = db;
  }

  @Override
  @Cacheable(cacheNames = "search-people", unless = "#result == null")
  public List<Map<String, Object>> searchPeople(String rawQuery, int limit) {
    String query = rawQuery == null ? "" : rawQuery.strip();
    if (query.length() < 2) return List.of();
    String digits = ReadSupport.digitsOnly(query);
    boolean looksLikeCpf = digits.length() >= 6 && digits.length() <= 11;
    String normalized = SourceValues.normalizeName(query);
    String pattern =
        looksLikeCpf ? digits + "%" : "%" + (normalized == null ? "" : normalized) + "%";

    List<Map<String, Object>> candidateRows =
        db.list(
            "WITH matches AS (SELECT id, canonical_name, cpf, cpf_trusted FROM people WHERE "
                + (looksLikeCpf ? "cpf LIKE ?" : "canonical_name LIKE ?")
                + " LIMIT ?)"
                + """
                 SELECT m.id AS "personId", m.canonical_name AS "canonicalName", m.cpf AS cpf,
                        m.cpf_trusted AS "cpfTrusted",
                        (SELECT count(*) FROM politician_history WHERE person_id = m.id)
                          AS "candidacyCount",
                        l.year AS "latestYear", l.office AS "latestOffice",
                        l.party_abbr AS "latestPartyAbbr", l.state AS "latestState",
                        l.result AS "latestResult"
                 FROM matches m
                 LEFT JOIN LATERAL (
                   SELECT year, office, party_abbr, state, result FROM politician_history
                   WHERE person_id = m.id ORDER BY year DESC, round DESC NULLS LAST LIMIT 1
                 ) l ON true
                 ORDER BY l.year DESC NULLS LAST
                """,
            pattern,
            limit * 4);
    if (candidateRows.size() > limit) candidateRows = candidateRows.subList(0, limit);
    Map<Long, String> photos = db.photoUrls(ReadSupport.longs(candidateRows, "personId"));
    List<Map<String, Object>> results = new ArrayList<>();
    Set<String> known = new HashSet<>();
    for (Map<String, Object> r : candidateRows) {
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("kind", "candidato");
      out.putAll(r);
      out.put("photoUrl", photos.get(ReadSupport.longOf(r.get("personId"))));
      results.add(out);
      if (r.get("cpf") != null) known.add((String) r.get("cpf"));
    }
    if (results.size() >= limit) return results;

    int remaining = limit - results.size();
    List<Map<String, Object>> personRows;
    if (looksLikeCpf) {
      String cpfPattern = digits + "%";
      personRows =
          db.list(
              """
              WITH matches AS (
                SELECT donor_cpf_cnpj AS cpf, donor_name AS name FROM campaign_donation
                WHERE donor_company_id IS NULL AND length(donor_cpf_cnpj) = 11
                  AND donor_cpf_cnpj LIKE ?
                UNION
                SELECT supplier_cpf_cnpj AS cpf, supplier_name AS name FROM campaign_expense
                WHERE supplier_company_id IS NULL AND length(supplier_cpf_cnpj) = 11
                  AND supplier_cpf_cnpj LIKE ?
              )
              SELECT cpf, max(name) AS name FROM matches GROUP BY cpf LIMIT ?
              """,
              cpfPattern,
              cpfPattern,
              remaining * 2);
    } else {
      String tsQuery = prefixTsQuery(normalized);
      personRows =
          tsQuery.isEmpty()
              ? List.of()
              : db.list(
                  "SELECT cpf, name FROM pessoa_fisica_search"
                      + " WHERE tsv @@ to_tsquery('simple', ?) LIMIT ?",
                  tsQuery,
                  remaining * 2);
    }
    for (Map<String, Object> r : personRows) {
      if (results.size() >= limit) break;
      String cpf = (String) r.get("cpf");
      if (known.contains(cpf)) continue;
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("kind", "pessoa_fisica");
      out.put("cpf", cpf);
      out.put("canonicalName", r.get("name") != null ? r.get("name") : cpf);
      results.add(out);
    }
    return results;
  }

  static String prefixTsQuery(String normalized) {
    if (normalized == null) return "";
    List<String> tokens = new ArrayList<>();
    for (String tok : normalized.split(" ")) {
      String clean = tok.replaceAll("[^\\p{L}\\p{N}]", "");
      if (!clean.isEmpty()) tokens.add(clean + ":*");
    }
    return String.join(" & ", tokens);
  }

  @Override
  @Cacheable(cacheNames = "person-header", unless = "#result == null")
  public Map<String, Object> header(long personId) {
    Map<String, Object> person =
        db.one(
            """
            SELECT id, cpf, cpf_trusted AS "cpfTrusted", voter_id AS "voterId",
                   canonical_name AS "canonicalName"
            FROM people WHERE id = ?
            """,
            personId);
    if (person == null) return null;
    Map<String, Object> latest =
        db.one(
            "SELECT t.year, t.office, t.party_abbr AS \"partyAbbr\", t.state, "
                + PROVENANCE_COLUMNS
                + " FROM politician_history t "
                + PROVENANCE_JOIN
                + " WHERE t.person_id = ? ORDER BY t.year DESC, t.round DESC NULLS LAST LIMIT 1",
            personId);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("person", person);
    if (latest != null) {
      withProvenance(latest);
      Object provenance = latest.remove("provenance");
      out.put("latestCandidacy", latest);
      out.put("provenance", provenance);
    } else {
      out.put("latestCandidacy", null);
      out.put("provenance", null);
    }
    out.put(
        "candidacyCount",
        db.count("SELECT count(*) FROM politician_history WHERE person_id = ?", personId));
    out.put("signalsCount", signalsCount(personId));
    return out;
  }

  private long signalsCount(long personId) {
    return db.count(
        "SELECT count(DISTINCT signal_id) FROM signal_actor WHERE type = 'person' AND actor_id = ?",
        personId);
  }

  @Override
  public String photoUrl(long personId) {
    return db.photoUrls(List.of(personId)).get(personId);
  }

  @Override
  @Cacheable(cacheNames = "person-photo-provenance", unless = "#result == null")
  public Map<String, Object> photoProvenance(long personId) {
    return ReadSupport.provenanceOnly(
        db.one(
            "SELECT "
                + PROVENANCE_COLUMNS
                + " FROM candidate_photo t "
                + PROVENANCE_JOIN
                + " WHERE t.person_id = ? ORDER BY t.year DESC NULLS LAST LIMIT 1",
            personId));
  }

  @Override
  @Cacheable(cacheNames = "person-candidacies")
  public List<Map<String, Object>> candidacies(long personId) {
    return db
        .list(
            """
            SELECT t.id, t.year, t.election_type AS "electionType", t.round, t.office,
                   t.candidate_number AS "candidateNumber", t.party_abbr AS "partyAbbr",
                   t.party_name AS "partyName", t.state, t.electoral_unit AS "electoralUnit",
                   t.municipality, t.candidacy_status AS "candidacyStatus",
                   t.candidacy_status_detail AS "candidacyStatusDetail", t.result,
                   t.ballot_name AS "ballotName", t.full_name AS "fullName",
                   t.birth_date AS "birthDate", t.gender, t.education,
                   t.marital_status AS "maritalStatus", t.race, t.occupation,
                   t.tse_candidacy_id AS "tseCandidacyId",
            """
                + PROVENANCE_COLUMNS
                + " FROM politician_history t "
                + PROVENANCE_JOIN
                + " WHERE t.person_id = ? ORDER BY t.year DESC, t.round DESC NULLS LAST",
            personId)
        .stream()
        .map(ReadSupport::withProvenance)
        .toList();
  }

  @Override
  @Cacheable(cacheNames = "person-campaign-orgs")
  public List<Map<String, Object>> campaignOrgs(long personId) {
    return db
        .list(
            "SELECT t.id, t.cnpj, t.year, t.office, t.party_abbr AS \"partyAbbr\", t.state, "
                + PROVENANCE_COLUMNS
                + " FROM campaign_org t "
                + PROVENANCE_JOIN
                + " WHERE t.person_id = ? ORDER BY t.year DESC",
            personId)
        .stream()
        .map(ReadSupport::withProvenance)
        .toList();
  }

  @Override
  @Cacheable(cacheNames = "person-social-media")
  public List<Map<String, Object>> socialMedia(long personId) {
    return db
        .list(
            "SELECT t.id, t.year, t.platform, t.url, t.state, "
                + PROVENANCE_COLUMNS
                + " FROM social_media t "
                + PROVENANCE_JOIN
                + " WHERE t.person_id = ? ORDER BY t.year DESC, t.platform",
            personId)
        .stream()
        .map(ReadSupport::withProvenance)
        .toList();
  }

  @Override
  @Cacheable(cacheNames = "person-assets")
  public Map<String, Object> assets(long personId) {
    List<Map<String, Object>> rows =
        db
            .list(
                "SELECT t.id, t.year, t.asset_type AS \"assetType\", t.description,"
                    + " coalesce(t.value_cents, 0) AS \"valueCents\","
                    + " t.source_updated_at AS \"sourceUpdatedAt\", "
                    + PROVENANCE_COLUMNS
                    + " FROM declared_assets t "
                    + PROVENANCE_JOIN
                    + " WHERE t.person_id = ? ORDER BY t.year DESC, t.value_cents DESC NULLS LAST",
                personId)
            .stream()
            .map(ReadSupport::withProvenance)
            .toList();
    TreeMap<Long, Map<String, Object>> byYear = new TreeMap<>();
    for (Map<String, Object> r : rows) {
      long year = ReadSupport.longOf(r.get("year"));
      long cents = ReadSupport.longOf(r.get("valueCents"));
      Map<String, Object> acc =
          byYear.computeIfAbsent(
              year,
              y -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("year", y);
                m.put("totalCents", 0L);
                m.put("count", 0L);
                return m;
              });
      acc.put("totalCents", (Long) acc.get("totalCents") + cents);
      acc.put("count", (Long) acc.get("count") + 1);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("declaredAssets", rows);
    out.put("declaredAssetsByYear", new ArrayList<>(byYear.values()));
    return out;
  }

  @Override
  @Cacheable(cacheNames = "person-earmarks")
  public Map<String, Object> earmarks(long personId) {
    List<Map<String, Object>> earmarks =
        db
            .list(
                """
                SELECT t.id, t.earmark_code AS "earmarkCode", t.year,
                       t.earmark_type AS "earmarkType", t.locality, t.state, t.municipality,
                       t.function_name AS "functionName", t.action_name AS "actionName",
                       t.committed_cents AS "committedCents", t.paid_cents AS "paidCents",
                """
                    + PROVENANCE_COLUMNS
                    + " FROM parliamentary_earmark t "
                    + PROVENANCE_JOIN
                    + " WHERE t.author_person_id = ?"
                    + " ORDER BY t.year DESC, t.committed_cents DESC NULLS LAST",
                personId)
            .stream()
            .map(ReadSupport::withProvenance)
            .toList();
    long committed = 0, paid = 0;
    for (Map<String, Object> e : earmarks) {
      Long c = ReadSupport.longOf(e.get("committedCents"));
      Long p = ReadSupport.longOf(e.get("paidCents"));
      committed += c == null ? 0 : c;
      paid += p == null ? 0 : p;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("earmarks", earmarks);
    out.put("totalCommittedCents", committed);
    out.put("totalPaidCents", paid);
    return out;
  }

  @Override
  @Cacheable(cacheNames = "person-finance")
  public Map<String, Object> finance(long personId, Integer year) {
    String yearClause = year != null ? " AND t.year = ?" : "";
    Object[] args = year != null ? new Object[] {personId, year} : new Object[] {personId};
    Map<String, Object> donations =
        db.one(
            "SELECT count(*) AS n, coalesce(sum(t.amount_cents), 0) AS total FROM campaign_donation"
                + " t JOIN campaign_org co ON co.id = t.campaign_org_id WHERE co.person_id = ?"
                + yearClause,
            args);
    Map<String, Object> expenses =
        db.one(
            "SELECT count(*) AS n, coalesce(sum(t.amount_cents), 0) AS total FROM campaign_expense"
                + " t JOIN campaign_org co ON co.id = t.campaign_org_id WHERE co.person_id = ?"
                + yearClause,
            args);
    Map<String, Object> fund =
        db.one(
            "SELECT count(*) AS n, coalesce(sum(t.amount_cents), 0) AS total FROM campaign_donation"
                + " t JOIN campaign_org co ON co.id = t.campaign_org_id WHERE co.person_id = ?"
                + " AND t.source IN ('FUNDO ESPECIAL', 'FUNDO PARTIDARIO')"
                + yearClause,
            args);
    long payments =
        db.count(
            "SELECT coalesce(sum(p.amount_cents), 0) FROM campaign_expense_payment p"
                + " JOIN campaign_expense ce ON ce.id = p.campaign_expense_id"
                + " JOIN campaign_org co ON co.id = ce.campaign_org_id WHERE co.person_id = ?"
                + (year != null ? " AND ce.year = ?" : ""),
            args);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("donationsCount", donations.get("n"));
    out.put("donationsTotalCents", donations.get("total"));
    out.put("expensesCount", expenses.get("n"));
    out.put("expensesTotalCents", expenses.get("total"));
    out.put("paymentsTotalCents", payments);
    out.put("electoralFundTotalCents", fund.get("total"));
    out.put("electoralFundCount", fund.get("n"));
    return out;
  }

  @Override
  @Cacheable(cacheNames = "person-signals")
  public Map<String, Object> signals(long personId) {
    List<Map<String, Object>> rows =
        db.list(
            """
            SELECT s.id, s.type, s.severity, s.explanation, sa.role, rr.rule,
                   rr.rule_version AS "ruleVersion", s.amount_cents AS "amountCents",
                   s.path_length AS "pathLength",
            """
                + AI_REVIEW_COLUMNS
                + """
                 FROM signal_actor sa
                 JOIN signal s ON s.id = sa.signal_id
                 JOIN rule_run rr ON rr.id = s.rule_run_id
                """
                + AI_REVIEW_JOIN
                + """
                 WHERE sa.type = 'person' AND sa.actor_id = ?
                 ORDER BY CASE s.severity WHEN 'high' THEN 0 WHEN 'medium' THEN 1 ELSE 2 END,
                          coalesce(s.amount_cents, 0) DESC
                 LIMIT 30
                """,
            personId);

    List<Long> expenseIds = new ArrayList<>();
    List<Long> cycleIds = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      if ("disproportionate_expense".equals(r.get("rule")))
        expenseIds.add(ReadSupport.longOf(r.get("id")));
      if ("circular_donations".equals(r.get("rule"))) cycleIds.add(ReadSupport.longOf(r.get("id")));
    }
    Map<Long, Map<String, Object>> expenseBySignal = new HashMap<>();
    if (!expenseIds.isEmpty()) {
      for (Map<String, Object> e :
          db.list(
              """
              SELECT se.signal_id AS "signalId", ce.id, ce.description,
                     coalesce(ce.amount_cents, 0) AS "amountCents", ce.year,
                     ce.supplier_name AS "supplierName"
              FROM signal_evidence se JOIN campaign_expense ce ON ce.id = se.record_id
              WHERE se.signal_id = ANY(?) AND se.table_name = 'campaign_expense'
              """,
              (Object) expenseIds.toArray(Long[]::new))) {
        expenseBySignal.put(ReadSupport.longOf(e.remove("signalId")), e);
      }
    }
    Map<Long, List<String>> graphIds = graphIds(cycleIds);

    List<Map<String, Object>> signals = new ArrayList<>();
    for (Map<String, Object> r : rows) {
      long id = ReadSupport.longOf(r.get("id"));
      boolean cycle = "circular_donations".equals(r.get("rule"));
      Object amount = r.remove("amountCents");
      Object pathLength = r.remove("pathLength");
      Map<String, Object> ai = ReadSupport.aiReview(r);
      Map<String, Object> out = new LinkedHashMap<>(r);
      out.put("expense", expenseBySignal.get(id));
      out.put("graphIds", graphIds.get(id));
      List<Map<String, Object>> nodes = cycle ? cycleNodes((String) r.get("explanation")) : null;
      out.put("cycleNodes", nodes);
      out.put("cycleEdgeAmounts", cycle ? cycleEdgeAmounts(nodes) : null);
      out.put("cycleAmountCents", cycle ? amount : null);
      out.put("cyclePathLength", cycle ? pathLength : null);
      out.put("aiReview", ai);
      signals.add(out);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("signals", signals);
    out.put("signalsCount", signalsCount(personId));
    return out;
  }

  Map<Long, List<String>> graphIds(List<Long> signalIds) {
    Map<Long, List<String>> out = new HashMap<>();
    if (signalIds.isEmpty()) return out;
    for (Map<String, Object> row :
        db.list(
            """
            SELECT sa.signal_id AS "signalId",
                   CASE WHEN sa.type = 'person' THEN p.cpf ELSE c.cnpj END AS "cpfCnpj"
            FROM signal_actor sa
            LEFT JOIN people p ON sa.type = 'person' AND p.id = sa.actor_id
            LEFT JOIN companies c ON sa.type = 'company' AND c.id = sa.actor_id
            WHERE sa.signal_id = ANY(?)
            ORDER BY sa.signal_id, sa.ctid
            """,
            (Object) signalIds.toArray(Long[]::new))) {
      if (row.get("cpfCnpj") == null) continue;
      out.computeIfAbsent(ReadSupport.longOf(row.get("signalId")), k -> new ArrayList<>())
          .add((String) row.get("cpfCnpj"));
    }
    return out;
  }

  List<Map<String, Object>> cycleNodes(String explanation) {
    List<Map<String, Object>> nodes = new ArrayList<>();
    Matcher m = CYCLE_CHAIN.matcher(explanation == null ? "" : explanation);
    while (m.find()) {
      Map<String, Object> n = new LinkedHashMap<>();
      n.put("cpfCnpj", m.group(1));
      n.put("label", m.group(2));
      n.put("type", m.group(1).length() == 14 ? "company" : "person");
      n.put("personId", null);
      n.put("photoUrl", null);
      nodes.add(n);
    }
    List<String> cpfs =
        nodes.stream()
            .filter(n -> "person".equals(n.get("type")))
            .map(n -> (String) n.get("cpfCnpj"))
            .toList();
    if (cpfs.isEmpty()) return nodes;
    Map<String, Long> byCpf = new HashMap<>();
    for (Map<String, Object> r :
        db.list(
            "SELECT id, cpf FROM people WHERE cpf = ANY(?)", (Object) cpfs.toArray(String[]::new)))
      byCpf.putIfAbsent((String) r.get("cpf"), ReadSupport.longOf(r.get("id")));
    for (Map<String, Object> n : nodes) n.put("personId", byCpf.get((String) n.get("cpfCnpj")));
    Map<Long, String> photos = db.photoUrls(ReadSupport.longs(nodes, "personId"));
    for (Map<String, Object> n : nodes) {
      Long pid = ReadSupport.longOf(n.get("personId"));
      if (pid != null) n.put("photoUrl", photos.get(pid));
    }
    return nodes;
  }

  List<Long> cycleEdgeAmounts(List<Map<String, Object>> nodes) {
    int n = nodes.size();
    if (n < 2) return List.of();
    List<Long> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      String from = (String) nodes.get(i).get("cpfCnpj");
      String to = (String) nodes.get((i + 1) % n).get("cpfCnpj");
      long donation =
          db.count(
              """
              SELECT coalesce(sum(d.amount_cents), 0) FROM campaign_donation d
              JOIN campaign_org co ON co.id = d.campaign_org_id JOIN people p ON p.id = co.person_id
              WHERE d.donor_cpf_cnpj = ? AND p.cpf = ?
              """,
              from,
              to);
      long expense =
          db.count(
              """
              SELECT coalesce(sum(e.amount_cents), 0) FROM campaign_expense e
              JOIN campaign_org co ON co.id = e.campaign_org_id JOIN people p ON p.id = co.person_id
              WHERE p.cpf = ? AND e.supplier_cpf_cnpj = ?
              """,
              from,
              to);
      out.add(donation + expense);
    }
    return out;
  }

  @Override
  @Cacheable(cacheNames = "person-donation-network")
  public Map<String, Object> donationNetwork(long personId) {
    List<Map<String, Object>> donatedTo = branches(personId, true);
    List<Map<String, Object>> receivedFrom = branches(personId, false);
    List<Long> ids = new ArrayList<>();
    for (Map<String, Object> b : concat(donatedTo, receivedFrom)) {
      ids.add(ReadSupport.longOf(((Map<?, ?>) b.get("node")).get("personId")));
      for (Object c : (List<?>) b.get("children"))
        ids.add(ReadSupport.longOf(((Map<?, ?>) c).get("personId")));
    }
    Map<Long, String> photos = db.photoUrls(ids);
    for (Map<String, Object> b : concat(donatedTo, receivedFrom)) {
      @SuppressWarnings("unchecked")
      Map<String, Object> node = (Map<String, Object>) b.get("node");
      node.put("photoUrl", photos.get(ReadSupport.longOf(node.get("personId"))));
      for (Object c : (List<?>) b.get("children")) {
        @SuppressWarnings("unchecked")
        Map<String, Object> child = (Map<String, Object>) c;
        child.put("photoUrl", photos.get(ReadSupport.longOf(child.get("personId"))));
      }
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("donatedTo", donatedTo);
    out.put("receivedFrom", receivedFrom);
    return out;
  }

  private static List<Map<String, Object>> concat(
      List<Map<String, Object>> a, List<Map<String, Object>> b) {
    List<Map<String, Object>> out = new ArrayList<>(a);
    out.addAll(b);
    return out;
  }

  private List<Map<String, Object>> branches(long personId, boolean donatedTo) {
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> node : edges(personId, donatedTo)) {
      long nodeId = ReadSupport.longOf(node.get("personId"));
      List<Map<String, Object>> children =
          edges(nodeId, donatedTo).stream()
              .filter(c -> ReadSupport.longOf(c.get("personId")) != personId)
              .toList();
      Map<String, Object> b = new LinkedHashMap<>();
      b.put("node", node);
      b.put("children", new ArrayList<>(children));
      out.add(b);
    }
    return out;
  }

  private List<Map<String, Object>> edges(long personId, boolean donatedTo) {
    String sql =
        donatedTo
            ? """
              SELECT co.person_id AS "personId", coalesce(p2.canonical_name, '(sem nome)') AS label,
                     sum(d.amount_cents) AS "amountCents"
              FROM campaign_donation d
              JOIN campaign_org co ON co.id = d.campaign_org_id
              JOIN people p2 ON p2.id = co.person_id
              WHERE d.donor_person_id = ? AND co.person_id <> ?
              GROUP BY co.person_id, p2.canonical_name
              ORDER BY "amountCents" DESC NULLS LAST LIMIT ?
              """
            : """
              SELECT d.donor_person_id AS "personId",
                     coalesce(p2.canonical_name, '(sem nome)') AS label,
                     sum(d.amount_cents) AS "amountCents"
              FROM campaign_donation d
              JOIN campaign_org co ON co.id = d.campaign_org_id
              JOIN people p2 ON p2.id = d.donor_person_id
              WHERE co.person_id = ? AND d.donor_person_id IS NOT NULL AND d.donor_person_id <> ?
              GROUP BY d.donor_person_id, p2.canonical_name
              ORDER BY "amountCents" DESC NULLS LAST LIMIT ?
              """;
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> r : db.list(sql, personId, personId, MAX_PER_LEVEL)) {
      r.put("photoUrl", null);
      rows.add(r);
    }
    return rows;
  }

  @Override
  @Cacheable(cacheNames = "person-category-expenses")
  public List<Map<String, Object>> categoryExpenseDetail(
      long personId, String category, Integer year) {
    List<String> spellings = ReadSupport.spellings(category);
    if (spellings == null) return List.of();
    List<Object> args = new ArrayList<>();
    args.add(personId);
    args.add(spellings.stream().map(s -> "%" + s + "%").toArray(String[]::new));
    if (year != null) args.add(year);
    return db
        .list(
            "SELECT t.id, t.description, t.amount_cents AS \"amountCents\", t.year, "
                + PROVENANCE_COLUMNS
                + " FROM campaign_expense t JOIN campaign_org co ON co.id = t.campaign_org_id "
                + PROVENANCE_JOIN
                + " WHERE co.person_id = ? AND t.description ILIKE ANY(?)"
                + (year != null ? " AND t.year = ?" : "")
                + " ORDER BY t.amount_cents DESC NULLS LAST",
            args.toArray())
        .stream()
        .map(ReadSupport::withProvenance)
        .toList();
  }
}
