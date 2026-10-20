package com.appagendamento.clients.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.clients.application.ClientNoShowPolicyAccessDeniedException;
import com.appagendamento.clients.application.ClientNoShowPolicyVersionConflictException;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminClientNoShowPolicyController.class)
public class AdminClientNoShowPolicyExceptionHandler {

  private final AuditCorrelationId correlation;

  public AdminClientNoShowPolicyExceptionHandler(AuditCorrelationId correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(ClientNoShowPolicyVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> conflict(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(
            ApiErrorResponse.of(
                "VERSION_CONFLICT",
                "A política de faltas mudou.",
                correlation.currentTraceId().toString()));
  }

  @ExceptionHandler(ClientNoShowPolicyAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied(HttpServletRequest request) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(
            ApiErrorResponse.of(
                "ACTION_NOT_ALLOWED",
                "Ação não permitida.",
                correlation.currentTraceId().toString()));
  }
}
