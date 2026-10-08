package com.binitech.elosys.config;

import com.binitech.elosys.application.ports.inbound.JobUseCasePort;
import com.binitech.elosys.application.ports.inbound.ReadModelQueryPort;
import com.binitech.elosys.application.ports.outbound.AiReviewRepositoryPort;
import com.binitech.elosys.application.ports.outbound.BuildInfoPort;
import com.binitech.elosys.application.ports.outbound.CampaignFinanceRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CandidateRepositoryPort;
import com.binitech.elosys.application.ports.outbound.CompanyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.DetectionDataPort;
import com.binitech.elosys.application.ports.outbound.FileStoragePort;
import com.binitech.elosys.application.ports.outbound.JobLockPort;
import com.binitech.elosys.application.ports.outbound.JobRunRepositoryPort;
import com.binitech.elosys.application.ports.outbound.JsonPort;
import com.binitech.elosys.application.ports.outbound.LegacyImportPort;
import com.binitech.elosys.application.ports.outbound.LexiconPort;
import com.binitech.elosys.application.ports.outbound.PeopleRepositoryPort;
import com.binitech.elosys.application.ports.outbound.PostClassifierPort;
import com.binitech.elosys.application.ports.outbound.ProvenanceRepositoryPort;
import com.binitech.elosys.application.ports.outbound.ReadCachePort;
import com.binitech.elosys.application.ports.outbound.ReadModelPorts;
import com.binitech.elosys.application.ports.outbound.ReadModelRefreshPort;
import com.binitech.elosys.application.ports.outbound.ScraperPort;
import com.binitech.elosys.application.ports.outbound.SignalRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SignalReviewerPort;
import com.binitech.elosys.application.ports.outbound.SocialRepositoryPort;
import com.binitech.elosys.application.ports.outbound.SourceDownloadPort;
import com.binitech.elosys.application.ports.outbound.TransparencyRepositoryPort;
import com.binitech.elosys.application.ports.outbound.TseExtrasRepositoryPort;
import com.binitech.elosys.application.usecases.CollectorSupport;
import com.binitech.elosys.application.usecases.Job;
import com.binitech.elosys.application.usecases.JobUseCase;
import com.binitech.elosys.application.usecases.MaintenanceJobs;
import com.binitech.elosys.application.usecases.ProvenanceService;
import com.binitech.elosys.application.usecases.ReadModelUseCase;
import com.binitech.elosys.application.usecases.collectors.ReceitaCnpjJob;
import com.binitech.elosys.application.usecases.collectors.SocialXJob;
import com.binitech.elosys.application.usecases.collectors.TransparenciaEarmarksJob;
import com.binitech.elosys.application.usecases.collectors.TransparenciaSanctionsJob;
import com.binitech.elosys.application.usecases.collectors.TseAccountsJob;
import com.binitech.elosys.application.usecases.collectors.TseAssetsJob;
import com.binitech.elosys.application.usecases.collectors.TseCandidatesJob;
import com.binitech.elosys.application.usecases.collectors.TsePhotoUrlsJob;
import com.binitech.elosys.application.usecases.collectors.TseSocialJob;
import com.binitech.elosys.application.usecases.review.AiReviewJob;
import com.binitech.elosys.application.usecases.review.SocialReviewJob;
import com.binitech.elosys.application.usecases.rules.CandidateSupplierPartnerJob;
import com.binitech.elosys.application.usecases.rules.CircularDonationsJob;
import com.binitech.elosys.application.usecases.rules.DisproportionateExpenseJob;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BeanConfiguration {

  @Bean
  ProvenanceService provenanceService(
      ProvenanceRepositoryPort repository,
      SourceDownloadPort downloads,
      FileStoragePort files,
      BuildInfoPort build,
      JsonPort json) {
    return new ProvenanceService(repository, downloads, files, build, json);
  }

  @Bean
  CollectorSupport collectorSupport(FileStoragePort files, JsonPort json) {
    return new CollectorSupport(files, json);
  }

  @Bean
  Job tseCandidatesJob(
      ProvenanceService provenance,
      CollectorSupport support,
      CandidateRepositoryPort candidates,
      PeopleRepositoryPort people) {
    return new TseCandidatesJob(provenance, support, candidates, people);
  }

  @Bean
  Job tseAccountsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      CampaignFinanceRepositoryPort finance,
      CandidateRepositoryPort candidates,
      CompanyRepositoryPort companies,
      PeopleRepositoryPort people) {
    return new TseAccountsJob(provenance, support, finance, candidates, companies, people);
  }

  @Bean
  Job tseSocialJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    return new TseSocialJob(provenance, support, extras, candidates);
  }

  @Bean
  Job tseAssetsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    return new TseAssetsJob(provenance, support, extras, candidates);
  }

  @Bean
  Job tsePhotoUrlsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      TseExtrasRepositoryPort extras,
      CandidateRepositoryPort candidates) {
    return new TsePhotoUrlsJob(provenance, support, downloads, extras, candidates);
  }

  @Bean
  Job receitaCnpjJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      CompanyRepositoryPort companies) {
    return new ReceitaCnpjJob(provenance, support, downloads, companies);
  }

  @Bean
  Job transparenciaSanctionsJob(
      ProvenanceService provenance,
      CollectorSupport support,
      SourceDownloadPort downloads,
      TransparencyRepositoryPort transparency,
      CompanyRepositoryPort companies,
      PeopleRepositoryPort people) {
    return new TransparenciaSanctionsJob(
        provenance, support, downloads, transparency, companies, people);
  }

  @Bean
  Job transparenciaEarmarksJob(
      ProvenanceService provenance,
      CollectorSupport support,
      TransparencyRepositoryPort transparency,
      CandidateRepositoryPort candidates,
      CompanyRepositoryPort companies) {
    return new TransparenciaEarmarksJob(provenance, support, transparency, candidates, companies);
  }

  @Bean
  Job socialXJob(
      SocialRepositoryPort social,
      ScraperPort scraper,
      LexiconPort lexicon,
      ProvenanceRepositoryPort provenance,
      CollectorSupport support) {
    return new SocialXJob(social, scraper, lexicon, provenance, support);
  }

  @Bean
  Job disproportionateExpenseJob(
      DetectionDataPort data,
      SignalRepositoryPort signals,
      ProvenanceService provenance,
      JsonPort json) {
    return new DisproportionateExpenseJob(data, signals, provenance, json);
  }

  @Bean
  Job circularDonationsJob(
      DetectionDataPort data,
      SignalRepositoryPort signals,
      ProvenanceService provenance,
      JsonPort json) {
    return new CircularDonationsJob(data, signals, provenance, json);
  }

  @Bean
  Job candidateSupplierPartnerJob(DetectionDataPort data, PeopleRepositoryPort people) {
    return new CandidateSupplierPartnerJob(data, people);
  }

  @Bean
  Job aiReviewJob(AiReviewRepositoryPort reviews, SignalReviewerPort reviewer, JsonPort json) {
    return new AiReviewJob(reviews, reviewer, json);
  }

  @Bean
  Job socialReviewJob(SocialRepositoryPort social, PostClassifierPort classifier, JsonPort json) {
    return new SocialReviewJob(social, classifier, json);
  }

  @Bean
  Job importSqliteJob(LegacyImportPort importer, ProvenanceService provenance) {
    return new MaintenanceJobs.ImportSqlite(importer, provenance);
  }

  @Bean
  Job manifestJob(ProvenanceService provenance) {
    return new MaintenanceJobs.Manifest(provenance);
  }

  @Bean
  Job verifyJob(ProvenanceService provenance) {
    return new MaintenanceJobs.Verify(provenance);
  }

  @Bean
  ReadModelQueryPort readModelUseCase(
      ReadModelPorts.PoliticianReadPort politicians,
      ReadModelPorts.EntityReadPort entities,
      ReadModelPorts.RankingReadPort rankings,
      ReadModelPorts.SignalReadPort signals,
      ReadModelPorts.GraphReadPort graph) {
    return new ReadModelUseCase(politicians, entities, rankings, signals, graph);
  }

  @Bean(destroyMethod = "shutdown")
  ExecutorService jobExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  JobUseCasePort jobUseCase(
      List<Job> jobs,
      JobLockPort lock,
      JobRunRepositoryPort runs,
      ReadCachePort cache,
      ReadModelRefreshPort readModels,
      FileStoragePort files,
      JsonPort json,
      ProvenanceService provenance,
      ExecutorService jobExecutor) {
    return new JobUseCase(
        jobs, lock, runs, cache, readModels, files, json, provenance, jobExecutor);
  }
}
