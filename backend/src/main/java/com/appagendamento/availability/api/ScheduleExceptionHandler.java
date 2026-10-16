package com.appagendamento.availability.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.availability.domain.BlockedPeriodStateConflictException;
import com.appagendamento.availability.domain.ConfirmedAppointmentConflictException;
import com.appagendamento.availability.domain.ScheduleAccessDeniedException;
import com.appagendamento.availability.domain.ScheduleExceptionAlreadyExistsException;
import com.appagendamento.availability.domain.ScheduleResourceNotFoundException;
import com.appagendamento.availability.domain.ScheduleValidationException;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {AdminScheduleController.class, ScheduleExceptionMutationController.class})
public class ScheduleExceptionHandler {

  private final AuditCorrelationId correlationId;

  public ScheduleExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(ScheduleValidationException.class)
  ResponseEntity<ApiErrorResponse> validation(
      ScheduleValidationException exception, HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Configuração inválida.");
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
    return error(request, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Configuração inválida.");
  }

  @ExceptionHandler(ScheduleVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> conflict(
      ScheduleVersionConflictException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "VERSION_CONFLICT",
        "A configuração foi alterada. Recarregue antes de decidir como prosseguir.");
  }

  @ExceptionHandler(ScheduleAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> accessDenied(
      ScheduleAccessDeniedException exception, HttpServletRequest request) {
    return error(request, HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler({ScheduleResourceNotFoundException.class})
  ResponseEntity<ApiErrorResponse> notFound(
      RuntimeException exception, HttpServletRequest request) {
    return error(request, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler({
    ScheduleExceptionAlreadyExistsException.class,
    BlockedPeriodStateConflictException.class,
    ConfirmedAppointmentConflictException.class
  })
  ResponseEntity<ApiErrorResponse> stateConflict(
      RuntimeException exception, HttpServletRequest request) {
    String code =
        exception instanceof ConfirmedAppointmentConflictException
            ? "CONFIRMED_APPOINTMENT_CONFLICT"
            : "STATE_CONFLICT";
    return error(
        request, HttpStatus.CONFLICT, code, "A operação não pode ser aplicada ao estado atual.");
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

  private ResponseEntity<ApiErrorResponse> error(
      HttpServletRequest request, HttpStatus status, String code, String message) {
    String traceId = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .body(ApiErrorResponse.of(code, message, traceId));
  }
}
