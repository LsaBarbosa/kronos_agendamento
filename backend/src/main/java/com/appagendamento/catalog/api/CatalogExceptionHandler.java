package com.appagendamento.catalog.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.catalog.application.CatalogAccessDeniedException;
import com.appagendamento.catalog.domain.ServiceNameAlreadyExistsException;
import com.appagendamento.catalog.domain.ServiceNotFoundException;
import com.appagendamento.catalog.domain.ServiceVersionConflictException;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class CatalogExceptionHandler {

  private final AuditCorrelationId correlationId;

  public CatalogExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(ServiceNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(
      ServiceNotFoundException exception, HttpServletRequest request) {
    return error(request, HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Serviço não encontrado.");
  }

  @ExceptionHandler(CatalogAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> forbidden(
      CatalogAccessDeniedException exception, HttpServletRequest request) {
    return error(request, HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(ServiceNameAlreadyExistsException.class)
  ResponseEntity<ApiErrorResponse> nameConflict(
      ServiceNameAlreadyExistsException exception, HttpServletRequest request) {
    return error(request, HttpStatus.CONFLICT, "SERVICE_NAME_CONFLICT", "Nome indisponível.");
  }

  @ExceptionHandler(ServiceVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> versionConflict(
      ServiceVersionConflictException exception, HttpServletRequest request) {
    return error(request, HttpStatus.CONFLICT, "VERSION_CONFLICT", "A versão do serviço mudou.");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ApiErrorResponse> idempotencyConflict(
      IdempotencyKeyReusedException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "A chave já foi usada com outro comando.");
  }

  @ExceptionHandler(OperationInProgressException.class)
  ResponseEntity<ApiErrorResponse> operationInProgress(
      OperationInProgressException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "OPERATION_IN_PROGRESS",
        "A operação ainda está em andamento.");
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dados inválidos.");
  }

  private ResponseEntity<ApiErrorResponse> error(
      HttpServletRequest request, HttpStatus status, String code, String message) {
    String traceId = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .body(ApiErrorResponse.of(code, message, traceId));
  }
}
