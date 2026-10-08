package com.binitech.elosys.application.ports.inbound;

import java.util.List;
import java.util.Optional;

public interface JobUseCasePort {
  List<JobDescription> available();

  JobRunView start(String job, JobParameters parameters);

  JobRunView runNow(String job, JobParameters parameters);

  Optional<JobRunView> find(long runId);

  List<JobRunView> recent(int limit);

  record JobDescription(String name, String description, boolean writesData) {}
}
