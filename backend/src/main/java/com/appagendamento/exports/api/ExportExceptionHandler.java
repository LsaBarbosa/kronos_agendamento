package com.appagendamento.exports.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.exports.domain.ExportAlreadyActiveException;
import com.appagendamento.exports.domain.ExportAuthorizationDeniedException;
import com.appagendamento.exports.domain.ExportConflictException;
import com.appagendamento.exports.domain.ExportExpiredException;
import com.appagendamento.exports.domain.ExportNotFoundException;
import com.appagendamento.exports.domain.ExportUnavailableException;
import com.appagendamento.exports.domain.InvalidExportRequestException;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminExportController.class)
public class ExportExceptionHandler {

  private final AuditCorrelationId correlationId;

  public ExportExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler({
    InvalidExportRequestException.class,
    MethodArgumentNotValidException.class,
    HttpMessageNotReadableException.class,
    MissingRequestHeaderException.class,
    jakarta.validation.ConstraintViolationException.class
  })
  ResponseEntity<ApiErrorResponse> invalid(Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Confira formato, categoria e filtros.");
  }

  @ExceptionHandler(InvalidIfMatchException.class)
  ResponseEntity<ApiErrorResponse> invalidIfMatch(InvalidIfMatchException exception) {
    return error(
        HttpStatus.BAD_REQUEST, "INVALID_IF_MATCH", "If-Match deve conter o ETag forte do job.");
  }

  @ExceptionHandler(ExportAlreadyActiveException.class)
  ResponseEntity<ApiErrorResponse> active(ExportAlreadyActiveException exception) {
    return error(HttpStatus.CONFLICT, "EXPORT_ALREADY_ACTIVE", "Já existe uma exportação ativa.");
  }

  @ExceptionHandler({IdempotencyKeyReusedException.class, OperationInProgressException.class})
  ResponseEntity<ApiErrorResponse> idempotency(RuntimeException exception) {
    String code =
        exception instanceof IdempotencyKeyReusedException
            ? "IDEMPOTENCY_KEY_REUSED"
            : "OPERATION_IN_PROGRESS";
    return error(HttpStatus.CONFLICT, code, "Recupere a operação original com a mesma intenção.");
  }

  @ExceptionHandler(ExportConflictException.class)
  ResponseEntity<ApiErrorResponse> conflict(ExportConflictException exception) {
    HttpStatus status =
        exception.code().equals("VERSION_CONFLICT")
            ? HttpStatus.PRECONDITION_FAILED
            : HttpStatus.CONFLICT;
    return error(
        status, exception.code(), "A exportação mudou ou não pode ser usada neste estado.");
  }

  @ExceptionHandler(ExportExpiredException.class)
  ResponseEntity<ApiErrorResponse> expired(ExportExpiredException exception) {
    return error(HttpStatus.GONE, "EXPORT_EXPIRED", "O arquivo expirou e não pode ser reativado.");
  }

  @ExceptionHandler(ExportNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(ExportNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler(ExportAuthorizationDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied(ExportAuthorizationDeniedException exception) {
    return error(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(ExportUnavailableException.class)
  ResponseEntity<ApiErrorResponse> unavailable(ExportUnavailableException exception) {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "EXPORT_INFRASTRUCTURE_UNAVAILABLE",
        "Exportação indisponível porque storage criptografado ou chave não está configurado.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    String trace = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", trace)
        .header("Cache-Control", "no-store")
        .body(ApiErrorResponse.of(code, message, trace));
  }
}
