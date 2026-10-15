package com.appagendamento.platform.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import com.appagendamento.tenant.application.GetTenant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@ImportAutoConfiguration({
  SecurityAutoConfiguration.class,
  ServletWebSecurityAutoConfiguration.class
})
@Import({SecurityConfiguration.class, SecurityErrorWriter.class, CorrelationIdFilter.class})
class SecurityBoundaryTest {
  @Autowired private MockMvc mvc;

  @MockitoBean private GetTenant getTenant;

  @Test
  void anonymousBusinessRequestReceivesConsistentError() throws Exception {
    String traceId = UUID.randomUUID().toString();
    mvc.perform(get("/api/v1/admin/appointments").header(CorrelationIdFilter.HEADER, traceId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.error.traceId").value(traceId))
        .andExpect(header().string(CorrelationIdFilter.HEADER, traceId));
  }

  @Test
  void evenAuthenticatedBusinessRequestsAreDeniedUntilRulesExist() throws Exception {
    mvc.perform(get("/api/v1/admin/appointments").with(user("test-user")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACTION_NOT_ALLOWED"));
  }

  @Test
  void privateActuatorEndpointIsNotExposed() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  void validCsrfTokenDoesNotBypassAuthentication() throws Exception {
    mvc.perform(post("/api/v1/admin/appointments").with(csrf()))
        .andExpect(status().isUnauthorized());
  }
}
