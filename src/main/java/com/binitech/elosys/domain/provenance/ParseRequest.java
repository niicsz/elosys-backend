package com.binitech.elosys.domain.provenance;

public record ParseRequest(
    long collectionId,
    Long collectionFileId,
    String parserName,
    String parserVersion,
    long rowsExtracted,
    long rowsRejected) {}
