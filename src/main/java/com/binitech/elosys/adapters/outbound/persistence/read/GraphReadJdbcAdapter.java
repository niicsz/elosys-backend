package com.binitech.elosys.adapters.outbound.persistence.read;

import com.binitech.elosys.application.ports.outbound.ReadModelPorts.GraphReadPort;
import com.binitech.elosys.domain.SourceValues;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class GraphReadJdbcAdapter implements GraphReadPort {
  private final ReadSupport db;

  public GraphReadJdbcAdapter(ReadSupport db) {
    this.db = db;
  }

  private static boolean validDoc(String d) {
    return d.length() == 11 || d.length() == 14;
  }

  @Override
  @Cacheable(cacheNames = "graph-search")
  public List<Map<String, Object>> searchEntities(String rawQuery, int limit) {
    String query = rawQuery == null ? "" : rawQuery.strip();
    if (query.length() < 2) return List.of();
    String digits = ReadSupport.digitsOnly(query);
    if (validDoc(digits)) {
      Identity ident = identities(List.of(digits)).get(digits);
      if (ident != null && !ident.canonical().equals(digits))
        return List.of(
            result(
                "person",
                ident.canonical(),
                ident.name() != null ? ident.name() : ident.canonical(),
                "político"));
      if (digits.length() == 14) {
        Map<String, Object> row =
            db.one(
                """
                SELECT c.cnpj, coalesce(cr.legal_name, c.legal_name) AS name, c.kind
                FROM companies c LEFT JOIN company_registry cr ON cr.company_id = c.id
                WHERE c.cnpj = ?
                """,
                digits);
        if (row != null)
          return List.of(
              result(
                  "company",
                  digits,
                  row.get("name") != null
                      ? (String) row.get("name")
                      : ReadSupport.formatCnpj(digits),
                  (String) row.get("kind")));
        return List.of(result("company", digits, ReadSupport.formatCnpj(digits), null));
      }
      Map<String, Object> row =
          db.one(
              "SELECT cpf, canonical_name FROM people WHERE cpf = ? ORDER BY id LIMIT 1", digits);
      if (row != null)
        return List.of(
            result(
                "person",
                digits,
                row.get("canonical_name") != null ? (String) row.get("canonical_name") : digits,
                null));
      return List.of(result("person", digits, digits, null));
    }

    String norm = SourceValues.normalizeName(query);
    String pattern = ReadSupport.like(norm == null ? "" : norm);
    List<Map<String, Object>> out = new ArrayList<>();
    for (Map<String, Object> p :
        db.list(
            "SELECT cpf, canonical_name FROM people WHERE canonical_name LIKE ? AND cpf IS NOT NULL"
                + " LIMIT ?",
            pattern,
            limit)) {
      String cpf = (String) p.get("cpf");
      out.add(
          result(
              "person",
              cpf,
              p.get("canonical_name") != null ? (String) p.get("canonical_name") : cpf,
              "político"));
    }
    for (Map<String, Object> c :
        db.list(
            """
            SELECT c.cnpj, coalesce(cr.legal_name, c.legal_name) AS name
            FROM companies c LEFT JOIN company_registry cr ON cr.company_id = c.id
            WHERE coalesce(cr.legal_name, c.legal_name) ILIKE ? LIMIT ?
            """,
            pattern,
            limit)) {
      String cnpj = (String) c.get("cnpj");
      out.add(
          result(
              "company", cnpj, c.get("name") != null ? (String) c.get("name") : cnpj, "empresa"));
    }
    return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
  }

  private static Map<String, Object> result(
      String type, String doc, String label, String sublabel) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("type", type);
    m.put("cpfCnpj", doc);
    m.put("label", label);
    m.put("sublabel", sublabel);
    return m;
  }

  record Identity(String canonical, String name, List<String> aliases) {}

  Map<String, Identity> identities(Collection<String> ids) {
    Map<String, Identity> out = new HashMap<>();
    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String id : ids) {
      String d = ReadSupport.digitsOnly(id);
      if (validDoc(d)) clean.add(d);
    }
    if (clean.isEmpty()) return out;

    Map<String, String> cnpjOwner = new HashMap<>();
    for (Map<String, Object> r :
        db.list(
            """
            SELECT DISTINCT c.cnpj, p.cpf FROM campaign_org co
            JOIN companies c ON c.id = co.company_id JOIN people p ON p.id = co.person_id
            WHERE c.cnpj = ANY(?) AND p.cpf IS NOT NULL
            """,
            (Object) clean.toArray(String[]::new)))
      cnpjOwner.putIfAbsent((String) r.get("cnpj"), (String) r.get("cpf"));

    LinkedHashSet<String> cpfs = new LinkedHashSet<>();
    for (String d : clean) if (d.length() == 11) cpfs.add(d);
    cpfs.addAll(cnpjOwner.values());
    record Rec(String name, LinkedHashSet<String> cnpjs) {}
    Map<String, Rec> byCpf = new HashMap<>();
    if (!cpfs.isEmpty()) {
      for (Map<String, Object> r :
          db.list(
              """
              SELECT p.cpf, p.canonical_name AS name, c.cnpj
              FROM people p
              LEFT JOIN campaign_org co ON co.person_id = p.id
              LEFT JOIN companies c ON c.id = co.company_id
              WHERE p.cpf = ANY(?)
              """,
              (Object) cpfs.toArray(String[]::new))) {
        Rec rec =
            byCpf.computeIfAbsent(
                (String) r.get("cpf"), k -> new Rec((String) r.get("name"), new LinkedHashSet<>()));
        if (r.get("cnpj") != null) rec.cnpjs().add((String) r.get("cnpj"));
      }
    }
    for (String id : clean) {
      if (id.length() == 11 && byCpf.containsKey(id)) {
        Rec rec = byCpf.get(id);
        List<String> aliases = new ArrayList<>(List.of(id));
        aliases.addAll(rec.cnpjs());
        out.put(id, new Identity(id, rec.name(), aliases));
      } else if (id.length() == 14 && cnpjOwner.containsKey(id)) {
        String cpf = cnpjOwner.get(id);
        Rec rec = byCpf.get(cpf);
        List<String> aliases = new ArrayList<>(List.of(cpf));
        if (rec != null) aliases.addAll(rec.cnpjs());
        out.put(id, new Identity(cpf, rec == null ? null : rec.name(), aliases));
      } else {
        out.put(id, new Identity(id, null, List.of(id)));
      }
    }
    return out;
  }

  record Incident(
      String anchor, String other, String kind, long amountCents, long n, boolean anchorIsSource) {
    Incident with(String newAnchor, String newOther) {
      return new Incident(newAnchor, newOther, kind, amountCents, n, anchorIsSource);
    }

    Map<String, Object> toEdge() {
      Map<String, Object> e = new LinkedHashMap<>();
      e.put("source", anchorIsSource ? anchor : other);
      e.put("target", anchorIsSource ? other : anchor);
      e.put("kind", kind);
      e.put("amountCents", amountCents);
      e.put("count", n);
      return e;
    }
  }

  List<Incident> incidentEdges(Collection<String> anchors) {
    if (anchors.isEmpty()) return List.of();
    String[] ids = anchors.toArray(String[]::new);
    List<Incident> out = new ArrayList<>();
    for (Map<String, Object> r :
        db.list(
            """
            SELECT d.donor_cpf_cnpj AS anchor, p.cpf AS other, 'donation' AS kind,
                   coalesce(sum(d.amount_cents), 0) AS amount, count(*) AS n, true AS src
            FROM campaign_donation d JOIN campaign_org co ON co.id = d.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE d.donor_cpf_cnpj = ANY(?) AND p.cpf IS NOT NULL AND d.donor_cpf_cnpj <> p.cpf
            GROUP BY d.donor_cpf_cnpj, p.cpf
            UNION ALL
            SELECT p.cpf, d.donor_cpf_cnpj, 'donation', coalesce(sum(d.amount_cents), 0), count(*), false
            FROM campaign_donation d JOIN campaign_org co ON co.id = d.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE p.cpf = ANY(?) AND d.donor_cpf_cnpj IS NOT NULL AND d.donor_cpf_cnpj <> p.cpf
            GROUP BY p.cpf, d.donor_cpf_cnpj
            UNION ALL
            SELECT p.cpf, e.supplier_cpf_cnpj, 'payment', coalesce(sum(e.amount_cents), 0), count(*), true
            FROM campaign_expense e JOIN campaign_org co ON co.id = e.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE p.cpf = ANY(?) AND e.supplier_cpf_cnpj IS NOT NULL AND p.cpf <> e.supplier_cpf_cnpj
            GROUP BY p.cpf, e.supplier_cpf_cnpj
            UNION ALL
            SELECT e.supplier_cpf_cnpj, p.cpf, 'payment', coalesce(sum(e.amount_cents), 0), count(*), false
            FROM campaign_expense e JOIN campaign_org co ON co.id = e.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE e.supplier_cpf_cnpj = ANY(?) AND p.cpf IS NOT NULL AND p.cpf <> e.supplier_cpf_cnpj
            GROUP BY e.supplier_cpf_cnpj, p.cpf
            """,
            ids,
            ids,
            ids,
            ids)) {
      out.add(
          new Incident(
              (String) r.get("anchor"),
              (String) r.get("other"),
              (String) r.get("kind"),
              ReadSupport.longOf(r.get("amount")),
              ReadSupport.longOf(r.get("n")),
              Boolean.TRUE.equals(r.get("src"))));
    }
    return out;
  }

  Map<String, Map<String, Object>> lookupNodes(
      Collection<String> rawIds, List<Map<String, Object>> edges) {
    LinkedHashSet<String> clean = new LinkedHashSet<>();
    for (String id : rawIds) if (id != null && !id.isEmpty()) clean.add(id);
    Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
    if (clean.isEmpty()) return nodes;
    String[] ids = clean.toArray(String[]::new);

    Set<String> sanctioned = new HashSet<>();
    for (Map<String, Object> r :
        db.list("SELECT DISTINCT cpf_cnpj FROM sanction WHERE cpf_cnpj = ANY(?)", (Object) ids))
      sanctioned.add((String) r.get("cpf_cnpj"));
    for (String id : clean) {
      boolean company = id.length() == 14;
      Map<String, Object> n = new LinkedHashMap<>();
      n.put("cpfCnpj", id);
      n.put("type", company ? "company" : "person");
      n.put("kind", company ? "company" : "person");
      n.put("label", company ? ReadSupport.formatCnpj(id) : id);
      n.put("sanctioned", sanctioned.contains(id));
      n.put("registryStatus", null);
      n.put("personId", null);
      n.put("photoUrl", null);
      nodes.put(id, n);
    }
    for (Map<String, Object> p :
        db.list(
            "SELECT id, cpf, canonical_name FROM people WHERE cpf = ANY(?) ORDER BY id",
            (Object) ids)) {
      Map<String, Object> n = nodes.get((String) p.get("cpf"));
      if (n == null || n.get("personId") != null) continue;
      n.put("label", p.get("canonical_name") != null ? p.get("canonical_name") : p.get("cpf"));
      n.put("kind", "politician");
      n.put("personId", p.get("id"));
    }
    for (Map<String, Object> c :
        db.list(
            """
            SELECT c.cnpj, c.kind, coalesce(cr.legal_name, c.legal_name) AS name,
                   cr.registry_status AS "registryStatus"
            FROM companies c LEFT JOIN company_registry cr ON cr.company_id = c.id
            WHERE c.cnpj = ANY(?)
            """,
            (Object) ids)) {
      Map<String, Object> n = nodes.get((String) c.get("cnpj"));
      if (n == null) continue;
      String cnpj = (String) c.get("cnpj");
      n.put("label", c.get("name") != null ? c.get("name") : ReadSupport.formatCnpj(cnpj));
      n.put("registryStatus", c.get("registryStatus"));
      if ("donor".equals(c.get("kind")) || "supplier".equals(c.get("kind")))
        n.put("kind", c.get("kind"));
    }
    for (Map<String, Object> e : edges) {
      String endpoint =
          "donation".equals(e.get("kind")) ? (String) e.get("source") : (String) e.get("target");
      Map<String, Object> n = nodes.get(endpoint);
      if (n != null && "person".equals(n.get("type")) && "person".equals(n.get("kind")))
        n.put("kind", "donation".equals(e.get("kind")) ? "donor" : "supplier");
    }
    Map<String, Map<String, Object>> owners = new LinkedHashMap<>();
    for (Map<String, Object> r :
        db.list(
            """
            SELECT DISTINCT c.cnpj, p.canonical_name AS name, p.id AS "personId" FROM campaign_org co
            JOIN companies c ON c.id = co.company_id JOIN people p ON p.id = co.person_id
            WHERE c.cnpj = ANY(?)
            """,
            (Object) ids)) owners.putIfAbsent((String) r.get("cnpj"), r);
    owners.forEach(
        (cnpj, owner) -> {
          Map<String, Object> n = nodes.get(cnpj);
          if (n == null) return;
          n.put("kind", "politician");
          if (owner.get("name") != null) n.put("label", owner.get("name"));
          n.put("personId", owner.get("personId"));
        });
    for (Map<String, Object> n : nodes.values())
      if (Boolean.TRUE.equals(n.get("sanctioned"))) n.put("kind", "sanctioned");
    Map<Long, String> photos =
        db.photoUrls(ReadSupport.longs(new ArrayList<>(nodes.values()), "personId"));
    for (Map<String, Object> n : nodes.values()) {
      Long pid = ReadSupport.longOf(n.get("personId"));
      if (pid != null) n.put("photoUrl", photos.get(pid));
    }
    return nodes;
  }

  @Override
  public Map<String, Object> paths(String newIdRaw, List<String> existingIdsRaw) {
    String newDigits = ReadSupport.digitsOnly(newIdRaw);
    if (!validDoc(newDigits)) return Map.of("nodes", List.of(), "edges", List.of());
    LinkedHashSet<String> existingDigits = new LinkedHashSet<>();
    for (String id : existingIdsRaw) {
      String d = ReadSupport.digitsOnly(id);
      if (validDoc(d)) existingDigits.add(d);
    }
    List<String> all = new ArrayList<>(List.of(newDigits));
    all.addAll(existingDigits);
    Map<String, Identity> idents = identities(all);
    String newId = canon(idents, newDigits);
    LinkedHashSet<String> existing = new LinkedHashSet<>();
    for (String d : existingDigits) {
      String c = canon(idents, d);
      if (!c.equals(newId)) existing.add(c);
    }
    Set<String> onCanvas = new HashSet<>(existing);
    onCanvas.add(newId);

    Map<String, List<String>> canonToAliases = new HashMap<>();
    for (Identity it : idents.values()) canonToAliases.put(it.canonical(), it.aliases());
    Map<String, String> aliasToCanon = new HashMap<>();
    canonToAliases.forEach((c, aliases) -> aliases.forEach(a -> aliasToCanon.put(a, c)));
    LinkedHashSet<String> anchorAliases = new LinkedHashSet<>();
    for (String c : onCanvasOrdered(newId, existing))
      anchorAliases.addAll(canonToAliases.getOrDefault(c, List.of(c)));

    List<Incident> raw = incidentEdges(anchorAliases);
    Map<String, Identity> otherIdents =
        identities(raw.stream().map(Incident::other).distinct().toList());
    Map<String, List<Incident>> fromNew = new LinkedHashMap<>();
    Map<String, List<Incident>> fromExisting = new LinkedHashMap<>();
    for (Incident r : raw) {
      Incident c =
          r.with(
              aliasToCanon.getOrDefault(r.anchor(), r.anchor()),
              otherIdents.containsKey(r.other())
                  ? otherIdents.get(r.other()).canonical()
                  : r.other());
      if (c.anchor().equals(c.other())) continue;
      (c.anchor().equals(newId) ? fromNew : fromExisting)
          .computeIfAbsent(c.other(), k -> new ArrayList<>())
          .add(c);
    }

    List<Map<String, Object>> edges = new ArrayList<>();
    LinkedHashSet<String> connectors = new LinkedHashSet<>();
    for (String e : existing)
      for (Incident r : fromNew.getOrDefault(e, List.of())) edges.add(r.toEdge());
    fromNew.forEach(
        (m, nEdges) -> {
          if (onCanvas.contains(m)) return;
          List<Incident> eEdges = fromExisting.get(m);
          if (eEdges == null || eEdges.isEmpty()) return;
          connectors.add(m);
          nEdges.forEach(r -> edges.add(r.toEdge()));
          eEdges.forEach(r -> edges.add(r.toEdge()));
        });

    List<String> allIds = new ArrayList<>(onCanvasOrdered(newId, existing));
    allIds.addAll(connectors);
    Map<String, Map<String, Object>> nodes = lookupNodes(allIds, edges);
    Set<String> seen = new HashSet<>();
    List<Map<String, Object>> deduped = new ArrayList<>();
    for (Map<String, Object> e : edges)
      if (seen.add(e.get("source") + "|" + e.get("target") + "|" + e.get("kind"))) deduped.add(e);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("nodes", new ArrayList<>(nodes.values()));
    out.put("edges", deduped);
    return out;
  }

  private static List<String> onCanvasOrdered(String newId, Collection<String> existing) {
    List<String> out = new ArrayList<>(List.of(newId));
    out.addAll(existing);
    return out;
  }

  private static String canon(Map<String, Identity> idents, String raw) {
    Identity i = idents.get(raw);
    return i == null ? raw : i.canonical();
  }

  @Override
  @Cacheable(cacheNames = "graph-node", unless = "#result == null")
  public Map<String, Object> resolveNode(String raw) {
    String digits = ReadSupport.digitsOnly(raw);
    if (!validDoc(digits)) return null;
    Identity ident = identities(List.of(digits)).get(digits);
    String canonical = ident == null ? digits : ident.canonical();
    Map<String, Object> node = lookupNodes(List.of(canonical), List.of()).get(canonical);
    if (node != null && ident != null && ident.name() != null) {
      node.put("label", ident.name());
      node.put("kind", "politician");
    }
    return node;
  }

  @Override
  @Cacheable(cacheNames = "graph-network")
  public Map<String, Object> nodeNetwork(String raw, int limit) {
    String digits = ReadSupport.digitsOnly(raw);
    if (!validDoc(digits))
      return Map.of("nodes", List.of(), "edges", List.of(), "truncated", false);
    Identity ident = identities(List.of(digits)).get(digits);
    String canonical = ident == null ? digits : ident.canonical();
    List<String> aliases = ident == null ? List.of(digits) : ident.aliases();

    List<Incident> raw0 = incidentEdges(aliases);
    Map<String, Identity> otherIdents =
        identities(raw0.stream().map(Incident::other).distinct().toList());
    Map<String, Incident> merged = new LinkedHashMap<>();
    for (Incident r : raw0) {
      String other =
          otherIdents.containsKey(r.other()) ? otherIdents.get(r.other()).canonical() : r.other();
      if (canonical.equals(other)) continue;
      String key = r.kind() + "|" + r.anchorIsSource() + "|" + other;
      Incident cur = merged.get(key);
      merged.put(
          key,
          cur == null
              ? r.with(canonical, other)
              : new Incident(
                  canonical,
                  other,
                  r.kind(),
                  cur.amountCents() + r.amountCents(),
                  cur.n() + r.n(),
                  r.anchorIsSource()));
    }
    List<Incident> rows = new ArrayList<>(merged.values());
    boolean truncated = rows.size() > limit;
    if (truncated) {
      rows.sort((a, b) -> Long.compare(b.amountCents(), a.amountCents()));
      rows = new ArrayList<>(rows.subList(0, limit));
    }
    List<Map<String, Object>> edges = rows.stream().map(Incident::toEdge).toList();
    LinkedHashSet<String> allIds = new LinkedHashSet<>(List.of(canonical));
    for (Map<String, Object> e : edges) {
      allIds.add((String) e.get("source"));
      allIds.add((String) e.get("target"));
    }
    Map<String, Map<String, Object>> nodes = lookupNodes(allIds, edges);
    if (ident != null && ident.name() != null && nodes.containsKey(canonical)) {
      nodes.get(canonical).put("label", ident.name());
      nodes.get(canonical).put("kind", "politician");
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("nodes", new ArrayList<>(nodes.values()));
    out.put("edges", new ArrayList<>(edges));
    out.put("truncated", truncated);
    return out;
  }
}
