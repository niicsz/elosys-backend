package com.binitech.elosys.application.usecases.collectors;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort.SourceProfile;
import com.binitech.elosys.application.ports.outbound.TseExtrasRepositoryPort;
import com.binitech.elosys.application.ports.outbound.TseExtrasRepositoryPort.PersonCpfTarget;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.Progress;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.domain.assets.CandidatePhotoRow;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.provenance.CollectionRef;
import com.binitech.elosys.domain.provenance.DownloadResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TsePhotoUrlsJob implements Job {
  private static final Logger LOGGER = LoggerFactory.getLogger(TsePhotoUrlsJob.class);

  static final String PARSER_NAME = "tse.photo_urls";
  static final String PARSER_VERSION = "2.0";
  static final String URL_TEMPLATE =
      "https://divulgacandcontas.tse.jus.br/divulga/rest/v1/candidatura/pesquisar"
          + "?cpf=%s&page=0&size=20";
  static final int DEFAULT_LIMIT = 500;
  static final int DEFAULT_WORKERS = 1;

  private final ProvenanceService provenance;
  private final CollectorSupport support;
  private final SourceDownloadPort downloads;
  private final TseExtrasRepositoryPort extras;
  private final CandidateRepositoryPort candidates;

  public TsePhotoUrlsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    this.provenance = provenance;
    this.support = support;
    this.downloads = downloads;
    this.extras = extras;
    this.candidates = candidates;
  }

  @Override
  public String name() {
    return "tse-photo-urls";
  }

  @Override
  public String description() {
    return "busca incremental da fotoUrl no DivulgaCandContas -> candidate_photo"
        + " [--limit=500] [--person-ids=...] [--years=...] [--workers=1]";
  }

  @Override
  public boolean refreshesManifest() {
    return true;
  }

  @Override
  public Map<String, Object> run(JobParameters p) {
    int limit = p.integer("limit", DEFAULT_LIMIT);
    int workers = Math.max(1, p.integer("workers", DEFAULT_WORKERS));
    List<Long> personIds = p.longList("person-ids");
    List<Integer> years = p.intList("years");
    long sourceId = provenance.sourceId(Sources.TSE_PHOTOS);

    List<PersonCpfTarget> targets =
        !personIds.isEmpty()
            ? extras.photoTargetsForPeople(personIds)
            : !years.isEmpty()
                ? extras.photoTargetsForYears(years, limit)
                : extras.photoTargets(limit);
    LOGGER.info("{} pessoas para consultar (limit={}, workers={})", targets.size(), limit, workers);

    Map<String, Integer> outcomes = new LinkedHashMap<>();
    outcomes.put("fetched", 0);
    outcomes.put("not_found", 0);
    outcomes.put("errors", 0);
    Progress rc = new Progress(LOGGER, "consultas fotoUrl", 50);
    try (ExecutorService pool =
        Executors.newFixedThreadPool(workers, Thread.ofVirtual().factory())) {
      CompletionService<Fetch> done = new ExecutorCompletionService<>(pool);
      for (PersonCpfTarget t : targets) done.submit(() -> fetch(t));
      for (int i = 0; i < targets.size(); i++) {
        rc.tick();
        String outcome;
        try {
          outcome = process(sourceId, done.take().get());
        } catch (ExecutionException e) {
          outcome = "errors";
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new BusinessException("interrompido", e);
        }
        outcomes.merge(outcome, 1, Integer::sum);
      }
    }
    rc.done();
    Map<String, Object> report = new LinkedHashMap<>(outcomes);
    report.putAll(extras.photoSummary());
    LOGGER.info("pronto: {}", report);
    return report;
  }

  private record Fetch(PersonCpfTarget target, Path path, DownloadResult result, Exception error) {}

  private Fetch fetch(PersonCpfTarget t) {
    Path path = support.tmpFile("divulgacand_foto_" + t.personId() + ".json");
    try {
      DownloadResult r =
          downloads.download(URL_TEMPLATE.formatted(t.cpf()), path, SourceProfile.LOOKUP);
      return new Fetch(t, path, r, null);
    } catch (RuntimeException e) {
      support.delete(path);
      return new Fetch(t, null, null, e);
    }
  }

  @SuppressWarnings("unchecked")
  private String process(long sourceId, Fetch f) {
    PersonCpfTarget t = f.target();
    if (f.error() != null) {
      LOGGER.warn("  pessoa {} (cpf {}): {}", t.personId(), t.cpf(), f.error().getMessage());
      return "errors";
    }
    try {
      Object payload;
      try {
        payload = support.readJson(f.path());
      } catch (BusinessException e) {
        LOGGER.warn(
            "  pessoa {} (cpf {}): resposta inválida ({})", t.personId(), t.cpf(), e.getMessage());
        return "errors";
      }
      Object items = payload instanceof Map<?, ?> m ? m.get("items") : null;
      if (!(items instanceof List<?> list) || list.isEmpty()) return "not_found";

      record Match(String sq, Integer year, String url) {}
      List<Match> matches = new ArrayList<>();
      for (Object o : list) {
        if (!(o instanceof Map<?, ?> it)) continue;
        Object id = it.get("id");
        String sq = id == null ? "" : String.valueOf(id instanceof Number n ? n.longValue() : id);
        Object foto = it.get("fotoURl");
        if (foto == null || String.valueOf(foto).isEmpty()) foto = it.get("fotoUrl");
        Integer year = null;
        if (it.get("eleicao") instanceof Map<?, ?> el) {
          Object ano = el.get("ano");
          if (ano instanceof Number n) year = n.intValue();
          else if (ano != null)
            year = com.binitech.elosys.domain.SourceValues.parseIntOrNull(ano.toString());
        }
        if (!sq.isEmpty() && foto != null && !String.valueOf(foto).isEmpty())
          matches.add(new Match(sq, year, String.valueOf(foto)));
      }
      if (matches.isEmpty()) return "not_found";

      String url = URL_TEMPLATE.formatted(t.cpf());
      CollectionRef collection =
          provenance.recordCollection(
              sourceId,
              url,
              f.path(),
              f.result().httpStatus(),
              f.result().contentType(),
              "DivulgaCandContas CPF search for person_id " + t.personId() + ".");
      long parseId =
          provenance.recordParse(collection.id(), PARSER_NAME, PARSER_VERSION, matches.size());
      extras.insertPhotosIgnoringDuplicates(
          matches.stream()
              .map(
                  m ->
                      new CandidatePhotoRow(
                          t.personId(),
                          candidates.historyIdByCandidacy(m.sq()),
                          m.sq(),
                          m.year(),
                          m.url(),
                          parseId))
              .toList());
      return "fetched";
    } finally {
      support.delete(f.path());
    }
  }
}
