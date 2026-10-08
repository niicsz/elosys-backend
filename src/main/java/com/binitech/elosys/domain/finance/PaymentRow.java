package com.binitech.elosys.domain.finance;

public record PaymentRow(
    String tseExpenseId,
    String tseInstallmentId,
    String accountantId,
    int year,
    String state,
    String documentType,
    String documentNumber,
    String paymentDate,
    Long amountCents,
    String source,
    String origin,
    String nature,
    String species,
    String description,
    long provenanceId) {}
