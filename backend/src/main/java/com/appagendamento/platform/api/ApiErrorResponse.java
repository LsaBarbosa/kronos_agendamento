package com.appagendamento.platform.api;

import java.util.Map;

/** Envelope inicial alinhado ao documento 03, seção 2. */
public record ApiErrorResponse(Error error) {

  public record Error(
      String code,
      String message,
      String traceId,
      boolean retryable,
      Map<String, Object> details) {}

  public static ApiErrorResponse of(String code, String message, String traceId) {
    return new ApiErrorResponse(new Error(code, message, traceId, false, Map.of()));
  }
}
