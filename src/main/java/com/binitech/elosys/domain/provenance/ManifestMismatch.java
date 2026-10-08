package com.binitech.elosys.domain.provenance;

public record ManifestMismatch(long collectionId, String url, String expected, String got) {}
