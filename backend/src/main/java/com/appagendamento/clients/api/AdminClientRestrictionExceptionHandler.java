package com.appagendamento.clients.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.clients.application.ClientRestrictionAccessDeniedException;
import com.appagendamento.clients.application.ClientRestrictionNotFoundException;
import com.appagendamento.clients.application.ClientRestrictionVersionConflictException;
import com.appagendamento.platform.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminClientRestrictionController.class)
public class AdminClientRestrictionExceptionHandler {

  private final AuditCorrelationId correlation;

  public AdminClientRestrictionExceptionHandler(AuditCorrelationId correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(ClientRestrictionAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> forbidden(ClientRestrictionAccessDeniedException exception) {
    return error(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(ClientRestrictionNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(ClientRestrictionNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler(ClientRestrictionVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> conflict(ClientRestrictionVersionConflictException exception) {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "A restrição foi alterada.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(ApiErrorResponse.of(code, message, correlation.currentTraceId().toString()));
  }
}
