package com.binitech.elosys.domain.assets;

public record DeclaredAssetRow(
    Long personId,
    Long historyId,
    String tseCandidacyId,
    int year,
    String state,
    Integer assetOrder,
    String assetType,
    String description,
    Long valueCents,
    String sourceUpdatedAt,
    long provenanceId) {}
