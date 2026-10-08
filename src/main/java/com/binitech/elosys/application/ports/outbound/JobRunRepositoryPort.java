package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.application.ports.inbound.JobRunView;
import java.util.List;
import java.util.Optional;

public interface JobRunRepositoryPort {
  long start(String job, String paramsJson);

  void succeed(long id, String reportJson);

  void fail(long id, String error);

  Optional<JobRunView> find(long id);

  List<JobRunView> recent(int limit);
}
