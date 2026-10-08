package com.binitech.elosys.application.usecases.rules;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort.CandidateSupplierPartner;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort.PartnerOfSupplier;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort.SupplierPayments;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort.PersonCpf;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.domain.SourceValues;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CandidateSupplierPartnerJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(CandidateSupplierPartnerJob.class);

  static final String MATCH_BASIS = "nome_e_6_digitos";
  static final int MIN_NAME_LEN = 8;

  private final DetectionDataPort data;
  private final PeopleRepositoryPort people;

  public CandidateSupplierPartnerJob(DetectionDataPort data, PeopleRepositoryPort people) {
    this.data = data;
    this.people = people;
  }

  @Override
  public String name() {
    return "candidate-supplier-partner";
  }

  @Override
  public String description() {
    return "candidatos sócios de empresas que receberam pagamento de campanha (match não"
        + " determinístico)";
  }

  static String visibleDigits(String mask) {
    if (mask == null || mask.length() < 9) return null;
    String visible = mask.substring(3, 9);
    return SourceValues.allAsciiDigits(visible) ? visible : null;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    data.clearCandidateSupplierPartners();
    List<PartnerOfSupplier> partners = data.partnersOfPaidSuppliers();
    LOGGER.info("  {} linhas de sócio para checar", partners.size());

    long matched = 0, noMatch = 0, ambiguous = 0;
    Set<String> seen = new HashSet<>();
    Progress rc = new Progress(LOGGER, "sócios checados", 2_000);
    for (PartnerOfSupplier pr : partners) {
      rc.tick();
      String visible = visibleDigits(pr.partnerDocMasked());
      if (visible == null) continue;
      String norm = SourceValues.normalizeName(pr.partnerName());
      if (norm == null || norm.length() < MIN_NAME_LEN || !norm.contains(" ")) continue;

      List<PersonCpf> candidates =
          people.findWithCpfByCanonicalName(norm).stream()
              .filter(c -> c.cpf().length() >= 9 && c.cpf().substring(3, 9).equals(visible))
              .toList();
      if (candidates.isEmpty()) {
        noMatch++;
        continue;
      }
      if (candidates.size() > 1) {
        ambiguous++;
        continue;
      }
      long personId = candidates.getFirst().id();
      if (!seen.add(personId + ":" + pr.companyId())) continue;

      SupplierPayments agg = data.supplierPayments(pr.companyId());
      data.insertCandidateSupplierPartner(
          new CandidateSupplierPartner(
              personId,
              pr.companyId(),
              pr.partnerId(),
              MATCH_BASIS,
              pr.role(),
              pr.entryDate(),
              agg.totalCents(),
              agg.count(),
              agg.distinctPayers(),
              data.supplierPaidByPerson(pr.companyId(), personId)));
      matched++;
    }
    rc.done();

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("matched", matched);
    report.put("no_match", noMatch);
    report.put("ambiguous_dropped", ambiguous);
    Map<String, Long> byPaidBySelf = new LinkedHashMap<>();
    data.candidateSupplierPartnersByPaidBySelf()
        .forEach((k, v) -> byPaidBySelf.put(k ? "1" : "0", v));
    report.put("by_paid_by_self", byPaidBySelf);
    LOGGER.info("pronto: {}", report);
    return report;
  }
}
