package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.exactDigits;
import static com.binitech.elosys.domain.SourceValues.isoDate;
import static com.binitech.elosys.domain.SourceValues.normalizeName;
import static com.binitech.elosys.domain.SourceValues.parseIntOrNull;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort.PromotedCandidate;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.CsvRecord;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Csv;
import com.binitech.elosys.application.usecases.IdentityResolver;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ProvenanceService.AcquiredFile;
import com.binitech.elosys.domain.candidate.CandidateRow;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.identity.PersonMatch;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TseCandidatesJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TseCandidatesJob.class);

  static final String PARSER_NAME = "tse.candidates";
  static final String PARSER_VERSION = "2.0";
  static final String URL_TEMPLATE =
      "https://cdn.tse.jus.br/estatistica/sead/odsele/consulta_cand/consulta_cand_%d.zip";
  static final List<Integer> SUPPORTED_YEARS = List.of(2014, 2016, 2018, 2020, 2022, 2024, 2026);
  private static final List<String> OWNED_TABLES = List.of("politician_history", "rejected_cpf");
  private static final int BATCH = 20_000;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final CandidateRepositoryPort candidates;
  private final PeopleRepositoryPort people;

  public TseCandidatesJob(
      ProvenanceService provenance,
      CollectorSupport support,
      CandidateRepositoryPort candidates,
      PeopleRepositoryPort people) {
    this.provenance = provenance;
    this.support = support;
    this.candidates = candidates;
    this.people = people;
  }

  @Override
  public String name() {
    return "tse-candidates";
  }

  @Override
  public String description() {
    return "ingere TSE consulta_cand -> politician_history [--years=2018,2020,...]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    List<Integer> years = p.intList("years").isEmpty() ? SUPPORTED_YEARS : p.intList("years");
    for (int y : years)
      if (!SUPPORTED_YEARS.contains(y)) throw new BusinessException("ano não suportado: " + y);
    LOGGER.info("rewrite-only: politician_history terá exatamente estes anos: {}", years);
    provenance.reset(Sources.TSE_CANDIDATES, OWNED_TABLES);

    Map<String, Object> report = new LinkedHashMap<>();
    Map<Integer, Object> perYear = new LinkedHashMap<>();
    candidates.createStaging();
    for (int year : years) perYear.put(year, ingestYear(year));
    report.put("years", perYear);
    report.put("promote", promote());
    return report;
  }

  private Map<String, Object> ingestYear(int year) {
    long sourceId = provenance.sourceId(Sources.TSE_CANDIDATES);
    String url = URL_TEMPLATE.formatted(year);
    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            url,
            "consulta_cand_%d.zip".formatted(year),
            SourceProfile.BULK,
            true,
            "TSE CDN; the file may be re-published at the same URL.",
            "TSE CDN; file provided locally (manual download). URL is canonical.");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("year", year);
    out.put("collection_id", zip.collection().id());
    if (!zip.collection().isNew()) {
      provenance.release(zip);
      out.put("already_collected", true);
      out.put("staged", 0);
      return out;
    }
    long staged = 0;
    for (String member : Csv.preferBrasil(provenance.csvMembers(zip.path()))) {
      ExtractedFile csv = provenance.extract(zip.path(), member);
      try {
        long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
        List<CandidateRow> batch = new ArrayList<>(BATCH);
        long n =
            support.forEachRecord(
                csv.path(),
                row -> {
                  batch.add(map(row, year, parseId));
                  if (batch.size() >= BATCH) {
                    candidates.stage(List.copyOf(batch));
                    batch.clear();
                  }
                });
        candidates.stage(batch);
        provenance.finishParse(parseId, n, 0);
        staged += n;
      } finally {
        support.delete(csv.path());
      }
    }
    provenance.release(zip);
    LOGGER.info("  {}: {} linhas em staging", year, staged);
    out.put("already_collected", false);
    out.put("staged", staged);
    return out;
  }

  static CandidateRow map(CsvRecord row, int fileYear, long parseId) {
    String fullName = Csv.first(row, "NM_CANDIDATO", "NOME_CANDIDATO");
    String yearRaw = Csv.first(row, "ANO_ELEICAO");
    return new CandidateRow(
        exactDigits(Csv.first(row, "NR_CPF_CANDIDATO", "CPF_CANDIDATO"), 11),
        exactDigits(Csv.first(row, "NR_TITULO_ELEITORAL_CANDIDATO"), 12),
        Csv.first(row, "NM_URNA_CANDIDATO", "NOME_URNA_CANDIDATO"),
        fullName,
        normalizeName(fullName),
        Csv.first(row, "SQ_CANDIDATO", "SEQUENCIAL_CANDIDATO"),
        yearRaw == null || yearRaw.isEmpty() ? fileYear : Integer.parseInt(yearRaw),
        Csv.first(row, "NM_TIPO_ELEICAO", "DS_ELEICAO"),
        parseIntOrNull(Csv.first(row, "NR_TURNO")),
        Csv.first(row, "DS_CARGO", "DESCRICAO_CARGO"),
        Csv.first(row, "NR_CANDIDATO", "NUMERO_CANDIDATO"),
        Csv.first(row, "SG_PARTIDO", "SIGLA_PARTIDO"),
        Csv.first(row, "NM_PARTIDO", "NOME_PARTIDO"),
        Csv.first(row, "NR_PARTIDO", "NUMERO_PARTIDO"),
        Csv.first(row, "SG_UF", "SIGLA_UF"),
        Csv.first(row, "SG_UE", "SIGLA_UE"),
        Csv.first(row, "NM_UE", "DESCRICAO_UE"),
        Csv.first(row, "DS_SITUACAO_CANDIDATURA", "DES_SITUACAO_CANDIDATURA"),
        Csv.first(row, "DS_DETALHE_SITUACAO_CAND"),
        Csv.first(row, "DS_SIT_TOT_TURNO", "DESC_SIT_TOT_TURNO"),
        isoDate(Csv.first(row, "DT_NASCIMENTO", "DATA_NASCIMENTO")),
        Csv.first(row, "DS_GENERO", "DESCRICAO_SEXO"),
        Csv.first(row, "DS_GRAU_INSTRUCAO", "DESCRICAO_GRAU_INSTRUCAO"),
        Csv.first(row, "DS_ESTADO_CIVIL", "DESCRICAO_ESTADO_CIVIL"),
        Csv.first(row, "DS_COR_RACA"),
        Csv.first(row, "DS_OCUPACAO", "DESCRICAO_OCUPACAO"),
        parseId);
  }

  private Map<String, Object> promote() {
    candidates.markAmbiguousCpfs();
    Set<String> rejected = candidates.rejectedCpfs();
    IdentityResolver identity = new IdentityResolver(people);

    long[] counters = new long[3];
    List<PromotedCandidate> batch = new ArrayList<>(BATCH);
    candidates.forEachStaged(
        staged -> {
          CandidateRow row = staged;
          if (row.cpf() != null && rejected.contains(row.cpf())) {
            row = row.withCpf(null);
            counters[2]++;
          }
          PersonMatch match = identity.resolve(row.cpf(), row.voterId(), row.normalizedName());
          batch.add(new PromotedCandidate(row, match.personId(), match.cpfTrusted()));
          if (batch.size() >= BATCH) {
            identity.flush();
            int inserted = candidates.insertHistory(List.copyOf(batch));
            counters[0] += inserted;
            counters[1] += batch.size() - inserted;
            batch.clear();
          }
        });
    identity.flush();
    int inserted = candidates.insertHistory(batch);
    counters[0] += inserted;
    counters[1] += batch.size() - inserted;
    candidates.dropStaging();

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("promoted", counters[0]);
    out.put("skipped", counters[1]);
    out.put("rejected_cpf", rejected.size());
    out.put("rows_with_cpf_dropped", counters[2]);
    out.put("rejected_cpf_detail", candidates.rejectedCpfDetail());
    LOGGER.info(
        "  {} linhas -> politician_history; {} CPFs ambíguos descartados ({} linhas)",
        counters[0],
        rejected.size(),
        counters[2]);
    return out;
  }
}
