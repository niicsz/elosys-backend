package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.signal.RuleRun;
import com.binitech.elosys.domain.signal.SignalDraft;
import java.util.List;
import java.util.Map;

public interface SignalRepositoryPort {
  void resetRule(String rule);

  long startRun(RuleRun run);

  void insertSignals(long ruleRunId, List<SignalDraft> signals);

  void finishRun(long ruleRunId, long rowsGenerated);

  Map<String, Long> countBySeverity(long ruleRunId);
}
