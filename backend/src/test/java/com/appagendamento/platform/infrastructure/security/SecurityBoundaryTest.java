package com.appagendamento.platform.infrastructure.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.appagendamento.appointments.api.BookingMetrics;
import com.appagendamento.appointments.application.CancelClientAppointment;
import com.appagendamento.appointments.application.CorrectAdministrativeNoShow;
import com.appagendamento.appointments.application.CreateAdministrativeAppointment;
import com.appagendamento.appointments.application.CreatePublicAppointment;
import com.appagendamento.appointments.application.GetAdminAppointments;
import com.appagendamento.appointments.application.GetClientAppointments;
import com.appagendamento.appointments.application.GetPublicBookingIntent;
import com.appagendamento.appointments.application.PreparePublicBookingIntent;
import com.appagendamento.appointments.application.RescheduleAdministrativeAppointment;
import com.appagendamento.appointments.application.TransitionAdministrativeAppointment;
import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.availability.application.GetBlockedPeriods;
import com.appagendamento.availability.application.GetScheduleConfiguration;
import com.appagendamento.availability.application.GetScheduleExceptions;
import com.appagendamento.availability.application.ManageBlockedPeriods;
import com.appagendamento.availability.application.ManageScheduleExceptions;
import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.application.GetService;
import com.appagendamento.catalog.application.ListServices;
import com.appagendamento.catalog.application.SetServiceStatus;
import com.appagendamento.catalog.application.UpdateService;
import com.appagendamento.clientaccess.application.ClientAccessService;
import com.appagendamento.clients.application.ClientNoShowFacts;
import com.appagendamento.clients.application.GetClientNoShowPolicy;
import com.appagendamento.clients.application.LiftClientBookingRestriction;
import com.appagendamento.clients.application.ReconcileClientBookingRestriction;
import com.appagendamento.clients.application.ReplaceClientNoShowPolicy;
import com.appagendamento.clients.application.SearchAdministrativeClients;
import com.appagendamento.exports.application.DownloadExport;
import com.appagendamento.exports.application.ExpireExport;
import com.appagendamento.exports.application.GetExport;
import com.appagendamento.exports.application.IssueExportDownloadHandle;
import com.appagendamento.exports.application.RequestExport;
import com.appagendamento.notifications.application.GetNotificationOperations;
import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.GetPublicBusiness;
import com.appagendamento.publicpage.application.GetPublicService;
import com.appagendamento.publicpage.application.GetPublicationStatus;
import com.appagendamento.publicpage.application.ListPublicServices;
import com.appagendamento.publicpage.application.UpdatePublicContact;
import com.appagendamento.publicpage.domain.PublicPageUnavailableException;
import com.appagendamento.retention.application.GetRetentionAssessment;
import com.appagendamento.subscriptions.api.PublicSubscriptionEligibility;
import com.appagendamento.subscriptions.application.AdmitPaymentWebhook;
import com.appagendamento.subscriptions.application.GetAdminCheckoutOperation;
import com.appagendamento.subscriptions.application.GetAdminSubscription;
import com.appagendamento.subscriptions.application.ListAdminCheckoutOperations;
import com.appagendamento.subscriptions.application.StartSubscriptionCheckout;
import com.appagendamento.tenant.application.GetTenant;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@ImportAutoConfiguration({
  SecurityAutoConfiguration.class,
  ServletWebSecurityAutoConfiguration.class
})
@Import({SecurityConfiguration.class, SecurityErrorWriter.class, CorrelationIdFilter.class})
@TestPropertySource(properties = "app.observability.scrape-token=synthetic-scrape-secret")
class SecurityBoundaryTest {

  @MockitoBean private PublicSubscriptionEligibility subscriptionEligibility;
  @MockitoBean private GetAdminSubscription getAdminSubscription;
  @MockitoBean private StartSubscriptionCheckout startSubscriptionCheckout;
  @MockitoBean private ListAdminCheckoutOperations listAdminCheckoutOperations;
  @MockitoBean private GetAdminCheckoutOperation getAdminCheckoutOperation;
  @MockitoBean private AdmitPaymentWebhook admitPaymentWebhook;
  @Autowired private MockMvc mvc;

  @MockitoBean private CreatePublicAppointment createPublicAppointment;
  @MockitoBean private CreateAdministrativeAppointment createAdministrativeAppointment;
  @MockitoBean private RescheduleAdministrativeAppointment rescheduleAdministrativeAppointment;
  @MockitoBean private TransitionAdministrativeAppointment transitionAdministrativeAppointment;
  @MockitoBean private CorrectAdministrativeNoShow correctAdministrativeNoShow;
  @MockitoBean private SearchAdministrativeClients searchAdministrativeClients;
  @MockitoBean private GetAdminAppointments getAdminAppointments;
  @MockitoBean private PreparePublicBookingIntent preparePublicBookingIntent;
  @MockitoBean private GetPublicBookingIntent getPublicBookingIntent;
  @MockitoBean private GetTenant getTenant;
  @MockitoBean private CreateService createService;
  @MockitoBean private UpdateService updateService;
  @MockitoBean private SetServiceStatus setServiceStatus;
  @MockitoBean private GetService getService;
  @MockitoBean private ListServices listServices;
  @MockitoBean private GetScheduleConfiguration getScheduleConfiguration;
  @MockitoBean private ReplaceWeeklySchedule replaceWeeklySchedule;
  @MockitoBean private ReplaceAvailabilityPolicy replaceAvailabilityPolicy;
  @MockitoBean private GetScheduleExceptions getScheduleExceptions;
  @MockitoBean private GetBlockedPeriods getBlockedPeriods;
  @MockitoBean private ManageScheduleExceptions manageScheduleExceptions;
  @MockitoBean private ManageBlockedPeriods manageBlockedPeriods;
  @MockitoBean private AuditCorrelationId auditCorrelationId;
  @MockitoBean private GetPublicationStatus getPublicationStatus;
  @MockitoBean private ChangePublicationStatus changePublicationStatus;
  @MockitoBean private UpdatePublicContact updatePublicContact;
  @MockitoBean private GetPublicBusiness getPublicBusiness;
  @MockitoBean private ListPublicServices listPublicServices;
  @MockitoBean private GetPublicService getPublicService;
  @MockitoBean private CalculateAvailabilityForDateRange calculateAvailability;
  @MockitoBean private MeterRegistry meterRegistry;
  @MockitoBean private BookingMetrics bookingMetrics;
  @MockitoBean private ClientAccessService clientAccessService;
  @MockitoBean private GetClientAppointments getClientAppointments;
  @MockitoBean private CancelClientAppointment cancelClientAppointment;
  @MockitoBean private GetNotificationOperations getNotificationOperations;
  @MockitoBean private GetClientNoShowPolicy getClientNoShowPolicy;
  @MockitoBean private ClientNoShowFacts clientNoShowFacts;
  @MockitoBean private LiftClientBookingRestriction liftClientBookingRestriction;
  @MockitoBean private ReplaceClientNoShowPolicy replaceClientNoShowPolicy;
  @MockitoBean private ReconcileClientBookingRestriction reconcileClientBookingRestriction;
  @MockitoBean private RequestExport requestExport;
  @MockitoBean private GetExport getExport;
  @MockitoBean private IssueExportDownloadHandle issueExportDownloadHandle;
  @MockitoBean private DownloadExport downloadExport;
  @MockitoBean private ExpireExport expireExport;
  @MockitoBean private GetRetentionAssessment getRetentionAssessment;

  @Test
  void anonymousBusinessRequestReceivesConsistentError() throws Exception {
    String traceId = UUID.randomUUID().toString();
    mvc.perform(get("/api/v1/admin/appointments").header(CorrelationIdFilter.HEADER, traceId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.error.traceId").value(traceId))
        .andExpect(header().string(CorrelationIdFilter.HEADER, traceId));
    mvc.perform(get("/api/v1/admin/subscription"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void evenAuthenticatedBusinessRequestsAreDeniedUntilRulesExist() throws Exception {
    mvc.perform(get("/api/v1/admin/appointments").with(user("test-user")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACTION_NOT_ALLOWED"));
  }

  @Test
  void e2eCodeEmitterAndClientAppointmentRoutesStayClosedOutsideTheirContracts() throws Exception {
    org.mockito.Mockito.when(auditCorrelationId.currentTraceId()).thenReturn(UUID.randomUUID());
    mvc.perform(
            get("/internal/e2e/access-challenges/" + UUID.randomUUID() + "/code")
                .header("X-E2E-Emitter-Secret", "synthetic-secret"))
        .andExpect(status().isUnauthorized());
    org.mockito.Mockito.when(getClientAppointments.list(null, null, null))
        .thenThrow(new com.appagendamento.appointments.domain.ClientAppointmentAccessException());
    mvc.perform(get("/api/v1/client/appointments")).andExpect(status().isUnauthorized());
  }

  @Test
  void publicAvailabilityIsAnonymousButStillRequiresPublishedBusiness() throws Exception {
    org.mockito.Mockito.when(auditCorrelationId.currentTraceId()).thenReturn(UUID.randomUUID());
    org.mockito.Mockito.when(getPublicBusiness.execute("example"))
        .thenThrow(new PublicPageUnavailableException());
    org.mockito.Mockito.when(
            calculateAvailability.executeForAuthorizedTenant(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
        .thenAnswer(
            invocation -> {
              Supplier<UUID> tenantResolver = invocation.getArgument(0);
              tenantResolver.get();
              throw new AssertionError("Negócio indisponível deveria encerrar a consulta");
            });
    mvc.perform(
            get("/api/v1/public/example/availability")
                .param("serviceId", UUID.randomUUID().toString())
                .param("from", "2026-10-19")
                .param("to", "2026-10-19"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PUBLIC_RESOURCE_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/public/example/availability")
                .param("serviceId", UUID.randomUUID().toString())
                .param("from", "2026-10-19")
                .param("to", "2026-10-19")
                .with(user("test-user")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("PUBLIC_RESOURCE_NOT_FOUND"));
  }

  @Test
  void unimplementedPublicRoutesRemainDenied() throws Exception {
    mvc.perform(get("/api/v1/public/example/appointments")).andExpect(status().isUnauthorized());
  }

  @Test
  void privateActuatorEndpointIsNotExposed() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }

  @Test
  void prometheusScrapeRequiresDedicatedServiceCredentialNotTenantSession() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer wrong"))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/prometheus").with(user("tenant-admin")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/actuator/prometheus").header("Authorization", "Bearer synthetic-scrape-secret"))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/actuator/prometheus")
                .header("Authorization", "Bearer synthetic-scrape-secret")
                .header("Cookie", "AGDSESSION=untrusted-session"))
        .andExpect(status().isNotFound());
  }

  @Test
  void validCsrfTokenDoesNotBypassAuthentication() throws Exception {
    mvc.perform(post("/api/v1/admin/appointments").with(csrf()))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/reschedule", UUID.randomUUID())
                .with(csrf())
                .header("Idempotency-Key", "reschedule-security-test-key"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/reschedule", UUID.randomUUID())
                .with(user("test-user"))
                .header("Idempotency-Key", "reschedule-security-test-key"))
        .andExpect(status().isForbidden());
  }
}
