package com.appagendamento.publicpage.api;

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

@RestControllerAdvice(assignableTypes = PublicPublicationController.class)
public class PublicPageExceptionHandler {

  private final AuditCorrelationId correlationId;

  public PublicPageExceptionHandler(AuditCorrelationId correlationId) {
    this.correlationId = correlationId;
  }

  @ExceptionHandler({
    PublicPageUnavailableException.class,
    PublicationNotFoundException.class,
    AvailabilityServiceNotFoundException.class
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
