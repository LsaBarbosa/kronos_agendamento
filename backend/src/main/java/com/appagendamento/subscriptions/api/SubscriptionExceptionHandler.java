package com.appagendamento.subscriptions.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.subscriptions.application.InvalidPaymentWebhookException;
import com.appagendamento.subscriptions.application.PaymentWebhookTooLargeException;
import com.appagendamento.subscriptions.application.SubscriptionAccessDeniedException;
import com.appagendamento.subscriptions.application.SubscriptionCheckoutConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SubscriptionExceptionHandler {

  private final AuditCorrelationId correlation;

  public SubscriptionExceptionHandler(AuditCorrelationId correlation) {
    this.correlation = correlation;
  }

  @ExceptionHandler(SubscriptionAccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> denied(SubscriptionAccessDeniedException exception) {
    String traceId = correlation.currentTraceId().toString();
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .header("X-Correlation-ID", traceId)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(ApiErrorResponse.of("ACTION_NOT_ALLOWED", "Ação não permitida.", traceId));
  }

  @ExceptionHandler(SubscriptionCheckoutConflictException.class)
  ResponseEntity<ApiErrorResponse> checkoutConflict(
      SubscriptionCheckoutConflictException exception) {
    return error(HttpStatus.CONFLICT, exception.getMessage());
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  ResponseEntity<ApiErrorResponse> idempotencyConflict(IdempotencyKeyReusedException exception) {
    return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED");
  }

  @ExceptionHandler(InvalidPaymentWebhookException.class)
  ResponseEntity<ApiErrorResponse> invalidWebhook(InvalidPaymentWebhookException exception) {
    return error(HttpStatus.UNAUTHORIZED, "PAYMENT_EVENT_NOT_AUTHENTICATED");
  }

  @ExceptionHandler(PaymentWebhookTooLargeException.class)
  ResponseEntity<ApiErrorResponse> webhookTooLarge(PaymentWebhookTooLargeException exception) {
    return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYMENT_EVENT_TOO_LARGE");
  }

  @ExceptionHandler(PaymentIntegrationUnavailableException.class)
  ResponseEntity<ApiErrorResponse> integrationUnavailable(
      PaymentIntegrationUnavailableException exception) {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENT_INTEGRATION_UNAVAILABLE");
  }

  private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code) {
    String traceId = correlation.currentTraceId().toString();
    return ResponseEntity.status(status)
        .header("X-Correlation-ID", traceId)
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(ApiErrorResponse.of(code, "Operação financeira indisponível ou inválida.", traceId));
  }
}
