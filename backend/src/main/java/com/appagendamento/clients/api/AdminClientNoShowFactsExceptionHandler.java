package com.appagendamento.clients.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.clients.application.ClientNoShowFactsAccessDeniedException;
import com.appagendamento.clients.application.ClientNoShowFactsNotFoundException;
import com.appagendamento.clients.application.InvalidClientNoShowQueryException;
import com.appagendamento.platform.api.ApiErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminClientNoShowFactsController.class)
public class AdminClientNoShowFactsExceptionHandler {

  private final AuditCorrelationId correlation;

  public AdminClientNoShowFactsExceptionHandler(AuditCorrelationId correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(InvalidClientNoShowQueryException.class)
  ResponseEntity<ApiErrorResponse> invalidQuery() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Cursor ou limite inválido.");
  }

  @ExceptionHandler(ClientNoShowFactsAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied() {
    return error(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(ClientNoShowFactsNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler(ClientNoShowFactsUnavailableException.class)
  ResponseEntity<ApiErrorResponse> unavailable() {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "NO_SHOW_FACTS_TEMPORARILY_UNAVAILABLE",
        "Não foi possível consultar os fatos de faltas agora.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(ApiErrorResponse.of(code, message, correlation.currentTraceId().toString()));
  }
}
