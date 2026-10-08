package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.CampaignFinanceRepositoryPort;
import com.binitech.elosys.domain.finance.CampaignOrgRow;
import com.binitech.elosys.domain.finance.DonationRow;
import com.binitech.elosys.domain.finance.ExpenseRow;
import com.binitech.elosys.domain.finance.PaymentRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class CampaignFinanceJdbcAdapter implements CampaignFinanceRepositoryPort {
  private static final List<String> ORG_COLUMNS =
      List.of(
          "company_id",
          "person_id",
          "cnpj",
          "tse_candidacy_id",
          "accountant_id",
          "year",
          "candidate_cpf",
          "candidate_name",
          "normalized_name",
          "office",
          "party_abbr",
          "state",
          "provenance_id",
          "collected_at");
  private static final List<String> DONATION_COLUMNS =
      List.of(
          "cnpj",
          "tse_candidacy_id",
          "year",
          "tse_receipt_id",
          "receipt_number",
          "document_id",
          "receipt_date",
          "amount_cents",
          "source",
          "origin",
          "species",
          "donor_cpf_cnpj",
          "donor_name",
          "donor_name_rfb",
          "donor_cnae",
          "donor_state",
          "donor_municipality",
          "donor_tse_candidacy_id",
          "donor_party_abbr",
          "donor_person_id",
          "donor_company_id",
          "provenance_id",
          "collected_at");
  private static final List<String> EXPENSE_COLUMNS =
      List.of(
          "cnpj",
          "tse_candidacy_id",
          "year",
          "tse_expense_id",
          "document_type",
          "document_number",
          "expense_date",
          "amount_cents",
          "origin",
          "description",
          "supplier_cpf_cnpj",
          "supplier_name",
          "supplier_name_rfb",
          "supplier_type",
          "supplier_cnae",
          "supplier_state",
          "supplier_municipality",
          "supplier_tse_candidacy_id",
          "supplier_party_abbr",
          "supplier_person_id",
          "supplier_company_id",
          "provenance_id",
          "collected_at");
  private static final List<String> PAYMENT_COLUMNS =
      List.of(
          "tse_expense_id",
          "tse_installment_id",
          "accountant_id",
          "year",
          "state",
          "document_type",
          "document_number",
          "payment_date",
          "amount_cents",
          "source",
          "origin",
          "nature",
          "species",
          "description",
          "provenance_id",
          "collected_at");

  private final JdbcClient jdbc;
  private final CopyWriter copy;
  private final TransactionTemplate tx;

  public CampaignFinanceJdbcAdapter(JdbcClient jdbc, CopyWriter copy, TransactionTemplate tx) {
    this.jdbc = jdbc;
    this.copy = copy;
    this.tx = tx;
  }

  @Override
  public int insertOrgs(List<CampaignOrgRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "campaign_org",
        ORG_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.companyId(),
                      r.personId(),
                      r.cnpj(),
                      r.tseCandidacyId(),
                      r.accountantId(),
                      r.year(),
                      r.candidateCpf(),
                      r.candidateName(),
                      r.normalizedName(),
                      r.office(),
                      r.partyAbbr(),
                      r.state(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public int insertDonations(List<DonationRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "campaign_donation",
        DONATION_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.cnpj(),
                      r.tseCandidacyId(),
                      r.year(),
                      r.tseReceiptId(),
                      r.receiptNumber(),
                      r.documentId(),
                      r.receiptDate(),
                      r.amountCents(),
                      r.source(),
                      r.origin(),
                      r.species(),
                      r.donorCpfCnpj(),
                      r.donorName(),
                      r.donorNameRfb(),
                      r.donorCnae(),
                      r.donorState(),
                      r.donorMunicipality(),
                      r.donorTseCandidacyId(),
                      r.donorPartyAbbr(),
                      r.donorPersonId(),
                      r.donorCompanyId(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public int insertExpenses(List<ExpenseRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "campaign_expense",
        EXPENSE_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.cnpj(),
                      r.tseCandidacyId(),
                      r.year(),
                      r.tseExpenseId(),
                      r.documentType(),
                      r.documentNumber(),
                      r.expenseDate(),
                      r.amountCents(),
                      r.origin(),
                      r.description(),
                      r.supplierCpfCnpj(),
                      r.supplierName(),
                      r.supplierNameRfb(),
                      r.supplierType(),
                      r.supplierCnae(),
                      r.supplierState(),
                      r.supplierMunicipality(),
                      r.supplierTseCandidacyId(),
                      r.supplierPartyAbbr(),
                      r.supplierPersonId(),
                      r.supplierCompanyId(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public int insertPayments(List<PaymentRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "campaign_expense_payment",
        PAYMENT_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.tseExpenseId(),
                      r.tseInstallmentId(),
                      r.accountantId(),
                      r.year(),
                      r.state(),
                      r.documentType(),
                      r.documentNumber(),
                      r.paymentDate(),
                      r.amountCents(),
                      r.source(),
                      r.origin(),
                      r.nature(),
                      r.species(),
                      r.description(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public void linkDonationsToOrg(int year) {
    linkToOrg("campaign_donation", year);
  }

  @Override
  public void linkExpensesToOrg(int year) {
    linkToOrg("campaign_expense", year);
  }

  private void linkToOrg(String table, int year) {
    String t = Jdbc.identifier(table);
    jdbc.sql(
            "UPDATE "
                + t
                + " x SET campaign_org_id = co.id FROM ("
                + "  SELECT DISTINCT ON (year, tse_candidacy_id, cnpj) id, year, tse_candidacy_id,"
                + "         cnpj FROM campaign_org WHERE year = ?"
                + "  ORDER BY year, tse_candidacy_id, cnpj, id) co"
                + " WHERE x.campaign_org_id IS NULL AND x.year = ? AND co.year = x.year"
                + "   AND co.tse_candidacy_id = x.tse_candidacy_id AND co.cnpj = x.cnpj")
        .params(year, year)
        .update();
  }

  @Override
  public void linkPaymentsToExpense(int year) {
    jdbc.sql(
            """
            UPDATE campaign_expense_payment p SET campaign_expense_id = ce.id FROM (
              SELECT DISTINCT ON (year, tse_expense_id) id, year, tse_expense_id
              FROM campaign_expense WHERE year = ? ORDER BY year, tse_expense_id, id) ce
            WHERE p.campaign_expense_id IS NULL AND p.year = ?
              AND ce.year = p.year AND ce.tse_expense_id = p.tse_expense_id
            """)
        .params(year, year)
        .update();
  }

  @Override
  public void rebuildPersonSearch() {
    tx.executeWithoutResult(
        status -> {
          jdbc.sql("TRUNCATE pessoa_fisica_search").update();
          jdbc.sql(
                  """
                  INSERT INTO pessoa_fisica_search (cpf, name)
                  SELECT cpf, max(name) FROM (
                    SELECT donor_cpf_cnpj AS cpf, donor_name AS name FROM campaign_donation
                      WHERE donor_company_id IS NULL AND donor_cpf_cnpj IS NOT NULL
                        AND length(donor_cpf_cnpj) = 11 AND donor_name IS NOT NULL
                    UNION ALL
                    SELECT supplier_cpf_cnpj AS cpf, supplier_name AS name FROM campaign_expense
                      WHERE supplier_company_id IS NULL AND supplier_cpf_cnpj IS NOT NULL
                        AND length(supplier_cpf_cnpj) = 11 AND supplier_name IS NOT NULL
                  ) t GROUP BY cpf
                  """)
              .update();
        });
  }

  @Override
  public Map<String, Object> summary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("orgs", count("SELECT count(*) FROM campaign_org"));
    out.put(
        "orgs_linked_to_person",
        count("SELECT count(*) FROM campaign_org WHERE person_id IS NOT NULL"));
    out.put("donations", count("SELECT count(*) FROM campaign_donation"));
    out.put(
        "donations_linked_to_org",
        count("SELECT count(*) FROM campaign_donation WHERE campaign_org_id IS NOT NULL"));
    out.put(
        "donations_from_known_politician",
        count("SELECT count(*) FROM campaign_donation WHERE donor_person_id IS NOT NULL"));
    out.put(
        "total_donations_cents",
        count("SELECT coalesce(sum(amount_cents), 0) FROM campaign_donation"));
    out.put("expenses", count("SELECT count(*) FROM campaign_expense"));
    out.put(
        "expenses_linked_to_org",
        count("SELECT count(*) FROM campaign_expense WHERE campaign_org_id IS NOT NULL"));
    out.put(
        "expenses_to_known_politician",
        count("SELECT count(*) FROM campaign_expense WHERE supplier_person_id IS NOT NULL"));
    out.put(
        "total_expenses_cents",
        count("SELECT coalesce(sum(amount_cents), 0) FROM campaign_expense"));
    out.put("payments", count("SELECT count(*) FROM campaign_expense_payment"));
    out.put(
        "payments_linked_to_expense",
        count(
            "SELECT count(*) FROM campaign_expense_payment WHERE campaign_expense_id IS NOT NULL"));
    out.put(
        "total_payments_cents",
        count("SELECT coalesce(sum(amount_cents), 0) FROM campaign_expense_payment"));
    return out;
  }

  private long count(String sql) {
    Number n = jdbc.sql(sql).query(Number.class).single();
    return n == null ? 0 : n.longValue();
  }
}
