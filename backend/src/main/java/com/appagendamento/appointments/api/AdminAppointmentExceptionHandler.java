package com.appagendamento.appointments.api;

import com.appagendamento.appointments.domain.AdminAppointmentAccessDeniedException;
import com.appagendamento.appointments.domain.AdminAppointmentNotFoundException;
import com.appagendamento.appointments.domain.AppointmentDataUnavailableException;
import com.appagendamento.appointments.domain.InvalidAdminAgendaQueryException;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.platform.api.ApiErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
