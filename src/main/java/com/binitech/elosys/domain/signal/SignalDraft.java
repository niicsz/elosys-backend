package com.binitech.elosys.domain.signal;

import java.util.List;

public record SignalDraft(
    String type,
    String severity,
    String explanation,
    Long amountCents,
    Integer pathLength,
    List<SignalActor> actors,
    List<SignalEvidence> evidence) {
  public SignalDraft {
    actors = List.copyOf(actors);
    evidence = List.copyOf(evidence);
  }
}
