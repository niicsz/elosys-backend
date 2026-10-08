package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.brlToCents;
import static com.binitech.elosys.domain.SourceValues.clean;
import static com.binitech.elosys.domain.SourceValues.digitsOnly;
import static com.binitech.elosys.domain.SourceValues.isoDate;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.ports.outbound.TransparencyRepositoryPort;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.CompanyResolver;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ProvenanceService.AcquiredFile;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.sanction.SanctionRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransparenciaSanctionsJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransparenciaSanctionsJob.class);

  static final String PARSER_NAME = "transparencia.sanctions";
  static final String PARSER_VERSION = "1.0";
  static final String PAGE_URL = "https://portaldatransparencia.gov.br/download-de-dados/%s";
  static final String FILE_URL = "https://portaldatransparencia.gov.br/download-de-dados/%s/%s";
  static final List<String> REGISTRIES = List.of("ceis", "cnep");
  private static final Pattern FILE_DATE =
      Pattern.compile(
          "\"ano\"\\s*:\\s*\"(\\d{4})\"\\s*,\\s*\"mes\"\\s*:\\s*\"(\\d{2})\"\\s*,\\s*\"dia\"\\s*:\\s*\"(\\d{2})\"");

  private static final List<String> CEIS_COLUMNS =
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
          "notes");

  private static final List<String> CNEP_COLUMNS =
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
          "fine_amount",
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
          "notes");

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final SourceDownloadPort downloads;
  private final TransparencyRepositoryPort transparency;
  private final CompanyRepositoryPort companies;
  private final PeopleRepositoryPort people;

  public TransparenciaSanctionsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      TransparencyRepositoryPort transparency,
      CompanyRepositoryPort companies,
      PeopleRepositoryPort people) {
    this.provenance = provenance;
    this.support = support;
    this.downloads = downloads;
    this.transparency = transparency;
    this.companies = companies;
    this.people = people;
  }

  @Override
  public String name() {
    return "transparencia-sanctions";
  }

  @Override
  public String description() {
    return "ingere CEIS/CNEP (Portal da Transparência) -> sanction";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    provenance.reset(Sources.SANCTIONS, List.of("sanction"));
    companies.deleteByKinds(List.of("sanctioned"));
    Map<String, Long> cpfToPerson = new HashMap<>();
    people.forEachPerson(
        k -> {
          if (k.cpf() != null) cpfToPerson.put(k.cpf(), k.id());
        });
    CompanyResolver resolver = new CompanyResolver(companies);
    long sourceId = provenance.sourceId(Sources.SANCTIONS);

    Map<String, Object> registries = new LinkedHashMap<>();
    for (String registry : REGISTRIES)
      registries.put(registry, ingest(registry, sourceId, cpfToPerson, resolver));

    Map<String, Object> report = new LinkedHashMap<>();
    report.put("registries", registries);
    report.putAll(transparency.sanctionSummary());
    report.put("companies", companies.countByKinds(List.of("sanctioned")));
    return report;
  }

  String discoverDate(String registry) {
    String page = downloads.fetchText(PAGE_URL.formatted(registry), SourceProfile.BULK);
    Matcher m = FILE_DATE.matcher(page);
    if (!m.find())
      throw new BusinessException(
          "não achei a data do arquivo atual de "
              + registry
              + " em "
              + PAGE_URL.formatted(registry));
    return m.group(1) + m.group(2) + m.group(3);
  }

  private Map<String, Object> ingest(
      String registry, long sourceId, Map<String, Long> cpfToPerson, CompanyResolver resolver) {
    String date = discoverDate(registry);
    String url = FILE_URL.formatted(registry, date);
    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            url,
            registry + "_" + date + ".zip",
            SourceProfile.BULK,
            false,
            "Portal da Transparencia; snapshot diario, capturado como " + date + ".",
            null);
    List<String> columns = registry.equals("cnep") ? CNEP_COLUMNS : CEIS_COLUMNS;
    List<SanctionRow> rows = new ArrayList<>();
    Progress rc = new Progress(LOGGER, registry);
    for (String member : provenance.csvMembers(zip.path())) {
      ExtractedFile csv = provenance.extract(zip.path(), member);
      try {
        long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
        support.forEachRow(
            csv.path(),
            raw -> {
              rc.tick();
              if (raw.size() != columns.size()) return;
              Map<String, String> row = new HashMap<>();
              for (int i = 0; i < columns.size(); i++) row.put(columns.get(i), clean(raw.get(i)));
              String doc = digitsOnly(row.get("cpf_cnpj"));
              if (doc == null) return;
              Long personId = null;
              Long companyId = null;
              if (doc.length() == 11) personId = cpfToPerson.get(doc);
              else if (doc.length() == 14) companyId = resolver.getOrCreate(doc, "sanctioned");
              rows.add(
                  new SanctionRow(
                      row.get("registry"),
                      row.get("sanction_code"),
                      row.get("person_type"),
                      doc,
                      row.get("sanctioned_name"),
                      row.get("sanctioned_name_reported"),
                      row.get("legal_name"),
                      row.get("trade_name"),
                      row.get("process_number"),
                      row.get("category"),
                      brlToCents(row.get("fine_amount")),
                      isoDate(row.get("start_date")),
                      isoDate(row.get("end_date")),
                      isoDate(row.get("publication_date")),
                      row.get("publication"),
                      row.get("publication_detail"),
                      isoDate(row.get("final_judgment_date")),
                      row.get("scope"),
                      row.get("sanctioning_agency"),
                      row.get("agency_state"),
                      row.get("agency_sphere"),
                      row.get("legal_basis"),
                      isoDate(row.get("source_data_date")),
                      row.get("source_origin"),
                      row.get("notes"),
                      companyId,
                      personId,
                      parseId));
            });
        provenance.finishParse(parseId, rc.count(), 0);
      } finally {
        support.delete(csv.path());
      }
    }
    rc.done();
    resolver.flush();
    int inserted = transparency.insertSanctions(rows);
    provenance.release(zip);
    LOGGER.info("  {} sanções ({})", inserted, registry.toUpperCase(java.util.Locale.ROOT));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("date", date);
    out.put("collection_id", zip.collection().id());
    out.put("new_sanctions", inserted);
    out.put("rows", rc.count());
    return out;
  }
}
