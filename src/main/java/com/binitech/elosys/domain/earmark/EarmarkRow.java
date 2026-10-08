package com.binitech.elosys.domain.earmark;

public record EarmarkRow(
    String earmarkCode,
    int year,
    String earmarkType,
    String authorCode,
    String authorName,
    Long authorPersonId,
    String authorMatchBasis,
    String locality,
    String state,
    String municipality,
    String functionName,
    String subfunctionName,
    String programName,
    String actionName,
    Long committedCents,
    Long paidCents,
    long provenanceId) {}
