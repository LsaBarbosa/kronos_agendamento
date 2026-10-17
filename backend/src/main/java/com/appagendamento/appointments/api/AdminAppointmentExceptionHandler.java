package com.appagendamento.appointments.api;

import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.AdministrativeScheduleConflictException;
import com.appagendamento.appointments.domain.AdministrativeScheduleVersionConflictException;
import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.AppointmentNotConfirmedException;
import com.appagendamento.appointments.domain.AppointmentNotReschedulableException;
import com.appagendamento.appointments.domain.AppointmentOutcomeNotYetAllowedException;
import com.appagendamento.appointments.domain.AppointmentVersionConflictException;
import com.appagendamento.appointments.domain.InvalidAdminAgendaQueryException;
import com.appagendamento.appointments.domain.SlotNoLongerAvailableException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.idempotency.application.OperationInProgressException;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AdminAppointmentController.class)
public class AdminAppointmentExceptionHandler {

  private final AuditCorrelationId correlationId;

  public AdminAppointmentExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler(InvalidAdminAgendaQueryException.class)
  ResponseEntity<ApiErrorResponse> invalidQuery(
      InvalidAdminAgendaQueryException exception, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Informe datas, filtros, cursor e limite válidos (até 366 datas e 100 itens por página).");
  }

  @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
  ResponseEntity<ApiErrorResponse> invalidCommand(Exception exception, HttpServletRequest request) {
    return error(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Confira cliente, motivo, exceções e os campos informados.");
  }

  @ExceptionHandler(AdministrativeScheduleVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> scheduleConflict(
      AdministrativeScheduleVersionConflictException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "VERSION_CONFLICT",
        "A agenda mudou. Recarregue os dados e confirme novamente as exceções.");
  }

  @ExceptionHandler(AppointmentVersionConflictException.class)
  ResponseEntity<ApiErrorResponse> appointmentVersionConflict(
      AppointmentVersionConflictException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "VERSION_CONFLICT",
        "A reserva mudou. Atualize os dados e confirme novamente a remarcação.");
  }

  @ExceptionHandler(AppointmentNotReschedulableException.class)
  ResponseEntity<ApiErrorResponse> appointmentNotReschedulable(
      AppointmentNotReschedulableException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "APPOINTMENT_NOT_RESCHEDULABLE",
        "Somente reservas confirmadas podem ser remarcadas.");
  }

  @ExceptionHandler(AppointmentNotConfirmedException.class)
  ResponseEntity<ApiErrorResponse> appointmentNotConfirmed(
      AppointmentNotConfirmedException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "INVALID_STATE_TRANSITION",
        "Somente reservas confirmadas podem receber este comando.");
  }

  @ExceptionHandler(AppointmentOutcomeNotYetAllowedException.class)
  ResponseEntity<ApiErrorResponse> outcomeNotYetAllowed(
      AppointmentOutcomeNotYetAllowedException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "INVALID_STATE_TRANSITION",
        "O atendimento ainda não atingiu o limite temporal para este encerramento.");
  }

  @ExceptionHandler(SlotNoLongerAvailableException.class)
  ResponseEntity<ApiErrorResponse> slotConflict(
      SlotNoLongerAvailableException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "SLOT_NO_LONGER_AVAILABLE",
        "O horário conflita com um bloqueio ou atendimento existente.");
  }

  @ExceptionHandler(AdministrativeScheduleConflictException.class)
  ResponseEntity<ApiErrorResponse> scheduleRejected(
      AdministrativeScheduleConflictException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "ADMIN_BOOKING_POLICY_REJECTED",
        "Uma regra não ignorável impede a reserva. Atualize a agenda e revise a decisão.");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ApiErrorResponse> idempotencyConflict(
      IdempotencyKeyReusedException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "A chave já foi usada com outro comando.");
  }

  @ExceptionHandler(OperationInProgressException.class)
  ResponseEntity<ApiErrorResponse> inProgress(
      OperationInProgressException exception, HttpServletRequest request) {
    return error(
        HttpStatus.CONFLICT,
        "OPERATION_IN_PROGRESS",
        "O resultado ainda está sendo confirmado. Aguarde e repita o mesmo comando.");
  }

  @ExceptionHandler(AdminAppointmentNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(
      AdminAppointmentNotFoundException exception, HttpServletRequest request) {
    return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Recurso não encontrado.");
  }

  @ExceptionHandler(AdminAppointmentAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied(
      AdminAppointmentAccessDeniedException exception, HttpServletRequest request) {
    return error(HttpStatus.FORBIDDEN, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  @ExceptionHandler(AppointmentDataUnavailableException.class)
  ResponseEntity<ApiErrorResponse> unavailable(
      AppointmentDataUnavailableException exception, HttpServletRequest request) {
    return error(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AGENDA_TEMPORARILY_UNAVAILABLE",
        "Não foi possível consultar a agenda agora. Tente novamente.");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message) {
    String traceId = correlationId.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .body(ApiErrorResponse.of(code, message, traceId));
  }
}
