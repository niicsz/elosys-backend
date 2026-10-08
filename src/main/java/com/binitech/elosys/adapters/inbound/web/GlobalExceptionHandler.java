package com.binitech.elosys.adapters.inbound.web;

import com.binitech.elosys.domain.exception.BusinessException;
import com.binitech.elosys.domain.exception.JobAlreadyRunningException;
import com.binitech.elosys.domain.exception.NotFoundException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(NotFoundException e) {
    return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
  }

  @ExceptionHandler(JobAlreadyRunningException.class)
  ResponseEntity<Map<String, String>> busy(JobAlreadyRunningException e) {
    return body(HttpStatus.CONFLICT, "job_running", e.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<Map<String, String>> badParam(MethodArgumentTypeMismatchException e) {
    return body(HttpStatus.BAD_REQUEST, "validation_error", "parâmetro inválido: " + e.getName());
  }

  @ExceptionHandler(BusinessException.class)
  ResponseEntity<Map<String, String>> business(BusinessException e) {
    return body(HttpStatus.BAD_REQUEST, "validation_error", e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> other(Exception e) {
    if (e instanceof ErrorResponse er)
      return body(er.getStatusCode(), "request_error", er.getBody().getDetail());
    LOGGER.error("erro inesperado", e);
    return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "erro inesperado");
  }

  private static ResponseEntity<Map<String, String>> body(
      HttpStatusCode status, String error, String message) {
    return ResponseEntity.status(status)
        .body(Map.of("error", error, "message", message == null ? "" : message));
  }
}
