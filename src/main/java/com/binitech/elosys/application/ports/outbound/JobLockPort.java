package com.binitech.elosys.application.ports.outbound;

import java.util.Optional;

public interface JobLockPort {
  Optional<String> tryAcquire(String holder);

  void release(String token);

  Optional<String> currentHolder();
}
