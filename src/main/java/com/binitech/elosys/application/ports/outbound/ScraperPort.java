package com.binitech.elosys.application.ports.outbound;

import java.util.List;
import java.util.Map;

public interface ScraperPort {
  boolean configured();

  ScrapeRun run(Map<String, Object> actorInput, int maxWaitSeconds);

  record ScrapeRun(String runId, List<Map<String, Object>> items) {}
}
