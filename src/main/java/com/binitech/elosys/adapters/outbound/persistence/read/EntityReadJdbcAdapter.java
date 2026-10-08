package com.binitech.elosys.adapters.outbound.persistence.read;

import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.PROVENANCE_COLUMNS;
import static com.binitech.elosys.adapters.outbound.persistence.read.ReadSupport.PROVENANCE_JOIN;

import com.binitech.elosys.application.ports.outbound.ReadModelPorts.EntityReadPort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts.FinanceQuery;
import com.binitech.elosys.domain.SourceValues;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

@Component
public class EntityReadJdbcAdapter implements EntityReadPort {
  static final int FINANCE_PAGE_SIZE = 25;
  static final int EARMARK_PAGE_SIZE = 50;

  private final ReadSupport db;

  public EntityReadJdbcAdapter(ReadSupport db) {
    this.db = db;
  }

  @Override
  @Cacheable(cacheNames = "candidate-person-id", unless = "#result == null")
  public Long candidatePersonId(String digits) {
    if (digits.length() != 11 && digits.length() != 14) return null;
    Map<String, Object> row =
        digits.length() == 11
            ? db.one("SELECT id FROM people WHERE cpf = ? ORDER BY id LIMIT 1", digits)
            : db.one(
                """
                SELECT p.id FROM campaign_org co JOIN companies c ON c.id = co.company_id
                JOIN people p ON p.id = co.person_id WHERE c.cnpj = ? LIMIT 1
                """,
                digits);
    return row == null ? null : ReadSupport.longOf(row.get("id"));
  }

  @Override
  @Cacheable(cacheNames = "entity-profile", unless = "#result == null")
  public Map<String, Object> entityProfile(String digits, Integer year) {
    if (digits.length() != 11 && digits.length() != 14) return null;
    boolean isCompany = digits.length() == 14;
    Long personId = null;
    String companyKind = null;
    String displayName = null;
    Map<String, Object> registry = null;
    List<Map<String, Object>> partners = List.of();

    if (isCompany) {
      Map<String, Object> company =
          db.one("SELECT kind, legal_name FROM companies WHERE cnpj = ?", digits);
      if (company != null) {
        companyKind = (String) company.get("kind");
        displayName = (String) company.get("legal_name");
      }
      registry =
          db.one(
              """
              SELECT t.legal_name AS "legalName", t.trade_name AS "tradeName",
                     t.opened_at AS "openedAt", t.registry_status AS "registryStatus",
                     t.legal_nature AS "legalNature", t.primary_cnae AS "primaryCnae",
                     t.share_capital_cents AS "shareCapitalCents", t.size, t.city, t.state,
              """
                  + PROVENANCE_COLUMNS
                  + " FROM company_registry t "
                  + PROVENANCE_JOIN
                  + " WHERE t.cnpj = ? LIMIT 1",
              digits);
      if (registry != null) {
        ReadSupport.withProvenance(registry);
        if (registry.get("legalName") != null) displayName = (String) registry.get("legalName");
      }
      partners =
          db.list(
              """
              SELECT id, partner_name AS "partnerName", role, entry_date AS "entryDate"
              FROM company_partner WHERE cnpj = ? ORDER BY entry_date DESC NULLS LAST
              """,
              digits);
    } else {
      Map<String, Object> person =
          db.one("SELECT id, canonical_name FROM people WHERE cpf = ? ORDER BY id LIMIT 1", digits);
      if (person != null) {
        personId = ReadSupport.longOf(person.get("id"));
        displayName = (String) person.get("canonical_name");
      }
    }

    String yearClause = year != null ? " AND year = ?" : "";
    Object[] args = year != null ? new Object[] {digits, year} : new Object[] {digits};
    Map<String, Object> donations =
        db.one(
            "SELECT count(*) AS n, coalesce(sum(amount_cents), 0) AS total FROM campaign_donation"
                + " WHERE donor_cpf_cnpj = ?"
                + yearClause,
            args);
    Map<String, Object> payments =
        db.one(
            "SELECT count(*) AS n, coalesce(sum(amount_cents), 0) AS total FROM campaign_expense"
                + " WHERE supplier_cpf_cnpj = ?"
                + yearClause,
            args);

    if (personId == null && companyKind == null && registry == null) {
      boolean any =
          db.count(
                  """
                  SELECT (EXISTS (SELECT 1 FROM campaign_donation WHERE donor_cpf_cnpj = ?)
                       OR EXISTS (SELECT 1 FROM campaign_expense WHERE supplier_cpf_cnpj = ?)
                       OR EXISTS (SELECT 1 FROM sanction WHERE cpf_cnpj = ?))::int
                  """,
                  digits,
                  digits,
                  digits)
              > 0;
      if (!any) return null;
    }

    List<Map<String, Object>> sanctions =
        db
            .list(
                """
                SELECT t.id, t.registry, t.category, t.fine_amount_cents AS "fineAmountCents",
                       t.start_date AS "startDate", t.end_date AS "endDate",
                       t.sanctioning_agency AS "sanctioningAgency",
                       t.agency_sphere AS "agencySphere",
                """
                    + PROVENANCE_COLUMNS
                    + " FROM sanction t "
                    + PROVENANCE_JOIN
                    + " WHERE t.cpf_cnpj = ? ORDER BY t.start_date DESC NULLS LAST",
                digits)
            .stream()
            .map(ReadSupport::withProvenance)
            .toList();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("cpfCnpj", digits);
    out.put("isCompany", isCompany);
    out.put("displayName", displayName);
    out.put("personId", personId);
    out.put("companyKind", companyKind);
    out.put("registry", registry);
    out.put("partners", partners);
    out.put(
        "donationsGivenTotal",
        Map.of("count", donations.get("n"), "totalCents", donations.get("total")));
    out.put(
        "paymentsReceivedTotal",
        Map.of("count", payments.get("n"), "totalCents", payments.get("total")));
    out.put("sanctions", sanctions);
    return out;
  }

  @Override
  @Cacheable(cacheNames = "company-earmarks")
  public Map<String, Object> companyEarmarks(String cnpj) {
    List<Map<String, Object>> rows =
        db.list(
            """
            WITH agg AS (
              SELECT earmark_code, sum(amount_cents) AS amount_cents, count(*) AS months_count,
                     min(state) AS state, min(municipality) AS municipality
              FROM parliamentary_earmark_beneficiary
              WHERE beneficiary_doc = ? AND earmark_code <> 'Sem informação'
              GROUP BY earmark_code
            ),
            author AS (
              SELECT DISTINCT ON (earmark_code) earmark_code, author_name, author_person_id, year
              FROM parliamentary_earmark ORDER BY earmark_code, id
            )
            SELECT agg.earmark_code AS "earmarkCode", author.year AS "earmarkYear",
                   author.author_name AS "authorName", author.author_person_id AS "authorPersonId",
                   agg.amount_cents AS "amountCents", agg.months_count AS "monthsCount",
                   agg.state, agg.municipality
            FROM agg LEFT JOIN author ON author.earmark_code = agg.earmark_code
            ORDER BY agg.amount_cents DESC
            """,
            cnpj);
    long total = 0;
    for (Map<String, Object> r : rows) total += ReadSupport.longOf(r.get("amountCents"));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("earmarks", rows);
    out.put("totalCents", total);
    return out;
  }

  @Override
  @Cacheable(cacheNames = "finance-page")
  public Map<String, Object> financePage(FinanceQuery p) {
    int page = Math.max(1, p.page());
    int offset = (page - 1) * FINANCE_PAGE_SIZE;
    String q = p.q() == null ? "" : p.q().strip();
    String dir = p.ascending() ? "ASC" : "DESC";

    String dateCol;
    String paidExpr;
    String from;
    String select;
    String nameExpr;
    StringBuilder where;
    List<Object> args = new ArrayList<>();
    String politicianOwned = "false";
    String paidSubquery =
        "(SELECT coalesce(sum(x.amount_cents), 0) FROM campaign_expense_payment x"
            + " WHERE x.campaign_expense_id = t.id)";

    if (p.scope().equals("candidate")) {
      boolean received = p.dir().equals("received");
      String docCol = received ? "t.donor_cpf_cnpj" : "t.supplier_cpf_cnpj";
      dateCol = received ? "t.receipt_date" : "t.expense_date";
      paidExpr = received ? "NULL::bigint" : paidSubquery;
      nameExpr = received ? "t.donor_name" : "t.supplier_name";
      politicianOwned =
          "EXISTS (SELECT 1 FROM candidate_supplier_partner csp JOIN companies comp ON comp.id ="
              + " csp.company_id WHERE comp.cnpj = "
              + docCol
              + ")";
      from =
          (received ? "campaign_donation" : "campaign_expense")
              + " t JOIN campaign_org co ON co.id = t.campaign_org_id"
              + " LEFT JOIN company_registry cr ON cr.cnpj = "
              + docCol
              + " "
              + PROVENANCE_JOIN;
      select =
          "t.id, t.year, "
              + dateCol
              + " AS date, t.amount_cents AS \"amountCents\", "
              + paidExpr
              + " AS \"paidCents\", "
              + nameExpr
              + " AS \"counterpartyName\", "
              + docCol
              + " AS \"counterpartyDoc\", NULL::bigint AS \"counterpartyPersonId\","
              + " cr.opened_at AS \"counterpartyOpenedAt\", "
              + (received ? "t.origin" : "t.description")
              + " AS detail, "
              + politicianOwned
              + " AS \"counterpartyIsPoliticianOwned\", "
              + PROVENANCE_COLUMNS;
      where = new StringBuilder("co.person_id = ?");
      args.add(Long.parseLong(p.id()));
      if (!q.isEmpty()) {
        if (received) {
          where.append(" AND t.donor_name ILIKE ?");
          args.add(ReadSupport.like(q));
        } else {
          where.append(" AND (t.supplier_name ILIKE ? OR t.description ILIKE ?)");
          args.add(ReadSupport.like(q));
          args.add(ReadSupport.like(q));
        }
      }
    } else {
      boolean given = p.dir().equals("given");
      dateCol = given ? "t.receipt_date" : "t.expense_date";
      paidExpr = given ? "NULL::bigint" : paidSubquery;
      nameExpr = "p.canonical_name";
      from =
          (given ? "campaign_donation" : "campaign_expense")
              + " t JOIN campaign_org co ON co.id = t.campaign_org_id"
              + " LEFT JOIN people p ON p.id = co.person_id "
              + PROVENANCE_JOIN;
      select =
          "t.id, t.year, "
              + dateCol
              + " AS date, t.amount_cents AS \"amountCents\", "
              + paidExpr
              + " AS \"paidCents\", p.canonical_name AS \"counterpartyName\","
              + " t.cnpj AS \"counterpartyDoc\", co.person_id AS \"counterpartyPersonId\","
              + " NULL::text AS \"counterpartyOpenedAt\", t.origin AS detail,"
              + " false AS \"counterpartyIsPoliticianOwned\", "
              + PROVENANCE_COLUMNS;
      where = new StringBuilder(given ? "t.donor_cpf_cnpj = ?" : "t.supplier_cpf_cnpj = ?");
      args.add(ReadSupport.digitsOnly(p.id()));
      if (!q.isEmpty()) {
        String norm = SourceValues.normalizeName(q);
        where.append(" AND p.canonical_name LIKE ?");
        args.add(ReadSupport.like(norm == null ? "" : norm));
      }
    }

    if (p.year() != null) {
      where.append(" AND t.year = ?");
      args.add(p.year());
    }
    if (p.dateFrom() != null) {
      where.append(" AND ").append(dateCol).append(" >= ?");
      args.add(p.dateFrom());
    }
    if (p.dateTo() != null) {
      where.append(" AND ").append(dateCol).append(" <= ?");
      args.add(p.dateTo());
    }
    if (p.amountMinCents() != null || p.amountMaxCents() != null) {
      long min = p.amountMinCents() == null ? 0 : p.amountMinCents();
      long max = p.amountMaxCents() == null ? Long.MAX_VALUE : p.amountMaxCents();
      where
          .append(" AND ((t.amount_cents BETWEEN ? AND ?) OR (")
          .append(paidExpr)
          .append(" IS NOT NULL AND (")
          .append(paidExpr)
          .append(") BETWEEN ? AND ?))");
      args.add(min);
      args.add(max);
      args.add(min);
      args.add(max);
    }
    if (p.onlyPoliticianOwned() && p.scope().equals("candidate"))
      where.append(" AND ").append(politicianOwned);

    String sortExpr =
        switch (p.sort() == null ? "amount" : p.sort()) {
          case "paid" -> paidExpr;
          case "year" -> "t.year";
          case "date" -> dateCol;
          case "name" -> nameExpr;
          default -> "t.amount_cents";
        };

    long total = db.count("SELECT count(*) FROM " + from + " WHERE " + where, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(FINANCE_PAGE_SIZE);
    pageArgs.add(offset);
    List<Map<String, Object>> rows =
        db.list(
            "SELECT "
                + select
                + " FROM "
                + from
                + " WHERE "
                + where
                + " ORDER BY ("
                + sortExpr
                + ") IS NULL, "
                + sortExpr
                + " "
                + dir
                + ", t.id "
                + dir
                + " LIMIT ? OFFSET ?",
            pageArgs.toArray());
    Map<Long, String> photos = db.photoUrls(ReadSupport.longs(rows, "counterpartyPersonId"));
    for (Map<String, Object> r : rows) {
      ReadSupport.withProvenance(r);
      if (r.get("amountCents") == null) r.put("amountCents", 0L);
      Long pid = ReadSupport.longOf(r.get("counterpartyPersonId"));
      r.put("counterpartyPhotoUrl", pid == null ? null : photos.get(pid));
    }
    Map<String, Object> out = ReadSupport.page(rows, total);
    out.put("pageSize", FINANCE_PAGE_SIZE);
    return out;
  }

  @Override
  @Cacheable(cacheNames = "earmark-payments")
  public Map<String, Object> earmarkPayments(int page, String rawQ, boolean ascending) {
    int p = Math.max(1, page);
    int offset = (p - 1) * EARMARK_PAGE_SIZE;
    String q = rawQ == null ? "" : rawQ.strip();
    String where = q.isEmpty() ? "" : " WHERE company_name ILIKE ? OR author_name ILIKE ?";
    List<Object> args = new ArrayList<>();
    if (!q.isEmpty()) {
      args.add(ReadSupport.like(q));
      args.add(ReadSupport.like(q));
    }
    long total =
        db.count("SELECT count(*) FROM mv_earmark_company_payments" + where, args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(EARMARK_PAGE_SIZE);
    pageArgs.add(offset);
    List<Map<String, Object>> rows =
        db.list(
            "SELECT earmark_code AS \"earmarkCode\", year, author_name AS \"authorName\","
                + " author_person_id AS \"authorPersonId\", company_name AS \"companyName\","
                + " company_cnpj AS \"companyCnpj\", amount_cents AS \"amountCents\""
                + " FROM mv_earmark_company_payments"
                + where
                + " ORDER BY amount_cents "
                + (ascending ? "ASC" : "DESC")
                + ", earmark_code, company_cnpj LIMIT ? OFFSET ?",
            pageArgs.toArray());
    Map<Long, String> photos = db.photoUrls(ReadSupport.longs(rows, "authorPersonId"));
    for (Map<String, Object> r : rows) {
      Long pid = ReadSupport.longOf(r.get("authorPersonId"));
      r.put("authorPhotoUrl", pid == null ? null : photos.get(pid));
    }
    return ReadSupport.page(rows, total);
  }
}
