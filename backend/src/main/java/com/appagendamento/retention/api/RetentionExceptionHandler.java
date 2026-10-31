package com.appagendamento.retention.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.retention.application.RetentionAuthorizationDeniedException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminRetentionAssessmentController.class)
public class RetentionExceptionHandler {

  private final AuditCorrelationId correlationId;

  public RetentionExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(RetentionAuthorizationDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied(RetentionAuthorizationDeniedException exception) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .header("Cache-Control", "no-store")
        .body(
            new ApiErrorResponse(
                new ApiErrorResponse.Error(
                    "ACTION_NOT_ALLOWED",
                    "Ação não permitida.",
                    correlationId.currentTraceId().toString(),
                    false,
                    java.util.Map.of())));
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<ApiErrorResponse> authorizationUnavailable(DataAccessException exception) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Cache-Control", "no-store")
        .body(
            new ApiErrorResponse(
                new ApiErrorResponse.Error(
                    "RETENTION_ASSESSMENT_UNAVAILABLE",
                    "A avaliação não está disponível no momento.",
                    correlationId.currentTraceId().toString(),
                    true,
                    java.util.Map.of())));
  }
}
