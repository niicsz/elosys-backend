package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.brlToCents;
import static com.binitech.elosys.domain.SourceValues.digitsOnly;
import static com.binitech.elosys.domain.SourceValues.normalizeName;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.ports.outbound.TransparencyRepositoryPort;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Csv;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ProvenanceService.AcquiredFile;
import com.binitech.elosys.domain.SourceValues;
import com.binitech.elosys.domain.earmark.EarmarkBeneficiaryRow;
import com.binitech.elosys.domain.earmark.EarmarkRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TransparenciaEarmarksJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransparenciaEarmarksJob.class);

  static final String PARSER_NAME = "transparencia.earmarks";
  static final String PARSER_VERSION = "1.0";
  static final String FILE_URL =
      "https://portaldatransparencia.gov.br/download-de-dados/emendas-parlamentares/UNICO";
  private static final List<String> OWNED_TABLES =
      List.of("parliamentary_earmark_beneficiary", "parliamentary_earmark");
  private static final List<String> AUTHOR_OFFICES = List.of("DEPUTADO FEDERAL", "SENADOR");
  private static final int BATCH = 50_000;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final TransparencyRepositoryPort transparency;
  private final CandidateRepositoryPort candidates;
  private final CompanyRepositoryPort companies;

  public TransparenciaEarmarksJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TransparencyRepositoryPort transparency,
      CandidateRepositoryPort candidates,
      CompanyRepositoryPort companies) {
    this.provenance = provenance;
    this.support = support;
    this.transparency = transparency;
    this.candidates = candidates;
    this.companies = companies;
  }

  @Override
  public String name() {
    return "transparencia-earmarks";
  }

  @Override
  public String description() {
    return "ingere Emendas Parlamentares (Portal da Transparência) -> parliamentary_earmark(*)";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    provenance.reset(Sources.EARMARKS, OWNED_TABLES);
    long sourceId = provenance.sourceId(Sources.EARMARKS);
    Map<String, Long> authorByName = authorIndex();
    Map<String, Long> companyByCnpj = companies.idsByCnpj();

    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            FILE_URL,
            "emendas_parlamentares.zip",
            SourceProfile.BULK,
            false,
            "Arquivo unico, todo o historico.",
            null);
    long earmarks;
    long beneficiaries;
    try {
      earmarks = ingestEarmarks(zip, authorByName);
      beneficiaries = ingestBeneficiaries(zip, companyByCnpj);
    } finally {
      provenance.release(zip);
    }
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("earmarks", earmarks);
    report.put("beneficiaries", beneficiaries);
    report.putAll(transparency.earmarkSummary());
    LOGGER.info("pronto: {}", report);
    return report;
  }

  private Map<String, Long> authorIndex() {
    Map<String, Set<Long>> byName = new HashMap<>();
    for (var h : candidates.officeHolders(AUTHOR_OFFICES)) {
      for (String n : new String[] {normalizeName(h.ballotName()), normalizeName(h.fullName())}) {
        if (n != null) byName.computeIfAbsent(n, k -> new HashSet<>()).add(h.personId());
      }
    }
    Map<String, Long> out = new HashMap<>();
    byName.forEach((name, ids) -> out.put(name, ids.size() == 1 ? ids.iterator().next() : null));
    return out;
  }

  private long ingestEarmarks(AcquiredFile zip, Map<String, Long> authorByName) {
    ExtractedFile csv = provenance.extract(zip.path(), "EmendasParlamentares.csv");
    try {
      long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
      List<EarmarkRow> rows = new ArrayList<>();
      long[] rejected = {0};
      Progress rc = new Progress(LOGGER, "emendas");
      support.forEachRecord(
          csv.path(),
          row -> {
            rc.tick();
            String code = Csv.raw(row, "Código da Emenda");
            String yearRaw = Csv.raw(row, "Ano da Emenda");
            if (code == null || code.isEmpty() || !SourceValues.allAsciiDigits(yearRaw)) {
              rejected[0]++;
              return;
            }
            String authorName = Csv.raw(row, "Nome do Autor da Emenda");
            String norm = normalizeName(authorName);
            Long authorPersonId = norm != null ? authorByName.get(norm) : null;
            rows.add(
                new EarmarkRow(
                    code,
                    Integer.parseInt(yearRaw),
                    Csv.raw(row, "Tipo de Emenda"),
                    Csv.raw(row, "Código do Autor da Emenda"),
                    authorName,
                    authorPersonId,
                    authorPersonId != null ? "nome" : null,
                    Csv.raw(row, "Localidade de aplicação do recurso"),
                    Csv.raw(row, "UF"),
                    Csv.raw(row, "Município"),
                    Csv.raw(row, "Nome Função"),
                    Csv.raw(row, "Nome Subfunção"),
                    Csv.raw(row, "Nome Programa"),
                    Csv.raw(row, "Nome Ação"),
                    brlToCents(Csv.raw(row, "Valor Empenhado")),
                    brlToCents(Csv.raw(row, "Valor Pago")),
                    parseId));
          });
      rc.done();
      provenance.finishParse(parseId, rows.size(), rejected[0]);
      for (int i = 0; i < rows.size(); i += BATCH)
        transparency.insertEarmarks(rows.subList(i, Math.min(rows.size(), i + BATCH)));
      return ((Number) transparency.earmarkSummary().get("earmarks_total")).longValue();
    } finally {
      support.delete(csv.path());
    }
  }

  private long ingestBeneficiaries(AcquiredFile zip, Map<String, Long> companyByCnpj) {
    ExtractedFile csv = provenance.extract(zip.path(), "EmendasParlamentares_PorFavorecido.csv");
    try {
      long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
      List<EarmarkBeneficiaryRow> batch = new ArrayList<>(BATCH);
      long[] inserted = {0};
      long[] rejected = {0};
      Progress rc = new Progress(LOGGER, "favorecidos", 50_000);
      support.forEachRecord(
          csv.path(),
          row -> {
            rc.tick();
            String code = Csv.raw(row, "Código da Emenda");
            String doc = digitsOnly(Csv.raw(row, "Código do Favorecido"));
            Long amount = brlToCents(Csv.raw(row, "Valor Recebido"));
            if (code == null || code.isEmpty() || doc == null || amount == null) {
              rejected[0]++;
              return;
            }
            batch.add(
                new EarmarkBeneficiaryRow(
                    code,
                    Csv.raw(row, "Código do Autor da Emenda"),
                    Csv.raw(row, "Ano/Mês"),
                    doc,
                    Csv.raw(row, "Favorecido"),
                    Csv.raw(row, "Tipo Favorecido"),
                    doc.length() == 14 ? companyByCnpj.get(doc) : null,
                    Csv.raw(row, "UF Favorecido"),
                    Csv.raw(row, "Município Favorecido"),
                    amount,
                    parseId));
            if (batch.size() >= BATCH) {
              inserted[0] += transparency.insertEarmarkBeneficiaries(List.copyOf(batch));
              batch.clear();
            }
          });
      inserted[0] += transparency.insertEarmarkBeneficiaries(batch);
      rc.done();
      provenance.finishParse(parseId, inserted[0], rejected[0]);
      return inserted[0];
    } finally {
      support.delete(csv.path());
    }
  }
}
