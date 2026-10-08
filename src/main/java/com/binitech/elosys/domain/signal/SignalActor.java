package com.binitech.elosys.domain.signal;

public record SignalActor(String type, long actorId, String role) {
  public static SignalActor person(long id, String role) {
    return new SignalActor("person", id, role);
  }

  public static SignalActor company(long id, String role) {
    return new SignalActor("company", id, role);
  }
}
