package com.binitech.elosys.application.usecases.collectors;

import static com.binitech.elosys.domain.SourceValues.brlToCents;
import static com.binitech.elosys.domain.SourceValues.isoDate;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort.ExtractedFile;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.ports.outbound.TseExtrasRepositoryPort;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Csv;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ProvenanceService.AcquiredFile;
import com.binitech.elosys.domain.SourceValues;
import com.binitech.elosys.domain.assets.DeclaredAssetRow;
import com.binitech.elosys.domain.exception.BusinessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TseAssetsJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TseAssetsJob.class);

  static final String PARSER_NAME = "tse.assets";
  static final String PARSER_VERSION = "1.0";
  static final String URL_TEMPLATE =
      "https://cdn.tse.jus.br/estatistica/sead/odsele/bem_candidato/bem_candidato_%d.zip";
  static final List<Integer> SUPPORTED_YEARS = List.of(2014, 2016, 2018, 2020, 2022, 2024, 2026);
  private static final int FLUSH_EVERY = 50_000;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final TseExtrasRepositoryPort extras;
  private final CandidateRepositoryPort candidates;

  public TseAssetsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    this.provenance = provenance;
    this.support = support;
    this.extras = extras;
    this.candidates = candidates;
  }

  @Override
  public String name() {
    return "tse-assets";
  }

  @Override
  public String description() {
    return "ingere TSE bem_candidato -> declared_assets [--years=...]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    List<Integer> years = p.intList("years").isEmpty() ? SUPPORTED_YEARS : p.intList("years");
    List<Integer> unknown = years.stream().filter(y -> !SUPPORTED_YEARS.contains(y)).toList();
    if (!unknown.isEmpty()) throw new BusinessException("não há bem_candidato para: " + unknown);
    provenance.reset(Sources.TSE_ASSETS, List.of("declared_assets"));
    if (candidates.countHistory() == 0)
      LOGGER.warn("politician_history vazia — rode tse-candidates antes para ligar as pessoas");
    long sourceId = provenance.sourceId(Sources.TSE_ASSETS);

    Map<Integer, Object> perYear = new LinkedHashMap<>();
    for (int year : years) perYear.put(year, ingestYear(year, sourceId));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("years", perYear);
    report.putAll(extras.declaredAssetsSummary());
    return report;
  }

  private Map<String, Object> ingestYear(int year, long sourceId) {
    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            URL_TEMPLATE.formatted(year),
            "bem_candidato_%d.zip".formatted(year),
            SourceProfile.BULK,
            true,
            "TSE CDN; the file may be re-published at the same URL.",
            "TSE CDN; file provided locally. URL is canonical.");
    Map<String, long[]> phMap = candidates.personAndHistoryByCandidacy(year);
    List<String> members = Csv.preferBrasil(provenance.csvMembers(zip.path()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("collection_id", zip.collection().id());
    if (members.isEmpty()) {
      LOGGER.warn("  sem bem_candidato no zip de {}", year);
      provenance.release(zip);
      out.put("new_assets", 0);
      out.put("rows", 0);
      return out;
    }
    List<DeclaredAssetRow> rows = new ArrayList<>(FLUSH_EVERY);
    long[] inserted = {0};
    long[] scanned = {0};
    Progress rc = new Progress(LOGGER, "bens " + year);
    for (String member : members) {
      ExtractedFile csv = provenance.extract(zip.path(), member);
      try {
        long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
        support.forEachRecord(
            csv.path(),
            row -> {
              rc.tick();
              String sq = Csv.firstPresent(row, "SQ_CANDIDATO");
              if (sq == null || sq.isEmpty()) return;
              String order = Csv.firstPresent(row, "NR_ORDEM_BEM_CANDIDATO");
              long[] ph = phMap.get(sq);
              String yearRaw = Csv.firstPresent(row, "ANO_ELEICAO");
              rows.add(
                  new DeclaredAssetRow(
                      ph == null ? null : ph[0],
                      ph == null ? null : ph[1],
                      sq,
                      yearRaw == null || yearRaw.isEmpty() ? year : Integer.parseInt(yearRaw),
                      Csv.firstPresent(row, "SG_UF"),
                      SourceValues.allAsciiDigits(order) ? Integer.valueOf(order) : null,
                      Csv.firstPresent(row, "DS_TIPO_BEM_CANDIDATO"),
                      Csv.firstPresent(row, "DS_BEM_CANDIDATO"),
                      brlToCents(Csv.firstPresent(row, "VR_BEM_CANDIDATO")),
                      isoDate(Csv.firstPresent(row, "DT_ULT_ATUAL_BEM_CANDIDATO")),
                      parseId));
              scanned[0]++;
              if (rows.size() >= FLUSH_EVERY) {
                inserted[0] += extras.insertDeclaredAssets(List.copyOf(rows));
                rows.clear();
              }
            });
        provenance.finishParse(parseId, scanned[0], 0);
      } finally {
        support.delete(csv.path());
      }
    }
    inserted[0] += extras.insertDeclaredAssets(rows);
    rc.done();
    provenance.release(zip);
    LOGGER.info("  {} bens declarados em {}", inserted[0], year);
    out.put("new_assets", inserted[0]);
    out.put("rows", rc.count());
    return out;
  }
}
