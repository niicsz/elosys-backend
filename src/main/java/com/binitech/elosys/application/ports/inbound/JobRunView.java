package com.binitech.elosys.application.ports.inbound;

import java.time.Instant;

public record JobRunView(
    long id,
    String job,
    String paramsJson,
    String status,
    Instant startedAt,
    Instant finishedAt,
    String reportJson,
    String error) {}
