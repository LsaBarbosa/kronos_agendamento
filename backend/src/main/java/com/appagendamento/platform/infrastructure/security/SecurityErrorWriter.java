package com.appagendamento.platform.infrastructure.security;

import com.appagendamento.platform.api.ApiErrorResponse;
import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

  private final ObjectMapper objectMapper;

  public SecurityErrorWriter(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  @Override
  public void commence(
      HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
      throws IOException {
    write(request, response, 401, "AUTHENTICATION_REQUIRED", "Autenticação necessária.");
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
      throws IOException {
    write(request, response, 403, "ACTION_NOT_ALLOWED", "Ação não permitida.");
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
