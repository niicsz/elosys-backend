package com.binitech.elosys.domain.finance;

public record CampaignOrgRow(
    long companyId,
    Long personId,
    String cnpj,
    String tseCandidacyId,
    String accountantId,
    int year,
    String candidateCpf,
    String candidateName,
    String normalizedName,
    String office,
    String partyAbbr,
    String state,
    long provenanceId) {}
