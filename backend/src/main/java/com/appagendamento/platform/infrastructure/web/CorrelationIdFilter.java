package com.appagendamento.platform.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlação local; não representa implementação de tracing distribuído. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  public static final String HEADER = "X-Correlation-ID";
  public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".traceId";
  private static final Pattern UUID_PATTERN =
      Pattern.compile(
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String incoming = request.getHeader(HEADER);
    String traceId =
        incoming != null && UUID_PATTERN.matcher(incoming).matches()
            ? incoming.toLowerCase(java.util.Locale.ROOT)
            : UUID.randomUUID().toString();
    String previous = MDC.get("traceId");
    request.setAttribute(ATTRIBUTE, traceId);
    response.setHeader(HEADER, traceId);
    MDC.put("traceId", traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      if (previous == null) {
        MDC.remove("traceId");
      } else {
        MDC.put("traceId", previous);
      }
    }
  }
}
