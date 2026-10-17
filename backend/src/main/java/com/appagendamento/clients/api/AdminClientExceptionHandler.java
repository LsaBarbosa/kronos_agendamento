package com.appagendamento.clients.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.clients.application.ClientSearchAccessDeniedException;
import com.appagendamento.clients.application.InvalidClientSearchQueryException;
import com.appagendamento.platform.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminClientController.class)
public class AdminClientExceptionHandler {

  private final AuditCorrelationId correlationId;

  public AdminClientExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(InvalidClientSearchQueryException.class)
  ResponseEntity<ApiErrorResponse> invalidQuery() {
    String trace = correlationId.currentTraceId().toString();
    return ResponseEntity.badRequest()
        .body(ApiErrorResponse.of("VALIDATION_ERROR", "Informe ao menos dois caracteres.", trace));
  }

  @ExceptionHandler(ClientSearchAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied() {
    String trace = correlationId.currentTraceId().toString();
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(ApiErrorResponse.of("ACTION_NOT_ALLOWED", "Ação não permitida.", trace));
  }
}
