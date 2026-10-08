package com.binitech.elosys.domain.assets;

public record CandidatePhotoRow(
    long personId,
    Long historyId,
    String tseCandidacyId,
    Integer year,
    String photoUrl,
    long provenanceId) {}
