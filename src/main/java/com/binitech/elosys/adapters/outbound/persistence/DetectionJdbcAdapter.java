package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.DetectionDataPort;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class DetectionJdbcAdapter implements DetectionDataPort {
  private final JdbcClient jdbc;
  private final StreamingQueries streaming;

  public DetectionJdbcAdapter(JdbcClient jdbc, StreamingQueries streaming) {
    this.jdbc = jdbc;
    this.streaming = streaming;
  }

  @Override
  public List<ExpenseCandidate> expensesMatchingAny(Collection<String> keywords) {
    String[] patterns = keywords.stream().map(k -> "%" + k + "%").toArray(String[]::new);
    return jdbc.sql(
            """
            SELECT ce.id, ce.amount_cents, ce.description, ce.year, ce.cnpj, ce.supplier_name,
                   ce.supplier_company_id, ce.supplier_person_id,
                   co.person_id AS candidate_person_id
            FROM campaign_expense ce
            LEFT JOIN campaign_org co ON co.id = ce.campaign_org_id
            WHERE ce.description LIKE ANY(?)
            ORDER BY ce.id
            """)
        .param(patterns)
        .query(
            (rs, i) ->
                new ExpenseCandidate(
                    rs.getLong("id"),
                    Jdbc.getLong(rs, "amount_cents"),
                    rs.getString("description"),
                    rs.getInt("year"),
                    rs.getString("cnpj"),
                    rs.getString("supplier_name"),
                    Jdbc.getLong(rs, "supplier_company_id"),
                    Jdbc.getLong(rs, "supplier_person_id"),
                    Jdbc.getLong(rs, "candidate_person_id")))
        .list();
  }

  @Override
  public Map<Long, String> campaignOrgCpfs() {
    Map<Long, String> out = new HashMap<>();
    streaming.forEach(
        "SELECT co.id, p.cpf FROM campaign_org co JOIN people p ON p.id = co.person_id"
            + " WHERE p.cpf IS NOT NULL",
        rs -> out.put(rs.getLong(1), rs.getString(2)));
    return out;
  }

  @Override
  public void forEachDonationEdge(Consumer<LedgerEdge> consumer) {
    streaming.forEach(
        "SELECT campaign_org_id, donor_cpf_cnpj, amount_cents FROM campaign_donation"
            + " WHERE donor_cpf_cnpj IS NOT NULL AND campaign_org_id IS NOT NULL ORDER BY id",
        rs ->
            consumer.accept(
                new LedgerEdge(rs.getLong(1), rs.getString(2), Jdbc.getLong(rs, "amount_cents"))));
  }

  @Override
  public void forEachExpenseEdge(Consumer<LedgerEdge> consumer) {
    streaming.forEach(
        "SELECT campaign_org_id, supplier_cpf_cnpj, amount_cents FROM campaign_expense"
            + " WHERE supplier_cpf_cnpj IS NOT NULL AND campaign_org_id IS NOT NULL ORDER BY id",
        rs ->
            consumer.accept(
                new LedgerEdge(rs.getLong(1), rs.getString(2), Jdbc.getLong(rs, "amount_cents"))));
  }

  @Override
  public String personNameByCpf(String cpf) {
    return jdbc.sql("SELECT canonical_name FROM people WHERE cpf = ? ORDER BY id LIMIT 1")
        .param(cpf)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Override
  public String companyNameByCnpj(String cnpj) {
    return jdbc.sql(
            "SELECT coalesce(cr.legal_name, c.legal_name) FROM companies c"
                + " LEFT JOIN company_registry cr ON cr.company_id = c.id WHERE c.cnpj = ?")
        .param(cnpj)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  @Override
  public Long personIdByCpf(String cpf) {
    return jdbc.sql("SELECT id FROM people WHERE cpf = ? ORDER BY id LIMIT 1")
        .param(cpf)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  @Override
  public Long companyIdByCnpj(String cnpj) {
    return jdbc.sql("SELECT id FROM companies WHERE cnpj = ?")
        .param(cnpj)
        .query(Long.class)
        .optional()
        .orElse(null);
  }

  @Override
  public List<Long> donationIdsBetween(String donorDoc, String candidateCpf, int limit) {
    return jdbc.sql(
            """
            SELECT d.id FROM campaign_donation d
            JOIN campaign_org co ON co.id = d.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE d.donor_cpf_cnpj = ? AND p.cpf = ? ORDER BY d.id LIMIT ?
            """)
        .params(donorDoc, candidateCpf, limit)
        .query(Long.class)
        .list();
  }

  @Override
  public List<Long> expenseIdsBetween(String candidateCpf, String supplierDoc, int limit) {
    return jdbc.sql(
            """
            SELECT e.id FROM campaign_expense e
            JOIN campaign_org co ON co.id = e.campaign_org_id
            JOIN people p ON p.id = co.person_id
            WHERE p.cpf = ? AND e.supplier_cpf_cnpj = ? ORDER BY e.id LIMIT ?
            """)
        .params(candidateCpf, supplierDoc, limit)
        .query(Long.class)
        .list();
  }

  @Override
  public List<PartnerOfSupplier> partnersOfPaidSuppliers() {
    return jdbc.sql(
            """
            SELECT cp.id, cp.company_id, cp.partner_name, cp.partner_doc_masked, cp.role,
                   cp.entry_date
            FROM company_partner cp
            WHERE cp.partner_doc_masked LIKE '***%**'
              AND EXISTS (SELECT 1 FROM campaign_expense e WHERE e.supplier_company_id = cp.company_id)
            ORDER BY cp.entry_date NULLS FIRST, cp.id
            """)
        .query(
            (rs, i) ->
                new PartnerOfSupplier(
                    rs.getLong("id"),
                    rs.getLong("company_id"),
                    rs.getString("partner_name"),
                    rs.getString("partner_doc_masked"),
                    rs.getString("role"),
                    rs.getString("entry_date")))
        .list();
  }

  @Override
  public SupplierPayments supplierPayments(long companyId) {
    return jdbc.sql(
            """
            SELECT count(*) AS n, coalesce(sum(e.amount_cents), 0) AS total,
                   count(DISTINCT co.person_id) AS payers
            FROM campaign_expense e JOIN campaign_org co ON co.id = e.campaign_org_id
            WHERE e.supplier_company_id = ?
            """)
        .param(companyId)
        .query(
            (rs, i) ->
                new SupplierPayments(rs.getLong("n"), rs.getLong("total"), rs.getLong("payers")))
        .single();
  }

  @Override
  public boolean supplierPaidByPerson(long companyId, long personId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM campaign_expense e
                           JOIN campaign_org co ON co.id = e.campaign_org_id
                           WHERE e.supplier_company_id = ? AND co.person_id = ?)
            """)
        .params(companyId, personId)
        .query(Boolean.class)
        .single();
  }

  @Override
  public void clearCandidateSupplierPartners() {
    jdbc.sql("DELETE FROM candidate_supplier_partner").update();
  }

  @Override
  public void insertCandidateSupplierPartner(CandidateSupplierPartner r) {
    jdbc.sql(
            """
            INSERT INTO candidate_supplier_partner (person_id, company_id, company_partner_id,
                match_basis, partner_role, partner_since, payments_total_cents, payments_count,
                payer_candidacies, paid_by_self, computed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
            """)
        .params(
            r.personId(),
            r.companyId(),
            r.companyPartnerId(),
            r.matchBasis(),
            r.partnerRole(),
            r.partnerSince(),
            r.paymentsTotalCents(),
            r.paymentsCount(),
            r.payerCandidacies(),
            r.paidBySelf(),
            Jdbc.now())
        .update();
  }

  @Override
  public Map<Boolean, Long> candidateSupplierPartnersByPaidBySelf() {
    Map<Boolean, Long> out = new LinkedHashMap<>();
    jdbc.sql(
            "SELECT paid_by_self, count(*) FROM candidate_supplier_partner GROUP BY paid_by_self"
                + " ORDER BY paid_by_self")
        .query(
            rs -> {
              out.put(rs.getBoolean(1), rs.getLong(2));
            });
    return out;
  }
}
