package com.binitech.elosys.adapters.outbound.persistence;

import com.binitech.elosys.application.ports.outbound.TransparencyRepositoryPort;
import com.binitech.elosys.domain.earmark.EarmarkBeneficiaryRow;
import com.binitech.elosys.domain.earmark.EarmarkRow;
import com.binitech.elosys.domain.sanction.SanctionRow;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
public class TransparencyJdbcAdapter implements TransparencyRepositoryPort {
  private static final List<String> SANCTION_COLUMNS =
      List.of(
          "registry",
          "sanction_code",
          "person_type",
          "cpf_cnpj",
          "sanctioned_name",
          "sanctioned_name_reported",
          "legal_name",
          "trade_name",
          "process_number",
          "category",
          "fine_amount_cents",
          "start_date",
          "end_date",
          "publication_date",
          "publication",
          "publication_detail",
          "final_judgment_date",
          "scope",
          "sanctioning_agency",
          "agency_state",
          "agency_sphere",
          "legal_basis",
          "source_data_date",
          "source_origin",
          "notes",
          "company_id",
          "person_id",
          "provenance_id",
          "collected_at");
  private static final List<String> EARMARK_COLUMNS =
      List.of(
          "earmark_code",
          "year",
          "earmark_type",
          "author_code",
          "author_name",
          "author_person_id",
          "author_match_basis",
          "locality",
          "state",
          "municipality",
          "function_name",
          "subfunction_name",
          "program_name",
          "action_name",
          "committed_cents",
          "paid_cents",
          "provenance_id",
          "collected_at");
  private static final List<String> BENEFICIARY_COLUMNS =
      List.of(
          "earmark_code",
          "author_code",
          "year_month",
          "beneficiary_doc",
          "beneficiary_name",
          "beneficiary_type",
          "beneficiary_company_id",
          "state",
          "municipality",
          "amount_cents",
          "provenance_id",
          "collected_at");

  private final JdbcClient jdbc;
  private final CopyWriter copy;

  public TransparencyJdbcAdapter(JdbcClient jdbc, CopyWriter copy) {
    this.jdbc = jdbc;
    this.copy = copy;
  }

  @Override
  public int insertSanctions(List<SanctionRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "sanction",
        SANCTION_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.registry(),
                      r.sanctionCode(),
                      r.personType(),
                      r.cpfCnpj(),
                      r.sanctionedName(),
                      r.sanctionedNameReported(),
                      r.legalName(),
                      r.tradeName(),
                      r.processNumber(),
                      r.category(),
                      r.fineAmountCents(),
                      r.startDate(),
                      r.endDate(),
                      r.publicationDate(),
                      r.publication(),
                      r.publicationDetail(),
                      r.finalJudgmentDate(),
                      r.scope(),
                      r.sanctioningAgency(),
                      r.agencyState(),
                      r.agencySphere(),
                      r.legalBasis(),
                      r.sourceDataDate(),
                      r.sourceOrigin(),
                      r.notes(),
                      r.companyId(),
                      r.personId(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public Map<String, Object> sanctionSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("sanctions", count("SELECT count(*) FROM sanction"));
    out.put("linked_to_person", count("SELECT count(*) FROM sanction WHERE person_id IS NOT NULL"));
    out.put(
        "linked_to_company", count("SELECT count(*) FROM sanction WHERE company_id IS NOT NULL"));
    return out;
  }

  @Override
  public int insertEarmarks(List<EarmarkRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "parliamentary_earmark",
        EARMARK_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.earmarkCode(),
                      r.year(),
                      r.earmarkType(),
                      r.authorCode(),
                      r.authorName(),
                      r.authorPersonId(),
                      r.authorMatchBasis(),
                      r.locality(),
                      r.state(),
                      r.municipality(),
                      r.functionName(),
                      r.subfunctionName(),
                      r.programName(),
                      r.actionName(),
                      r.committedCents(),
                      r.paidCents(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        true);
  }

  @Override
  public int insertEarmarkBeneficiaries(List<EarmarkBeneficiaryRow> rows) {
    OffsetDateTime now = Jdbc.now();
    return copy.insert(
        "parliamentary_earmark_beneficiary",
        BENEFICIARY_COLUMNS,
        rows.stream()
            .map(
                r ->
                    new Object[] {
                      r.earmarkCode(),
                      r.authorCode(),
                      r.yearMonth(),
                      r.beneficiaryDoc(),
                      r.beneficiaryName(),
                      r.beneficiaryType(),
                      r.beneficiaryCompanyId(),
                      r.state(),
                      r.municipality(),
                      r.amountCents(),
                      r.provenanceId(),
                      now
                    })
            .toList(),
        false);
  }

  @Override
  public Map<String, Object> earmarkSummary() {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("earmarks_total", count("SELECT count(*) FROM parliamentary_earmark"));
    out.put(
        "earmarks_matched_author",
        count("SELECT count(*) FROM parliamentary_earmark WHERE author_person_id IS NOT NULL"));
    out.put(
        "beneficiaries_matched_company",
        count(
            "SELECT count(*) FROM parliamentary_earmark_beneficiary"
                + " WHERE beneficiary_company_id IS NOT NULL"));
    return out;
  }

  private long count(String sql) {
    Number n = jdbc.sql(sql).query(Number.class).single();
    return n == null ? 0 : n.longValue();
  }
}
