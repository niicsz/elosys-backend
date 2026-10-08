package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.brlToCents;
import static com.binitech.elosys.domain.SourceValues.digitsOnly;
import static com.binitech.elosys.domain.SourceValues.exactDigits;
import static com.binitech.elosys.domain.SourceValues.isoDate;
import static com.binitech.elosys.domain.SourceValues.normalizeName;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CampaignFinanceRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.CsvRecord;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.CompanyResolver;
import com.binitech.elosys.application.usecases.Csv;
import com.binitech.elosys.application.usecases.IdentityResolver;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ProvenanceService.AcquiredFile;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.finance.CampaignOrgRow;
import com.binitech.elosys.domain.finance.DonationRow;
import com.binitech.elosys.domain.finance.ExpenseRow;
import com.binitech.elosys.domain.finance.PaymentRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TseAccountsJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TseAccountsJob.class);

  static final String PARSER_NAME = "tse.accounts";
  static final String PARSER_VERSION = "4.0";
  static final String URL_TEMPLATE =
      "https://cdn.tse.jus.br/estatistica/sead/odsele/prestacao_contas/"
          + "prestacao_de_contas_eleitorais_candidatos_%d.zip";
  static final List<Integer> SUPPORTED_YEARS = List.of(2018, 2020, 2022, 2024, 2026);

  private static final List<String> OWNED_TABLES =
      List.of("campaign_donation", "campaign_expense_payment", "campaign_expense", "campaign_org");

  private static final List<String> COMPANY_KINDS = List.of("campaign", "donor", "supplier");
  private static final int FLUSH_EVERY = 50_000;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final CampaignFinanceRepositoryPort finance;
  private final CandidateRepositoryPort candidates;
  private final CompanyRepositoryPort companies;
  private final PeopleRepositoryPort people;

  public TseAccountsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      CampaignFinanceRepositoryPort finance,
      CandidateRepositoryPort candidates,
      CompanyRepositoryPort companies,
      PeopleRepositoryPort people) {
    this.provenance = provenance;
    this.support = support;
    this.finance = finance;
    this.candidates = candidates;
    this.companies = companies;
    this.people = people;
  }

  @Override
  public String name() {
    return "tse-accounts";
  }

  @Override
  public String description() {
    return "ingere TSE prestação de contas -> campaign_org/donation/expense/payment [--years=...]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    List<Integer> years = p.intList("years").isEmpty() ? SUPPORTED_YEARS : p.intList("years");
    List<Integer> unknown = years.stream().filter(y -> !SUPPORTED_YEARS.contains(y)).toList();
    if (!unknown.isEmpty())
      throw new BusinessException("não há arquivo de prestação de contas para: " + unknown);

    LOGGER.info("rewrite-only: contas de campanha terão exatamente estes anos: {}", years);
    provenance.reset(Sources.TSE_ACCOUNTS, OWNED_TABLES);
    companies.deleteByKinds(COMPANY_KINDS);
    if (candidates.countHistory() == 0)
      LOGGER.warn(
          "politician_history vazia — rode tse-candidates antes para a identidade ligar;"
              + " seguindo só com match por CPF");

    Context ctx = new Context();
    ctx.rejectedCpf = candidates.rejectedCpfs();
    ctx.identity = new IdentityResolver(people);
    ctx.cpfToPerson = new HashMap<>();
    people.forEachPerson(
        k -> {
          if (k.cpf() != null) ctx.cpfToPerson.put(k.cpf(), k.id());
        });
    ctx.companies = new CompanyResolver(companies);
    long sourceId = provenance.sourceId(Sources.TSE_ACCOUNTS);

    Map<Integer, Object> perYear = new LinkedHashMap<>();
    for (int year : years) perYear.put(year, ingestYear(ctx, year, sourceId));
    finance.rebuildPersonSearch();

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("years", perYear);
    report.putAll(finance.summary());
    report.put("companies", companies.countByKinds(COMPANY_KINDS));
    LOGGER.info("pronto: {}", report);
    return report;
  }

  private static final class Context {
    Set<String> rejectedCpf;
    IdentityResolver identity;
    Map<String, Long> cpfToPerson;
    CompanyResolver companies;
  }

  private Map<String, Object> ingestYear(Context ctx, int year, long sourceId) {
    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            URL_TEMPLATE.formatted(year),
            "prestacao_contas_candidatos_%d.zip".formatted(year),
            SourceProfile.BULK,
            true,
            "TSE CDN; the file may be re-published at the same URL.",
            "TSE CDN; file provided locally. URL is canonical.");
    Map<String, Long> phPerson = candidates.personByCandidacy(year);
    List<String> all = provenance.csvMembers(zip.path());
    List<String> receitas = members(all, "receitas_candidatos_");
    List<String> despesas = members(all, "despesas_contratadas_candidatos_");
    List<String> pagas = members(all, "despesas_pagas_candidatos_");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("collection_id", zip.collection().id());
    if (receitas.isEmpty()) {
      LOGGER.warn("  sem receitas_candidatos no zip de {}", year);
      provenance.release(zip);
      out.put("new_orgs", 0);
      out.put("new_donations", 0);
      out.put("new_expenses", 0);
      out.put("new_payments", 0);
      out.put("rows", 0);
      return out;
    }

    Set<String> seenOrg = new HashSet<>();
    List<CampaignOrgRow> orgs = new ArrayList<>();
    List<DonationRow> donations = new ArrayList<>(FLUSH_EVERY);
    long[] donated = {0};
    Progress rc = new Progress(LOGGER, "receitas " + year);
    for (String member : receitas) {
      parseMember(
          zip,
          member,
          row -> {
            rc.tick();
            scanReceipt(ctx, row, year, phPerson, seenOrg, orgs, donations, currentParse);
            if (donations.size() >= FLUSH_EVERY) donated[0] += flushDonations(ctx, donations);
          });
    }
    donated[0] += flushDonations(ctx, donations);
    rc.done();
    ctx.identity.flush();
    ctx.companies.flush();
    int newOrgs = finance.insertOrgs(orgs);
    finance.linkDonationsToOrg(year);

    long expensed = 0;
    if (!despesas.isEmpty()) {
      List<ExpenseRow> expenses = new ArrayList<>(FLUSH_EVERY);
      long[] acc = {0};
      Progress rc2 = new Progress(LOGGER, "despesas " + year);
      for (String member : despesas) {
        parseMember(
            zip,
            member,
            row -> {
              rc2.tick();
              scanExpense(ctx, row, year, phPerson, expenses, currentParse);
              if (expenses.size() >= FLUSH_EVERY) acc[0] += flushExpenses(ctx, expenses);
            });
      }
      acc[0] += flushExpenses(ctx, expenses);
      rc2.done();
      expensed = acc[0];
      finance.linkExpensesToOrg(year);
    } else {
      LOGGER.warn("  sem despesas_contratadas no zip de {}", year);
    }

    long paid = 0;
    if (!pagas.isEmpty()) {
      List<PaymentRow> payments = new ArrayList<>(FLUSH_EVERY);
      long[] acc = {0};
      Progress rc3 = new Progress(LOGGER, "despesas pagas " + year);
      for (String member : pagas) {
        parseMember(
            zip,
            member,
            row -> {
              rc3.tick();
              scanPayment(row, year, payments, currentParse);
              if (payments.size() >= FLUSH_EVERY) {
                acc[0] += finance.insertPayments(List.copyOf(payments));
                payments.clear();
              }
            });
      }
      acc[0] += finance.insertPayments(payments);
      rc3.done();
      paid = acc[0];
      finance.linkPaymentsToExpense(year);
    } else {
      LOGGER.warn("  sem despesas_pagas no zip de {}", year);
    }

    provenance.release(zip);
    LOGGER.info(
        "  {}: {} CNPJs de campanha, {} doações, {} despesas, {} pagamentos",
        year,
        newOrgs,
        donated[0],
        expensed,
        paid);
    out.put("new_orgs", newOrgs);
    out.put("new_donations", donated[0]);
    out.put("new_expenses", expensed);
    out.put("new_payments", paid);
    out.put("rows", rc.count());
    return out;
  }

  private long currentParse;

  private void parseMember(AcquiredFile zip, String member, Consumer<CsvRecord> scanner) {
    ExtractedFile csv = provenance.extract(zip.path(), member);
    try {
      currentParse = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
      long n = support.forEachRecord(csv.path(), scanner);
      provenance.finishParse(currentParse, n, 0);
    } finally {
      support.delete(csv.path());
    }
  }

  private int flushDonations(Context ctx, List<DonationRow> rows) {
    ctx.identity.flush();
    ctx.companies.flush();
    int n = finance.insertDonations(List.copyOf(rows));
    rows.clear();
    return n;
  }

  private int flushExpenses(Context ctx, List<ExpenseRow> rows) {
    ctx.companies.flush();
    int n = finance.insertExpenses(List.copyOf(rows));
    rows.clear();
    return n;
  }

  static List<String> members(List<String> all, String prefix) {
    List<String> names =
        all.stream()
            .filter(
                n -> {
                  String l = n.toLowerCase(Locale.ROOT);
                  return l.startsWith(prefix) && !l.contains("doador_originario");
                })
            .toList();
    return Csv.preferBrasil(names);
  }

  private static String g(CsvRecord row, String... names) {
    return Csv.firstPresent(row, names);
  }

  private static int year(CsvRecord row, int fallback) {
    String v = g(row, "AA_ELEICAO");
    return v == null || v.isEmpty() ? fallback : Integer.parseInt(v);
  }

  private void scanReceipt(
      Context ctx,
      CsvRecord row,
      int fileYear,
      Map<String, Long> phPerson,
      Set<String> seenOrg,
      List<CampaignOrgRow> orgs,
      List<DonationRow> donations,
      long parseId) {
    String cnpj = exactDigits(g(row, "NR_CNPJ_PRESTADOR_CONTA"), 14);
    String sq = g(row, "SQ_CANDIDATO");
    if (cnpj == null) return;
    int year = year(row, fileYear);

    if (seenOrg.add(sq + "|" + cnpj)) {
      String cpf = exactDigits(g(row, "NR_CPF_CANDIDATO"), 11);
      if (cpf != null && ctx.rejectedCpf.contains(cpf)) cpf = null;
      String name = g(row, "NM_CANDIDATO");
      String norm = normalizeName(name);
      Long personId = phPerson.get(sq);
      if (personId == null) personId = ctx.identity.resolve(cpf, null, norm).personId();
      orgs.add(
          new CampaignOrgRow(
              ctx.companies.getOrCreate(cnpj, "campaign"),
              personId,
              cnpj,
              sq,
              g(row, "SQ_PRESTADOR_CONTAS"),
              year,
              cpf,
              name,
              norm,
              g(row, "DS_CARGO"),
              g(row, "SG_PARTIDO"),
              g(row, "SG_UF"),
              parseId));
    }

    String donorRaw = digitsOnly(g(row, "NR_CPF_CNPJ_DOADOR"));
    String donorCpf = donorRaw != null && donorRaw.length() == 11 ? donorRaw : null;
    String donorCnpj = donorRaw != null && donorRaw.length() == 14 ? donorRaw : null;
    String donorCandidacy = g(row, "SQ_CANDIDATO_DOADOR");
    Long donorPersonId = null;
    if (donorCandidacy != null) donorPersonId = phPerson.get(donorCandidacy);
    else if (donorCpf != null) donorPersonId = ctx.cpfToPerson.get(donorCpf);
    Long donorCompanyId = donorCnpj != null ? ctx.companies.getOrCreate(donorCnpj, "donor") : null;

    donations.add(
        new DonationRow(
            cnpj,
            sq,
            year,
            g(row, "SQ_RECEITA"),
            g(row, "NR_RECIBO_DOACAO"),
            g(row, "NR_DOCUMENTO_DOACAO"),
            isoDate(g(row, "DT_RECEITA")),
            brlToCents(g(row, "VR_RECEITA")),
            g(row, "DS_FONTE_RECEITA"),
            g(row, "DS_ORIGEM_RECEITA"),
            g(row, "DS_ESPECIE_RECEITA"),
            donorRaw,
            g(row, "NM_DOADOR"),
            g(row, "NM_DOADOR_RFB"),
            g(row, "DS_CNAE_DOADOR"),
            g(row, "SG_UF_DOADOR"),
            g(row, "NM_MUNICIPIO_DOADOR"),
            donorCandidacy,
            g(row, "SG_PARTIDO_DOADOR"),
            donorPersonId,
            donorCompanyId,
            parseId));
  }

  private void scanExpense(
      Context ctx,
      CsvRecord row,
      int fileYear,
      Map<String, Long> phPerson,
      List<ExpenseRow> expenses,
      long parseId) {
    String cnpj = exactDigits(g(row, "NR_CNPJ_PRESTADOR_CONTA"), 14);
    String sq = g(row, "SQ_CANDIDATO");
    if (cnpj == null) return;

    String supplierRaw = digitsOnly(g(row, "NR_CPF_CNPJ_FORNECEDOR"));
    String supplierCpf = supplierRaw != null && supplierRaw.length() == 11 ? supplierRaw : null;
    String supplierCnpj = supplierRaw != null && supplierRaw.length() == 14 ? supplierRaw : null;
    String supplierCandidacy = g(row, "SQ_CANDIDATO_FORNECEDOR");
    Long supplierPersonId = null;
    if (supplierCandidacy != null) supplierPersonId = phPerson.get(supplierCandidacy);
    else if (supplierCpf != null) supplierPersonId = ctx.cpfToPerson.get(supplierCpf);
    Long supplierCompanyId =
        supplierCnpj != null ? ctx.companies.getOrCreate(supplierCnpj, "supplier") : null;

    expenses.add(
        new ExpenseRow(
            cnpj,
            sq,
            year(row, fileYear),
            g(row, "SQ_DESPESA"),
            g(row, "DS_TIPO_DOCUMENTO"),
            g(row, "NR_DOCUMENTO"),
            isoDate(g(row, "DT_DESPESA")),
            brlToCents(g(row, "VR_DESPESA_CONTRATADA")),
            g(row, "DS_ORIGEM_DESPESA"),
            g(row, "DS_DESPESA"),
            supplierRaw,
            g(row, "NM_FORNECEDOR"),
            g(row, "NM_FORNECEDOR_RFB"),
            g(row, "DS_TIPO_FORNECEDOR"),
            g(row, "DS_CNAE_FORNECEDOR"),
            g(row, "SG_UF_FORNECEDOR"),
            g(row, "NM_MUNICIPIO_FORNECEDOR"),
            supplierCandidacy,
            g(row, "SG_PARTIDO_FORNECEDOR"),
            supplierPersonId,
            supplierCompanyId,
            parseId));
  }

  private static void scanPayment(
      CsvRecord row, int fileYear, List<PaymentRow> payments, long parseId) {
    String expenseId = g(row, "SQ_DESPESA");
    if (expenseId == null) return;
    payments.add(
        new PaymentRow(
            expenseId,
            g(row, "SQ_PARCELAMENTO_DESPESA"),
            g(row, "SQ_PRESTADOR_CONTAS"),
            year(row, fileYear),
            g(row, "SG_UF"),
            g(row, "DS_TIPO_DOCUMENTO"),
            g(row, "NR_DOCUMENTO"),
            isoDate(g(row, "DT_PAGTO_DESPESA")),
            brlToCents(g(row, "VR_PAGTO_DESPESA")),
            g(row, "DS_FONTE_DESPESA"),
            g(row, "DS_ORIGEM_DESPESA"),
            g(row, "DS_NATUREZA_DESPESA"),
            g(row, "DS_ESPECIE_RECURSO"),
            g(row, "DS_DESPESA"),
            parseId));
  }
}
