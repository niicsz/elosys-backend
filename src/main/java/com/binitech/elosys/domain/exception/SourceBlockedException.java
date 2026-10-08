package com.binitech.elosys.domain.exception;

public class SourceBlockedException extends BusinessException {
  public SourceBlockedException(String url) {
    super(
        "HTTP 403 em "
            + url
            + " — bloqueado pelo filtro anti-bot da fonte. Baixe o arquivo num navegador e coloque"
            + " no diretório temporário (elosys.tmp-dir), ou configure"
            + " elosys.download.curl-impersonate-path.");
  }
}
