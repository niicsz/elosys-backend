package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.domain.company.CompanyPartnerRow;
import com.binitech.elosys.domain.company.CompanyRegistryRow;
import com.binitech.elosys.domain.company.CompanyTarget;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class CompanyJdbcAdapter implements CompanyRepositoryPort {
  private static final String MONEY_RANKED =
      """
      WITH m AS (
        SELECT supplier_company_id AS cid, sum(amount_cents) AS tot
        FROM campaign_expense WHERE supplier_company_id IS NOT NULL GROUP BY supplier_company_id
        UNION ALL
        SELECT donor_company_id AS cid, sum(amount_cents) AS tot
        FROM campaign_donation WHERE donor_company_id IS NOT NULL GROUP BY donor_company_id
      ),
      agg AS (SELECT cid, sum(tot) AS money FROM m GROUP BY cid)
      SELECT c.id, c.cnpj
      FROM companies c
      LEFT JOIN company_registry r ON r.company_id = c.id
      LEFT JOIN agg ON agg.cid = c.id
      WHERE r.id IS NULL %s
      ORDER BY coalesce(agg.money, 0) DESC
      LIMIT ?
      """;
  private static final String NOT_CAMPAIGN = "AND c.kind IS DISTINCT FROM 'campaign'";

  private final JdbcClient jdbc;
  private final JdbcTemplate template;
  private final IdentitySequences sequences;
  private final StreamingQueries streaming;

  public CompanyJdbcAdapter(
      JdbcClient jdbc,
      JdbcTemplate template,
      IdentitySequences sequences,
      StreamingQueries streaming) {
    this.jdbc = jdbc;
    this.template = template;
    this.sequences = sequences;
    this.streaming = streaming;
  }

  @Override
  public Map<String, Long> idsByCnpj() {
    Map<String, Long> out = new HashMap<>();
    streaming.forEach(
        "SELECT cnpj, id FROM companies", rs -> out.put(rs.getString("cnpj"), rs.getLong("id")));
    return out;
  }

  @Override
  public long[] reserveIds(int count) {
    return sequences.reserve("companies", count);
  }

  @Override
  public void insert(List<NewCompany> companies) {
    OffsetDateTime now = Jdbc.now();
    template.batchUpdate(
        "INSERT INTO companies (id, cnpj, legal_name, kind, created_at) VALUES (?, ?, NULL, ?, ?)",
        companies,
        5_000,
        (ps, c) -> {
          ps.setLong(1, c.id());
          ps.setString(2, c.cnpj());
          Jdbc.setString(ps, 3, c.kind());
          ps.setObject(4, now);
        });
  }

  @Override
  public void deleteByKinds(Collection<String> kinds) {
    jdbc.sql(
            """
            DELETE FROM companies c WHERE c.kind = ANY(?)
              AND NOT EXISTS (SELECT 1 FROM company_registry r WHERE r.company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM company_partner p WHERE p.company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM candidate_supplier_partner s WHERE s.company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM sanction s WHERE s.company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM parliamentary_earmark_beneficiary b
                              WHERE b.beneficiary_company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM campaign_org o WHERE o.company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM campaign_donation d WHERE d.donor_company_id = c.id)
              AND NOT EXISTS (SELECT 1 FROM campaign_expense e WHERE e.supplier_company_id = c.id)
            """)
        .param(kinds.toArray(String[]::new))
        .update();
  }

  @Override
  public long countByKinds(Collection<String> kinds) {
    return jdbc.sql("SELECT count(*) FROM companies WHERE kind = ANY(?)")
        .param(kinds.toArray(String[]::new))
        .query(Long.class)
        .single();
  }

  @Override
  public List<CompanyTarget> missingRegistryByMoney(int limit, boolean includeCampaign) {
    return jdbc.sql(MONEY_RANKED.formatted(includeCampaign ? "" : NOT_CAMPAIGN))
        .param(limit)
        .query((rs, i) -> new CompanyTarget(rs.getLong("id"), rs.getString("cnpj")))
        .list();
  }

  @Override
  public List<CompanyTarget> missingRegistryById(int limit, boolean includeCampaign) {
    return jdbc.sql(
            "SELECT c.id, c.cnpj FROM companies c "
                + "LEFT JOIN company_registry r ON r.company_id = c.id "
                + "WHERE r.id IS NULL "
                + (includeCampaign ? "" : NOT_CAMPAIGN)
                + " ORDER BY c.id LIMIT ?")
        .param(limit)
        .query((rs, i) -> new CompanyTarget(rs.getLong("id"), rs.getString("cnpj")))
        .list();
  }

  @Override
  public List<CompanyTarget> findByCnpjs(Collection<String> cnpjs) {
    return jdbc.sql("SELECT id, cnpj FROM companies WHERE cnpj = ANY(?)")
        .param(cnpjs.toArray(String[]::new))
        .query((rs, i) -> new CompanyTarget(rs.getLong("id"), rs.getString("cnpj")))
        .list();
  }

  @Override
  public void upsertRegistry(CompanyRegistryRow r) {
    jdbc.sql(
            """
            INSERT INTO company_registry (company_id, cnpj, legal_name, trade_name, opened_at,
                registry_status, registry_status_date, legal_nature, primary_cnae,
                share_capital_cents, size, city, state, provenance_id, collected_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (company_id) DO UPDATE SET
                cnpj = excluded.cnpj, legal_name = excluded.legal_name,
                trade_name = excluded.trade_name, opened_at = excluded.opened_at,
                registry_status = excluded.registry_status,
                registry_status_date = excluded.registry_status_date,
                legal_nature = excluded.legal_nature, primary_cnae = excluded.primary_cnae,
                share_capital_cents = excluded.share_capital_cents, size = excluded.size,
                city = excluded.city, state = excluded.state,
                provenance_id = excluded.provenance_id, collected_at = excluded.collected_at
            """)
        .params(
            r.companyId(),
            r.cnpj(),
            r.legalName(),
            r.tradeName(),
            r.openedAt(),
            r.registryStatus(),
            r.registryStatusDate(),
            r.legalNature(),
            r.primaryCnae(),
            r.shareCapitalCents(),
            r.size(),
            r.city(),
            r.state(),
            r.provenanceId(),
            Jdbc.now())
        .update();
  }

  @Override
  public void fillLegalNameIfMissing(long companyId, String legalName) {
    jdbc.sql("UPDATE companies SET legal_name = ? WHERE id = ? AND legal_name IS NULL")
        .params(legalName, companyId)
        .update();
  }

  @Override
  public void insertPartnersIgnoringDuplicates(List<CompanyPartnerRow> partners) {
    if (partners.isEmpty()) return;
    OffsetDateTime now = Jdbc.now();
    template.batchUpdate(
        """
        INSERT INTO company_partner (company_id, cnpj, partner_name, partner_doc_masked, role,
                                     entry_date, provenance_id, collected_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
        """,
        partners,
        1_000,
        (ps, p) -> {
          ps.setLong(1, p.companyId());
          ps.setString(2, p.cnpj());
          ps.setString(3, p.partnerName());
          Jdbc.setString(ps, 4, p.partnerDocMasked());
          Jdbc.setString(ps, 5, p.role());
          Jdbc.setString(ps, 6, p.entryDate());
          ps.setLong(7, p.provenanceId());
          ps.setObject(8, now);
        });
  }

  @Override
  public long countMissingRegistry() {
    return jdbc.sql(
            "SELECT count(*) FROM companies c LEFT JOIN company_registry r ON r.company_id = c.id"
                + " WHERE r.id IS NULL")
        .query(Long.class)
        .single();
  }

  @Override
  public long countRegistries() {
    return jdbc.sql("SELECT count(*) FROM company_registry").query(Long.class).single();
  }

  @Override
  public long countPartners() {
    return jdbc.sql("SELECT count(*) FROM company_partner").query(Long.class).single();
  }
}
