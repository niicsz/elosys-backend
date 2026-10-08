package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import com.binitech.elosys.application.ports.inbound.JobRunView;
import com.binitech.elosys.application.ports.inbound.JobUseCasePort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort;
import com.binitech.elosys.application.ports.outbound.JobLockPort;
import com.binitech.elosys.application.ports.outbound.JobRunRepositoryPort;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.ReadCachePort;
import com.binitech.elosys.application.ports.outbound.ReadModelRefreshPort;
import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.JobAlreadyRunningException;
import com.binitech.elosys.domain.exception.NotFoundException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JobUseCase implements JobUseCasePort {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobUseCase.class);

  private final Map<String, Job> jobs;
  private final JobLockPort lock;
  private final JobRunRepositoryPort runs;
  private final ReadCachePort cache;
  private final ReadModelRefreshPort readModels;
  private final FileStoragePort files;
  private final JsonPort json;
  private final ProvenanceService provenance;
  private final ExecutorService executor;

  public JobUseCase(
      List<Job> jobs,
      JobLockPort lock,
      JobRunRepositoryPort runs,
      ReadCachePort cache,
      ReadModelRefreshPort readModels,
      FileStoragePort files,
      JsonPort json,
      ProvenanceService provenance,
      ExecutorService executor) {
    Map<String, Job> byName = new LinkedHashMap<>();
    for (Job job : jobs) {
      if (byName.put(job.name(), job) != null)
        throw new IllegalStateException("job duplicado: " + job.name());
    }
    this.jobs = byName;
    this.lock = lock;
    this.runs = runs;
    this.cache = cache;
    this.readModels = readModels;
    this.files = files;
    this.json = json;
    this.provenance = provenance;
    this.executor = executor;
  }

  @Override
  public List<JobDescription> available() {
    return jobs.values().stream()
        .map(j -> new JobDescription(j.name(), j.description(), j.writesData()))
        .toList();
  }

  @Override
  public JobRunView start(String jobName, JobParameters parameters) {
    Job job = job(jobName);
    String token = acquire(job);
    long runId = runs.start(job.name(), json.write(parameters.values()));
    try {
      executor.submit(() -> execute(job, parameters, runId, token));
    } catch (RuntimeException e) {
      release(token);
      runs.fail(runId, "não foi possível agendar: " + e.getMessage());
      throw e;
    }
    return runs.find(runId).orElseThrow();
  }

  @Override
  public JobRunView runNow(String jobName, JobParameters parameters) {
    Job job = job(jobName);
    String token = acquire(job);
    long runId = runs.start(job.name(), json.write(parameters.values()));
    execute(job, parameters, runId, token);
    return runs.find(runId).orElseThrow();
  }

  @Override
  public Optional<JobRunView> find(long runId) {
    return runs.find(runId);
  }

  @Override
  public List<JobRunView> recent(int limit) {
    return runs.recent(limit);
  }

  private Job job(String name) {
    Job job = jobs.get(name);
    if (job == null) throw new NotFoundException("job desconhecido: " + name);
    return job;
  }

  private String acquire(Job job) {
    if (!job.writesData()) return null;
    return lock.tryAcquire(job.name())
        .orElseThrow(
            () ->
                new JobAlreadyRunningException(
                    job.name(), lock.currentHolder().orElse("desconhecido")));
  }

  private void release(String token) {
    if (token != null) lock.release(token);
  }

  private void refreshReadSide(Job job) {
    try {
      readModels.refresh();
    } catch (RuntimeException e) {
      LOGGER.error("[job] falha ao atualizar os agregados de leitura após {}", job.name(), e);
    }
    cache.evictAll();
  }

  private void execute(Job job, JobParameters parameters, long runId, String token) {
    LOGGER.info("[job {}] {} iniciado com {}", runId, job.name(), parameters.values());
    try {
      Map<String, Object> report = job.run(parameters);
      String reportJson = json.writePretty(report);
      files.writeReport(job.reportName(), reportJson);
      if (job.refreshesManifest()) provenance.writeManifest();
      runs.succeed(runId, reportJson);
      LOGGER.info("[job {}] {} concluído", runId, job.name());
    } catch (BusinessException e) {
      LOGGER.warn("[job {}] {} falhou: {}", runId, job.name(), e.getMessage());
      runs.fail(runId, e.getMessage());
    } catch (RuntimeException e) {
      LOGGER.error("[job {}] {} falhou", runId, job.name(), e);
      runs.fail(runId, e.getClass().getSimpleName() + ": " + e.getMessage());
    } finally {
      release(token);
      if (job.writesData()) refreshReadSide(job);
    }
  }
}
