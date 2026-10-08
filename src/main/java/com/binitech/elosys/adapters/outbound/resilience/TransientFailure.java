package com.binitech.elosys.adapters.outbound.resilience;

public class TransientFailure extends RuntimeException {
  public TransientFailure(String message) {
    super(message);
  }

  public TransientFailure(String message, Throwable cause) {
    super(message, cause);
  }
}
