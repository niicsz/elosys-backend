package com.binitech.elosys.application.usecases;

import com.binitech.elosys.application.ports.inbound.JobParameters;
import java.util.Map;

public interface Job {
  String name();

  String description();

  default boolean writesData() {
    return true;
  }

  default String reportName() {
    return name().replace('-', '_') + "_report.json";
  }

  default boolean refreshesManifest() {
    return false;
  }

  Map<String, Object> run(JobParameters parameters);
}
