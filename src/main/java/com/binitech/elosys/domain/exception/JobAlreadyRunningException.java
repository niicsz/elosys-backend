package com.binitech.elosys.domain.exception;

public class JobAlreadyRunningException extends BusinessException {
  public JobAlreadyRunningException(String job, String holder) {
    super("já existe um job de escrita em execução (" + holder + "); '" + job + "' não iniciado");
  }
}
