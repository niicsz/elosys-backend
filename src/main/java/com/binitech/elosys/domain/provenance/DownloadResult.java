package com.binitech.elosys.domain.provenance;

public record DownloadResult(Integer httpStatus, String contentType) {
  public static DownloadResult providedLocally() {
    return new DownloadResult(null, "application/zip");
  }
}
