package com.binitech.elosys.domain.provenance;

public record SourceDefinition(
    String name, String agency, String type, String baseUrl, String legalBasis, String notes) {}
