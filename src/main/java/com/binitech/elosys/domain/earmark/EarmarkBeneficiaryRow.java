package com.binitech.elosys.domain.earmark;

public record EarmarkBeneficiaryRow(
    String earmarkCode,
    String authorCode,
    String yearMonth,
    String beneficiaryDoc,
    String beneficiaryName,
    String beneficiaryType,
    Long beneficiaryCompanyId,
    String state,
    String municipality,
    long amountCents,
    long provenanceId) {}
