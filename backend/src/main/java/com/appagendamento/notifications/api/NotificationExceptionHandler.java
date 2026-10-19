package com.appagendamento.notifications.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.notifications.application.InvalidNotificationQueryException;
import com.appagendamento.notifications.application.NotificationAdminAccessDeniedException;
import com.appagendamento.notifications.application.NotificationNotFoundException;
import com.appagendamento.platform.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class NotificationExceptionHandler {

  private final AuditCorrelationId correlationId;

  public NotificationExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(NotificationAdminAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> forbidden(NotificationAdminAccessDeniedException exception) {
    return error(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(NotificationNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(NotificationNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler(InvalidNotificationQueryException.class)
  ResponseEntity<ApiErrorResponse> invalidQuery(InvalidNotificationQueryException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Informe um limite entre 1 e 100.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    String traceId = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .body(ApiErrorResponse.of(code, message, traceId));
  }
}
