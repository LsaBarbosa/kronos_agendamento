package com.appagendamento.publicpage.api;

import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.InvalidAppointmentTimeException;
import com.appagendamento.appointments.domain.PublicAppointmentUnavailableException;
import com.appagendamento.appointments.domain.PublicBookingDigestKeyUnavailableException;
import com.appagendamento.appointments.domain.PublicBookingRecoveryTemporarilyUnavailableException;
import com.appagendamento.appointments.domain.PublicBookingRecoveryUnavailableException;
import com.appagendamento.appointments.domain.SlotNoLongerAvailableException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.availability.application.AppointmentOccupancyUnavailableException;
import com.appagendamento.availability.application.AvailabilityServiceNotFoundException;
import com.appagendamento.availability.application.InvalidAvailabilityWindowException;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.publicpage.application.PublicationNotFoundException;
import com.appagendamento.publicpage.application.PublicationNotReadyException;
import com.appagendamento.publicpage.application.PublicationVersionConflictException;
import com.appagendamento.publicpage.domain.PublicPageUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(
    assignableTypes = {PublicPublicationController.class, PublicAppointmentController.class})
public class PublicPageExceptionHandler {

  private final AuditCorrelationId correlationId;

  public PublicPageExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler({
    PublicPageUnavailableException.class,
    PublicationNotFoundException.class,
    AvailabilityServiceNotFoundException.class,
    PublicAppointmentUnavailableException.class,
    PublicBookingRecoveryUnavailableException.class
  })
  ResponseEntity<ApiErrorResponse> notFound(Exception exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.NOT_FOUND,
        "PUBLIC_RESOURCE_NOT_FOUND",
        "Recurso não encontrado.",
        Map.of(),
        false);
  }

  @ExceptionHandler(PublicBookingDigestKeyUnavailableException.class)
  ResponseEntity<ApiErrorResponse> digestKeyUnavailable(
      PublicBookingDigestKeyUnavailableException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "PUBLIC_BOOKING_DIGEST_KEY_UNAVAILABLE",
        "A recuperação está temporariamente indisponível. Tente novamente mais tarde.",
        Map.of(),
        true);
  }

  @ExceptionHandler(PublicBookingRecoveryTemporarilyUnavailableException.class)
  ResponseEntity<ApiErrorResponse> recoveryUnavailable(
      PublicBookingRecoveryTemporarilyUnavailableException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "PUBLIC_BOOKING_RECOVERY_UNAVAILABLE",
        "A recuperação está temporariamente indisponível. O resultado continua incerto.",
        Map.of(),
        true);
  }

  @ExceptionHandler(AppointmentDataUnavailableException.class)
  ResponseEntity<ApiErrorResponse> appointmentDataUnavailable(
      AppointmentDataUnavailableException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "AVAILABILITY_TEMPORARILY_UNAVAILABLE",
        "Não foi possível verificar a agenda agora. Tente novamente.",
        Map.of(),
        true);
  }

  @ExceptionHandler(SlotNoLongerAvailableException.class)
  ResponseEntity<ApiErrorResponse> slotConflict(
      SlotNoLongerAvailableException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "SLOT_NO_LONGER_AVAILABLE",
        "Este horário não está mais disponível. Escolha outro horário.",
        Map.of(),
        true);
  }

  @ExceptionHandler(InvalidAppointmentTimeException.class)
  ResponseEntity<ApiErrorResponse> invalidAppointmentTime(
      InvalidAppointmentTimeException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.BAD_REQUEST,
        "INVALID_APPOINTMENT_TIME",
        "O horário precisa usar o offset vigente no fuso do negócio.",
        Map.of(),
        false);
  }

  @ExceptionHandler(PublicationNotReadyException.class)
  ResponseEntity<ApiErrorResponse> notReady(
      PublicationNotReadyException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "PUBLICATION_NOT_READY",
        "A publicação não pode ser ativada. Resolva os itens pendentes.",
        Map.of("blockers", exception.blockers()),
        false);
  }

  @ExceptionHandler(PublicationVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> versionConflict(
      PublicationVersionConflictException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "VERSION_CONFLICT",
        "A publicação mudou. Recarregue o estado antes de tentar novamente.",
        Map.of(),
        false);
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ApiErrorResponse> idempotencyConflict(
      IdempotencyKeyReusedException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "A chave já foi usada com outro comando.",
        Map.of(),
        false);
  }

  @ExceptionHandler(OperationInProgressException.class)
  ResponseEntity<ApiErrorResponse> inProgress(
      OperationInProgressException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.CONFLICT,
        "OPERATION_IN_PROGRESS",
        "A operação ainda está em andamento.",
        Map.of(),
        true);
  }

  @ExceptionHandler(InvalidAvailabilityWindowException.class)
  ResponseEntity<ApiErrorResponse> invalidWindow(
      InvalidAvailabilityWindowException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.BAD_REQUEST,
        "INVALID_AVAILABILITY_WINDOW",
        "Informe uma janela válida de até 31 datas locais inclusivas.",
        Map.of(),
        false);
  }

  @ExceptionHandler(AppointmentOccupancyUnavailableException.class)
  ResponseEntity<ApiErrorResponse> occupancyUnavailable(
      AppointmentOccupancyUnavailableException exception, HttpServletRequest request) {
    return error(
        request,
        HttpStatus.SERVICE_UNAVAILABLE,
        "AVAILABILITY_TEMPORARILY_UNAVAILABLE",
        "Não foi possível verificar as ocupações agora. Tente novamente.",
        Map.of(),
        true);
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiErrorResponse> invalidRequest(Exception exception, HttpServletRequest request) {
    return error(
        request, HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Dados inválidos.", Map.of(), false);
  }

  private ResponseEntity<ApiErrorResponse> error(
      HttpServletRequest request,
      HttpStatus status,
      String code,
      String message,
      Map<String, Object> details,
      boolean retryable) {
    String traceId = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .body(
            new ApiErrorResponse(
                new ApiErrorResponse.Error(code, message, traceId, retryable, details)));
  }
}
