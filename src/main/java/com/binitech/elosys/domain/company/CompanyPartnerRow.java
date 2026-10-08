package com.binitech.elosys.domain.company;

public record CompanyPartnerRow(
    long companyId,
    String cnpj,
    String partnerName,
    String partnerDocMasked,
    String role,
    String entryDate,
    long provenanceId) {}
