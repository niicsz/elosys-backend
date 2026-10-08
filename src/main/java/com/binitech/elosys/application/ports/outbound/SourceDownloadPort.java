package com.binitech.elosys.application.ports.outbound;

import com.binitech.elosys.domain.provenance.DownloadResult;
import java.nio.file.Path;

public interface SourceDownloadPort {
  DownloadResult download(String url, Path destination, SourceProfile profile);

  String fetchText(String url, SourceProfile profile);

  enum SourceProfile {
    BULK,
    LOOKUP
  }
}
