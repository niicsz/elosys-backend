package com.binitech.elosys.application.usecases.collectors;

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
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.finance.SocialMediaRow;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TseSocialJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TseSocialJob.class);

  static final String PARSER_NAME = "tse.social";
  static final String PARSER_VERSION = "1.0";
  static final String URL_TEMPLATE =
      "https://cdn.tse.jus.br/estatistica/sead/odsele/consulta_cand/rede_social_candidato_%d.zip";
  static final List<Integer> SUPPORTED_YEARS = List.of(2018, 2020, 2022, 2024, 2026);

  private static final List<Map.Entry<String, Pattern>> PLATFORMS =
      List.of(
          Map.entry("facebook", ci("facebook\\.com|fb\\.me")),
          Map.entry("instagram", ci("instagram\\.com")),
          Map.entry("x", ci("twitter\\.com|(?<![\\w.])x\\.com")),
          Map.entry("youtube", ci("youtube\\.com|youtu\\.be")),
          Map.entry("tiktok", ci("tiktok\\.com")),
          Map.entry("linkedin", ci("linkedin\\.com")),
          Map.entry("whatsapp", ci("wa\\.me|whatsapp\\.com")),
          Map.entry("telegram", ci("t\\.me|telegram\\.(me|org)")),
          Map.entry("kwai", ci("kwai\\.com")));

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final TseExtrasRepositoryPort extras;
  private final CandidateRepositoryPort candidates;

  public TseSocialJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    this.provenance = provenance;
    this.support = support;
    this.extras = extras;
    this.candidates = candidates;
  }

  private static Pattern ci(String regex) {
    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
  }

  @Override
  public String name() {
    return "tse-social";
  }

  @Override
  public String description() {
    return "ingere TSE rede_social_candidato -> social_media [--years=...]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  static String platform(String url) {
    for (var p : PLATFORMS) if (p.getValue().matcher(url).find()) return p.getKey();
    String lower = url.toLowerCase(Locale.ROOT);
    return lower.startsWith("http://") || lower.startsWith("https://") ? "website" : "other";
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    List<Integer> years = p.intList("years").isEmpty() ? SUPPORTED_YEARS : p.intList("years");
    List<Integer> unknown = years.stream().filter(y -> !SUPPORTED_YEARS.contains(y)).toList();
    if (!unknown.isEmpty())
      throw new BusinessException("não há rede_social_candidato para: " + unknown);
    provenance.reset(Sources.TSE_SOCIAL, List.of("social_media"));
    if (candidates.countHistory() == 0)
      LOGGER.warn("politician_history vazia — rode tse-candidates antes para ligar as pessoas");
    long sourceId = provenance.sourceId(Sources.TSE_SOCIAL);

    Map<Integer, Object> perYear = new LinkedHashMap<>();
    for (int year : years) perYear.put(year, ingestYear(year, sourceId));
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("years", perYear);
    report.putAll(extras.socialMediaSummary());
    return report;
  }

  private Map<String, Object> ingestYear(int year, long sourceId) {
    AcquiredFile zip =
        provenance.acquire(
            sourceId,
            URL_TEMPLATE.formatted(year),
            "rede_social_candidato_%d.zip".formatted(year),
            SourceProfile.BULK,
            true,
            "TSE CDN; the file may be re-published at the same URL.",
            "TSE CDN; file provided locally. URL is canonical.");
    Map<String, Long> phPerson = candidates.personByCandidacy(year);
    Set<String> seen = new HashSet<>();
    List<SocialMediaRow> rows = new ArrayList<>();
    Progress rc = new Progress(LOGGER, "rede social " + year);
    List<String> members = Csv.preferBrasil(provenance.csvMembers(zip.path()));
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("collection_id", zip.collection().id());
    if (members.isEmpty()) {
      LOGGER.warn("  sem rede_social_candidato no zip de {}", year);
      provenance.release(zip);
      out.put("new_urls", 0);
      out.put("rows", 0);
      return out;
    }
    for (String member : members) {
      ExtractedFile csv = provenance.extract(zip.path(), member);
      try {
        long parseId = provenance.startParse(zip.collection(), csv, PARSER_NAME, PARSER_VERSION);
        support.forEachRecord(
            csv.path(),
            row -> {
              rc.tick();
              String sq = Csv.firstPresent(row, "SQ_CANDIDATO");
              String rawUrl = Csv.firstPresent(row, "DS_URL");
              if (sq == null || sq.isEmpty() || rawUrl == null || rawUrl.isEmpty()) return;
              String url = rawUrl.strip();
              if (!seen.add(sq + "\u0000" + url)) return;
              String order = Csv.firstPresent(row, "NR_ORDEM_REDE_SOCIAL");
              String yearRaw = Csv.firstPresent(row, "AA_ELEICAO");
              rows.add(
                  new SocialMediaRow(
                      phPerson.get(sq),
                      sq,
                      yearRaw == null || yearRaw.isEmpty() ? year : Integer.parseInt(yearRaw),
                      Csv.firstPresent(row, "SG_UF"),
                      platform(url),
                      url,
                      SourceValues.allAsciiDigits(order) ? Integer.valueOf(order) : null,
                      parseId));
            });
        provenance.finishParse(parseId, rows.size(), 0);
      } finally {
        support.delete(csv.path());
      }
    }
    rc.done();
    int inserted = extras.insertSocialMedia(rows);
    provenance.release(zip);
    LOGGER.info("  {} URLs de redes sociais em {}", inserted, year);
    out.put("new_urls", inserted);
    out.put("rows", rc.count());
    return out;
  }
}
