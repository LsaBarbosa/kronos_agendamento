package com.appagendamento.platform.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

  private final CorrelationIdFilter filter = new CorrelationIdFilter();

  @Test
  void validCorrelationIsSharedByRequestResponseAndMdc() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    String traceId = UUID.randomUUID().toString();
    request.addHeader(CorrelationIdFilter.HEADER, traceId);
    filter.doFilter(
        request,
        response,
        (req, res) -> {
          assertThat(req.getAttribute(CorrelationIdFilter.ATTRIBUTE)).isEqualTo(traceId);
          assertThat(MDC.get("traceId")).isEqualTo(traceId);
        });
    assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(traceId);
    assertThat(MDC.get("traceId")).isNull();
  }

  @Test
  void invalidHeaderIsReplacedInsteadOfReflected() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    request.addHeader(CorrelationIdFilter.HEADER, "invalid\r\nvalue");
    filter.doFilter(request, response, (req, res) -> {});
    String traceId = response.getHeader(CorrelationIdFilter.HEADER);
    assertThat(UUID.fromString(traceId).toString()).isEqualTo(traceId);
  }

  @Test
  void previousMdcIsRestoredEvenWhenChainFails() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    MDC.put("traceId", "outer-context");
    try {
      assertThatThrownBy(
              () ->
                  filter.doFilter(
                      request,
                      response,
                      (req, res) -> {
                        throw new ServletException("failure");
                      }))
          .isInstanceOf(ServletException.class);
      assertThat(MDC.get("traceId")).isEqualTo("outer-context");
    } finally {
      MDC.remove("traceId");
    }
  }

  @Test
  void correlationMdcDoesNotLeakAcrossSequentialRequests() throws Exception {
    String firstId = UUID.randomUUID().toString();
    String secondId = UUID.randomUUID().toString();
    MockHttpServletRequest firstRequest = new MockHttpServletRequest();
    MockHttpServletResponse firstResponse = new MockHttpServletResponse();
    firstRequest.addHeader(CorrelationIdFilter.HEADER, firstId);
    filter.doFilter(
        firstRequest,
        firstResponse,
        (request, response) -> assertThat(MDC.get("traceId")).isEqualTo(firstId));

    assertThat(MDC.get("traceId")).isNull();
    MockHttpServletRequest secondRequest = new MockHttpServletRequest();
    MockHttpServletResponse secondResponse = new MockHttpServletResponse();
    secondRequest.addHeader(CorrelationIdFilter.HEADER, secondId);
    filter.doFilter(
        secondRequest,
        secondResponse,
        (request, response) -> assertThat(MDC.get("traceId")).isEqualTo(secondId));

    assertThat(firstResponse.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(firstId);
    assertThat(secondResponse.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(secondId);
    assertThat(MDC.get("traceId")).isNull();
  }
}
