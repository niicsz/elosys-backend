package com.binitech.elosys.domain.finance;

public record SocialMediaRow(
    Long personId,
    String tseCandidacyId,
    int year,
    String state,
    String platform,
    String url,
    Integer orderInSource,
    long provenanceId) {}
