package com.appagendamento.appointments.api;

import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.AppointmentNotConfirmedException;
import com.appagendamento.appointments.domain.AppointmentVersionConflictException;
import com.appagendamento.appointments.domain.ClientAppointmentAccessException;
import com.appagendamento.appointments.domain.ClientAppointmentNotFoundException;
import com.appagendamento.appointments.domain.ClientAppointmentPolicyUnavailableException;
import com.appagendamento.appointments.domain.ClientAppointmentQueryException;
import com.appagendamento.appointments.domain.ClientCancellationWindowClosedException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ClientAppointmentController.class)
public class ClientAppointmentExceptionHandler {

  private final AuditCorrelationId correlation;

  public ClientAppointmentExceptionHandler(AuditCorrelationId correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(ClientAppointmentAccessException.class)
  ResponseEntity<ApiErrorResponse> unauthenticated() {
    return error(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Autenticação necessária.");
  }

  @ExceptionHandler(ClientAppointmentNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Reserva não encontrada.");
  }

  @ExceptionHandler(ClientAppointmentQueryException.class)
  ResponseEntity<ApiErrorResponse> invalidQuery() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Filtro ou cursor inválido.");
  }

  @ExceptionHandler(ClientCancellationWindowClosedException.class)
  ResponseEntity<ApiErrorResponse> cutoffClosed() {
    return error(
        HttpStatus.CONFLICT,
        "CANCELLATION_WINDOW_CLOSED",
        "O prazo de cancelamento terminou. Entre em contato com o estabelecimento.");
  }

  @ExceptionHandler(AppointmentNotConfirmedException.class)
  ResponseEntity<ApiErrorResponse> invalidState() {
    return error(
        HttpStatus.CONFLICT,
        "APPOINTMENT_NOT_CANCELLABLE",
        "Somente reservas confirmadas podem ser canceladas.");
  }

  @ExceptionHandler(AppointmentVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> versionConflict() {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT", "A reserva mudou. Atualize os dados.");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ApiErrorResponse> idempotencyConflict() {
    return error(
        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "A chave já foi usada com outro comando.");
  }

  @ExceptionHandler(OperationInProgressException.class)
  ResponseEntity<ApiErrorResponse> inProgress() {
    return error(
        HttpStatus.CONFLICT,
        "OPERATION_IN_PROGRESS",
        "O resultado ainda está sendo confirmado. Repita o mesmo comando.");
  }

  @ExceptionHandler(AppointmentDataUnavailableException.class)
  ResponseEntity<ApiErrorResponse> unavailable() {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CLIENT_APPOINTMENTS_TEMPORARILY_UNAVAILABLE",
        "Não foi possível acessar suas reservas agora. Tente novamente.");
  }

  @ExceptionHandler(ClientAppointmentPolicyUnavailableException.class)
  ResponseEntity<ApiErrorResponse> policyUnavailable() {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CLIENT_APPOINTMENTS_TEMPORARILY_UNAVAILABLE",
        "Não foi possível validar o prazo de cancelamento agora.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    String traceId = correlation.currentTraceId().toString();
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header("X-Correlation-ID", traceId)
        .body(ApiErrorResponse.of(code, message, traceId));
  }
}
