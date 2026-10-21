package com.appagendamento.subscriptions.api;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.subscriptions.application.SubscriptionAccessDeniedException;
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
}
