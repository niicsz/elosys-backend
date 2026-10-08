package com.binitech.elosys.domain.company;

public record CompanyRegistryRow(
    long companyId,
    String cnpj,
    String legalName,
    String tradeName,
    String openedAt,
    String registryStatus,
    String registryStatusDate,
    String legalNature,
    String primaryCnae,
    Long shareCapitalCents,
    String size,
    String city,
    String state,
    long provenanceId) {}
