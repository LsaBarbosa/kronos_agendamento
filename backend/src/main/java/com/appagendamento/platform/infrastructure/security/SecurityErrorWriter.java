package com.appagendamento.platform.infrastructure.security;

import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(SecurityErrorWriter.class);

  private final ObjectMapper objectMapper;

  public SecurityErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    logPaymentWebhookSecurityFailure(request, 401, exception);
    write(request, response, 401, "AUTHENTICATION_REQUIRED", "Autenticação necessária.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    logPaymentWebhookSecurityFailure(request, 403, exception);
    write(request, response, 403, "ACTION_NOT_ALLOWED", "Ação não permitida.");
  }

  private void logPaymentWebhookSecurityFailure(
      HttpServletRequest request, int status, RuntimeException exception) {
    String requestUri = request.getRequestURI();
    Object errorUriAttribute = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
    String errorUri = errorUriAttribute instanceof String value ? value : "";
    Object errorExceptionAttribute = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
    String errorExceptionType = exceptionType(errorExceptionAttribute);
    boolean webhookRequest = isPaymentWebhookUri(requestUri);
    boolean webhookErrorDispatch = !webhookRequest && isPaymentWebhookUri(errorUri);
    if (webhookRequest || webhookErrorDispatch) {
      LOGGER.warn(
          "payment_webhook_security_failure status={} dispatch={} error_target={} exception_type={} original_exception_type={}",
          status,
          request.getDispatcherType().name(),
          webhookErrorDispatch,
          exception.getClass().getSimpleName(),
          errorExceptionType);
    }
  }

  private String exceptionType(Object value) {
    if (!(value instanceof Throwable failure)) return "unavailable";
    Throwable root = failure;
    for (int depth = 0; depth < 8 && root.getCause() != null && root.getCause() != root; depth++) {
      root = root.getCause();
    }
    return root.getClass().getSimpleName();
  }

  private boolean isPaymentWebhookUri(String uri) {
    return uri.matches(".*/api/v1/webhooks/payments/[a-z0-9][a-z0-9._-]{0,47}");
  }

  private void write(
      HttpServletRequest request,
      HttpServletResponse response,
      int status,
      String code,
      String message)
      throws IOException {
    Object attribute = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
    String traceId = attribute instanceof String value ? value : UUID.randomUUID().toString();
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.setHeader(CorrelationIdFilter.HEADER, traceId);
    if (request.getRequestURI().startsWith("/api/v1/client/")) {
      response.setHeader("Cache-Control", "no-store");
    }
    objectMapper.writeValue(
        response.getOutputStream(), ApiErrorResponse.of(code, message, traceId));
  }
}
