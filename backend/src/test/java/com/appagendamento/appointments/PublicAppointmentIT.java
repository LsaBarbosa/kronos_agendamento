package com.appagendamento.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.appointments.application.AppointmentRepository;
import com.appagendamento.appointments.application.CreateAdministrativeAppointment;
import com.appagendamento.appointments.application.CreateAdministrativeAppointmentCommand;
import com.appagendamento.appointments.application.CreatePublicAppointment;
import com.appagendamento.appointments.application.PublicAppointmentReceipt;
import com.appagendamento.appointments.domain.Appointment;
import com.appagendamento.appointments.domain.AppointmentStatus;
import com.appagendamento.appointments.domain.SlotNoLongerAvailableException;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.availability.application.ManageBlockedPeriods;
import com.appagendamento.availability.application.ManageScheduleExceptions;
import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.availability.domain.ConfirmedAppointmentConflictException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.application.SetServiceStatus;
import com.appagendamento.catalog.application.UpdateService;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.clients.api.ClientBookingRestrictionQuery;
import com.appagendamento.clients.api.ClientBookingRestrictionStatus;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.application.UpdatePublicContact;
import com.appagendamento.support.PostgresTestEnvironment;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "identity.oidc.enabled=true",
      "identity.oidc.issuer-uri=https://identity.test/realms/agendamento",
      "identity.oidc.authorization-uri=https://identity.test/realms/agendamento/protocol/openid-connect/auth",
      "identity.oidc.token-uri=https://identity.test/realms/agendamento/protocol/openid-connect/token",
      "identity.oidc.jwk-set-uri=https://identity.test/realms/agendamento/protocol/openid-connect/certs",
      "identity.oidc.user-info-uri=https://identity.test/realms/agendamento/protocol/openid-connect/userinfo",
      "identity.oidc.client-id=integration-test",
      "identity.oidc.client-secret=not-a-real-secret",
      "identity.oidc.redirect-uri={baseUrl}/login/oauth2/code/keycloak"
    })
@AutoConfigureMockMvc
class PublicAppointmentIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private CreateService createService;
  @Autowired private ReplaceWeeklySchedule replaceWeeklySchedule;
  @Autowired private ReplaceAvailabilityPolicy replaceAvailabilityPolicy;
  @Autowired private UpdatePublicContact updatePublicContact;
  @Autowired private ChangePublicationStatus changePublicationStatus;
  @Autowired private CalculateAvailabilityForDateRange availability;
  @Autowired private ManageBlockedPeriods manageBlockedPeriods;
  @Autowired private ManageScheduleExceptions manageScheduleExceptions;
  @Autowired private CreatePublicAppointment createAppointment;
  @Autowired private CreateAdministrativeAppointment createAdministrativeAppointment;
  @Autowired private AppointmentRepository appointmentRepository;
  @Autowired private UpdateService updateService;
  @Autowired private SetServiceStatus setServiceStatus;
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private java.time.Clock clock;
  @MockitoBean private PublicationEvidence publicationEvidence;
  @MockitoBean private ClientBookingRestrictionQuery clientRestrictions;

  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private UUID serviceId;
  private AuthenticatedAdmin actor;
  private String slug;
  private LocalDate bookingDate;
  private String requestedStart;
  private final Map<String, String> recoveryCapabilities = new ConcurrentHashMap<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void preparePublishedTenant() throws Exception {
    org.mockito.Mockito.when(
            publicationEvidence.unavailableEvidence(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    org.mockito.Mockito.when(
            clientRestrictions.status(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(ClientBookingRestrictionStatus.NOT_TRACKED);
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    slug = "book-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Booking fixture', ?, ?, 'ACTIVE')",
        tenantId,
        slug,
        ZONE.getId());
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    actor = new TestAdmin(adminId, subject, tenantId);
    serviceId =
        createService
            .execute(
                actor,
                "booking-service-" + UUID.randomUUID(),
                "Consulta",
                "Atendimento",
                30,
                15,
                ServiceStatus.ACTIVE)
            .value()
            .serviceId();
    replaceWeeklySchedule.execute(
        actor,
        "booking-schedule-" + UUID.randomUUID(),
        0,
        List.of(new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON)));
    replaceAvailabilityPolicy.execute(
        actor, "booking-policy-" + UUID.randomUUID(), 0, 30, 1, 30, 2, 2);
    updatePublicContact.execute(actor, "booking-contact-" + UUID.randomUUID(), "+5511999990000", 0);
    changePublicationStatus.execute(actor, "booking-publish-" + UUID.randomUUID(), true, 1);
    bookingDate = LocalDate.now(ZONE).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
    requestedStart =
        availability
            .execute(tenantId, serviceId, bookingDate, bookingDate)
            .days()
            .getFirst()
            .slots()
            .getFirst()
            .startsAt()
            .toString();
  }

  @Test
  void confirmsPersistsSnapshotAndReplayDoesNotRepeatEffects() throws Exception {
    String key = "book-intent-" + UUID.randomUUID();
    String body = requestBody(serviceId, requestedStart, "  José Silva  ", "+55 (11) 99999-1234");
    int auditEventsBeforeBooking = count("audit_event", tenantId);
    submitBooking(key, body)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.startsAt").value(org.hamcrest.Matchers.endsWith("-03:00")))
        .andExpect(jsonPath("$.serviceName").value("Consulta"))
        .andExpect(jsonPath("$.timeZone").value(ZONE.getId()))
        .andExpect(jsonPath("$.client").doesNotExist())
        .andExpect(header().exists("X-Operation-Id"));

    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    submitBooking(key, body)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()));
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(count("audit_event", tenantId)).isEqualTo(auditEventsBeforeBooking + 1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(1);

    submitBooking("lost-slot-" + UUID.randomUUID(), body)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);

    mvc.perform(
            get("/api/v1/public/" + slug + "/availability")
                .param("serviceId", serviceId.toString())
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString()))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.days[0].slots[*].startsAt")
                .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(requestedStart))));

    submitBooking(key, requestBody(serviceId, requestedStart, "Outro nome", "+5511999991234"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

    setServiceStatus.execute(
        actor, serviceId, "deactivate-agenda-" + UUID.randomUUID(), 0, ServiceStatus.INACTIVE);
    assertThat(
            appJdbc.queryForObject(
                "SELECT normalized_phone FROM agendamento.client WHERE tenant_id = ?",
                String.class,
                tenantId))
        .isEqualTo("+5511999991234");
    assertThat(
            appJdbc.queryForObject(
                "SELECT client_name_snapshot FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("José Silva");
    assertThat(
            appJdbc.queryForObject(
                "SELECT service_name_snapshot || ':' || service_duration_minutes || ':' || service_buffer_after_minutes || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Consulta:30:15:1");
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(count("audit_event", tenantId)).isEqualTo(auditEventsBeforeBooking + 2);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);

    long version =
        appJdbc.queryForObject(
            "SELECT version FROM agendamento.service WHERE tenant_id = ? AND service_id = ?",
            Long.class,
            tenantId,
            serviceId);
    updateService.execute(
        actor,
        serviceId,
        "change-service-after-booking-" + UUID.randomUUID(),
        version,
        "Nova consulta",
        null,
        null,
        null);
    assertThat(
            appJdbc.queryForObject(
                "SELECT service_name_snapshot FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Consulta");

    changePublicationStatus.execute(actor, "revoke-before-replay-" + UUID.randomUUID(), false, 2);
    submitBooking(key, body).andExpect(status().isNotFound());
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
  }

  @Test
  void administratorReadsTenantScopedAgendaDetailsAndActualHistory() throws Exception {
    String key = "admin-agenda-" + UUID.randomUUID();
    submitBooking(
            key, requestBody(serviceId, requestedStart, "Cliente da agenda", "+5511999991234"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);

    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString())
                .param("status", "CONFIRMED")
                .param("serviceId", serviceId.toString())
                .param("limit", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeZone").value(ZONE.getId()))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.items[0].clientName").value("Cliente da agenda"))
        .andExpect(jsonPath("$.items[0].serviceName").value("Consulta"))
        .andExpect(jsonPath("$.items[0].durationMinutes").value(30))
        .andExpect(jsonPath("$.items[0].bufferAfterMinutes").value(15))
        .andExpect(jsonPath("$.items[0].status").value("CONFIRMED"))
        .andExpect(jsonPath("$.items[0].normalizedPhone").doesNotExist())
        .andExpect(jsonPath("$.items[0].recoveryCapability").doesNotExist());

    mvc.perform(
            get("/api/v1/admin/appointments/{id}", appointmentId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.occupiedUntil").exists());

    mvc.perform(
            get("/api/v1/admin/appointments/{id}/history", appointmentId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].reasonCode").value("APPOINTMENT_CREATED"));

    mvc.perform(
            get("/api/v1/admin/appointments/{id}", UUID.randomUUID())
                .with(authentication(principalToken(actor))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

    UUID foreignAppointmentId = seedForeignAppointment(bookingDate);
    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].appointmentId")
                .value(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem(foreignAppointmentId.toString()))));
    mvc.perform(
            get("/api/v1/admin/appointments/{id}", foreignAppointmentId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/admin/appointments/{id}/history", foreignAppointmentId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
  }

  @Test
  void administratorCreatesAndReplaysAnAppointmentWithoutDuplicatingEffects() throws Exception {
    long revision =
        appJdbc.queryForObject(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
            Long.class,
            tenantId);
    var start = java.time.OffsetDateTime.parse(requestedStart);
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + start
            + "\",\"client\":{\"name\":\"Admin Client\",\"phone\":\"+55 (11) 99999-7777\"},"
            + "\"overridePolicies\":[],\"expectedScheduleVersion\":"
            + revision
            + "}";
    String key = "admin-create-" + UUID.randomUUID();
    int beforeAudit = countByAction("APPOINTMENT_CONFIRMED");
    int beforeOutbox = countByEvent("AppointmentConfirmed");

    var first =
        mvc.perform(
                post("/api/v1/admin/appointments")
                    .with(authentication(principalToken(actor)))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.appointmentId").exists())
            .andReturn();
    String appointmentId =
        objectMapper
            .readTree(first.getResponse().getContentAsString())
            .get("appointmentId")
            .asText();

    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId));

    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(beforeAudit + 1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(beforeOutbox + 1);
    mvc.perform(
            get("/api/v1/admin/appointments/{id}/history", UUID.fromString(appointmentId))
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].actorType").value("ADMIN"))
        .andExpect(jsonPath("$.items[0].reasonCode").value("ADMIN_BOOKING"))
        .andExpect(jsonPath("$.items[0].reason").value(org.hamcrest.Matchers.nullValue()));
  }

  @Test
  void administrativeBookingRequiresCsrfCurrentRevisionAndTenantOwnedReferences() throws Exception {
    long revision =
        appJdbc.queryForObject(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
            Long.class,
            tenantId);
    var start = java.time.OffsetDateTime.parse(requestedStart);
    String base =
        "\"startsAt\":\""
            + start
            + "\",\"client\":{\"name\":\"Tenant Client\",\"phone\":\"+5511999997788\"},"
            + "\"overridePolicies\":[],\"expectedScheduleVersion\":";

    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .header("Idempotency-Key", "admin-no-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + serviceId + "\"," + base + revision + "}"))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-stale-revision-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + serviceId + "\"," + base + (revision + 1) + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

    UUID otherTenant = UUID.randomUUID();
    UUID otherService = UUID.randomUUID();
    UUID otherClient = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Private booking fixture', ?, 'UTC', 'ACTIVE')",
        otherTenant,
        "private-admin-booking-" + UUID.randomUUID().toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status) VALUES (?, ?, 'Private', 'private', 30, 0, 'ACTIVE')",
        otherService,
        otherTenant);
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Private Client', '+5511999997799')",
        otherClient,
        otherTenant);

    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-foreign-service-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"serviceId\":\"" + otherService + "\"," + base + revision + "}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ADMIN_BOOKING_POLICY_REJECTED"));

    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-foreign-client-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"serviceId\":\""
                        + serviceId
                        + "\",\"startsAt\":\""
                        + start
                        + "\",\"clientId\":\""
                        + otherClient
                        + "\",\"overridePolicies\":[],\"expectedScheduleVersion\":"
                        + revision
                        + "}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

    assertThat(count("appointment", tenantId)).isZero();
    assertThat(count("client", tenantId)).isZero();
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isZero();
    assertThat(countByEvent("AppointmentConfirmed")).isZero();
  }

  @Test
  void administrativeReplayIsDeniedAfterMembershipRevocation() throws Exception {
    long revision =
        appJdbc.queryForObject(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
            Long.class,
            tenantId);
    var start = java.time.OffsetDateTime.parse(requestedStart);
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + start
            + "\",\"client\":{\"name\":\"Replay Client\",\"phone\":\"+5511999997789\"},"
            + "\"overridePolicies\":[],\"expectedScheduleVersion\":"
            + revision
            + "}";
    String key = "admin-revoked-replay-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isCreated());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        actor.adminUserId());
    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("ACTION_NOT_ALLOWED"));

    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
  }

  @Test
  void administrativeBookingCanUseAnExistingTenantClientWithoutReturningTheirPhone()
      throws Exception {
    UUID existingClient = UUID.randomUUID();
    String phone = "+5511999996670";
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Cliente existente', ?)",
        existingClient,
        tenantId,
        phone);
    long revision = currentScheduleRevision();
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + requestedStart
            + "\",\"clientId\":\""
            + existingClient
            + "\",\"overridePolicies\":[],\"expectedScheduleVersion\":"
            + revision
            + "}";

    var response =
        mvc.perform(
                post("/api/v1/admin/appointments")
                    .with(authentication(principalToken(actor)))
                    .with(csrf())
                    .header("Idempotency-Key", "admin-existing-client-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();

    UUID appointmentId =
        UUID.fromString(
            objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("appointmentId")
                .asText());
    assertThat(
            appJdbc.queryForObject(
                "SELECT client_id FROM agendamento.appointment WHERE appointment_id = ?",
                UUID.class,
                appointmentId))
        .isEqualTo(existingClient);
    assertThat(count("client", tenantId)).isEqualTo(1);
    assertThat(response.getResponse().getContentAsString()).doesNotContain(phone);

    var search =
        mvc.perform(
                get("/api/v1/admin/clients")
                    .with(authentication(principalToken(actor)))
                    .param("query", "Cliente exist"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].clientId").value(existingClient.toString()))
            .andExpect(jsonPath("$[0].displayName").value("Cliente existente"))
            .andReturn();
    assertThat(search.getResponse().getContentAsString()).doesNotContain(phone);
  }

  @Test
  void controlledRestrictionAdapterAllowsAdminBookingAndRecordsRestrictedResult() throws Exception {
    UUID restrictedClient = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Cliente com restrição', '+5511999996671')",
        restrictedClient,
        tenantId);
    org.mockito.Mockito.when(clientRestrictions.status(tenantId, restrictedClient))
        .thenReturn(ClientBookingRestrictionStatus.RESTRICTED);
    long revision = currentScheduleRevision();
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + requestedStart
            + "\",\"clientId\":\""
            + restrictedClient
            + "\",\"overridePolicies\":[],\"expectedScheduleVersion\":"
            + revision
            + "}";

    var response =
        mvc.perform(
                post("/api/v1/admin/appointments")
                    .with(authentication(principalToken(actor)))
                    .with(csrf())
                    .header("Idempotency-Key", "admin-restricted-client-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    UUID appointmentId =
        UUID.fromString(
            objectMapper
                .readTree(response.getResponse().getContentAsString())
                .get("appointmentId")
                .asText());

    assertThat(
            appJdbc.queryForObject(
                "SELECT details->>'client_restriction_status' FROM agendamento.audit_event WHERE target_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("RESTRICTED");
    org.mockito.Mockito.verify(clientRestrictions).status(tenantId, restrictedClient);
  }

  @Test
  void administrativeEncaixeRequiresReasonAndNeverIgnoresOverlap() throws Exception {
    long revision =
        appJdbc.queryForObject(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
            Long.class,
            tenantId);
    var outside = bookingDate.atTime(8, 0).atZone(ZONE).toOffsetDateTime();
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + outside
            + "\",\"client\":{\"name\":\"Encaixe\",\"phone\":\"+5511999996666\"},"
            + "\"overridePolicies\":[\"OUTSIDE_WEEKLY_SCHEDULE\"],\"reason\":\"Solicitação administrativa\","
            + "\"expectedScheduleVersion\":"
            + revision
            + "}";
    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-encaixe-no-reason-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace(",\"reason\":\"Solicitação administrativa\"", "")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-encaixe-unknown-policy-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("OUTSIDE_WEEKLY_SCHEDULE", "HORIZON")))
        .andExpect(status().isBadRequest());
    assertThat(count("appointment", tenantId)).isZero();

    var accepted =
        mvc.perform(
                post("/api/v1/admin/appointments")
                    .with(authentication(principalToken(actor)))
                    .with(csrf())
                    .header("Idempotency-Key", "admin-encaixe-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    UUID appointmentId =
        UUID.fromString(
            objectMapper
                .readTree(accepted.getResponse().getContentAsString())
                .get("appointmentId")
                .asText());
    assertThat(
            appJdbc.queryForObject(
                "SELECT reason_code FROM agendamento.appointment_history WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("ADMIN_BOOKING_OVERRIDE");
    assertThat(
            appJdbc.queryForObject(
                "SELECT reason FROM agendamento.appointment_history WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Solicitação administrativa");
    assertThat(
            appJdbc.queryForObject(
                "SELECT details->'override_policies' FROM agendamento.audit_event WHERE target_id = ?",
                String.class,
                appointmentId))
        .contains("OUTSIDE_WEEKLY_SCHEDULE");
    assertThat(
            appJdbc.queryForObject(
                "SELECT payload::text FROM agendamento.outbox_event WHERE aggregate_id = ?",
                String.class,
                appointmentId))
        .doesNotContain("Solicitação administrativa", "+5511999996666");

    String overlapBody = body.replace("+5511999996666", "+5511999996667");
    mvc.perform(
            post("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-overlap-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(overlapBody))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));
    assertThat(count("appointment", tenantId)).isEqualTo(1);
  }

  @Test
  void administrativeOutsideScheduleOverrideDoesNotReopenClosedDate() {
    manageScheduleExceptions.create(
        actor,
        "admin-closed-" + UUID.randomUUID(),
        bookingDate,
        ScheduleExceptionKind.CLOSED,
        List.of(),
        "Fechamento de teste");
    long revision = currentScheduleRevision();
    int appointmentsBefore = count("appointment", tenantId);
    int clientsBefore = count("client", tenantId);
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_CONFIRMED");
    int outboxBefore = countByEvent("AppointmentConfirmed");

    int status =
        submitAdministrativeBooking(
            "admin-closed-rejected-" + UUID.randomUUID(),
            bookingDate.atTime(8, 0).atZone(ZONE).toOffsetDateTime().toString(),
            revision,
            List.of("OUTSIDE_WEEKLY_SCHEDULE"),
            "Exceção explicitamente solicitada",
            "+5511999996101");

    assertThat(status).isEqualTo(409);
    assertThat(currentScheduleRevision()).isEqualTo(revision);
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);
    assertThat(count("client", tenantId)).isEqualTo(clientsBefore);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(outboxBefore);
  }

  @Test
  void specialPeriodsAreFinalAndMustContainTheCompleteOccupiedInterval() {
    LocalDate specialDate = bookingDate.plusWeeks(1);
    manageScheduleExceptions.create(
        actor,
        "admin-special-" + UUID.randomUUID(),
        specialDate,
        ScheduleExceptionKind.SPECIAL,
        List.of(new SpecialPeriod(LocalTime.of(9, 0), LocalTime.of(11, 0))),
        "Período especial de teste");
    long revision = currentScheduleRevision();

    int acceptedStatus =
        submitAdministrativeBooking(
            "admin-special-inside-" + UUID.randomUUID(),
            specialDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString(),
            revision,
            List.of(),
            null,
            "+5511999996102");
    assertThat(acceptedStatus).isEqualTo(201);
    int appointmentsAfterAccepted = count("appointment", tenantId);
    int clientsAfterAccepted = count("client", tenantId);
    int historyAfterAccepted = count("appointment_history", tenantId);
    int auditAfterAccepted = countByAction("APPOINTMENT_CONFIRMED");
    int outboxAfterAccepted = countByEvent("AppointmentConfirmed");

    int outsideStatus =
        submitAdministrativeBooking(
            "admin-special-outside-" + UUID.randomUUID(),
            specialDate.atTime(8, 0).atZone(ZONE).toOffsetDateTime().toString(),
            revision,
            List.of("OUTSIDE_WEEKLY_SCHEDULE"),
            "Não ultrapassar SPECIAL",
            "+5511999996103");
    assertThat(outsideStatus).isEqualTo(409);
    assertThat(currentScheduleRevision()).isEqualTo(revision);

    LocalDate bufferDate = specialDate.plusWeeks(1);
    manageScheduleExceptions.create(
        actor,
        "admin-special-buffer-" + UUID.randomUUID(),
        bufferDate,
        ScheduleExceptionKind.SPECIAL,
        List.of(new SpecialPeriod(LocalTime.of(9, 0), LocalTime.of(10, 30))),
        "Limite de buffer de teste");
    long bufferRevision = currentScheduleRevision();
    int bufferStatus =
        submitAdministrativeBooking(
            "admin-special-buffer-rejected-" + UUID.randomUUID(),
            bufferDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString(),
            bufferRevision,
            List.of(),
            null,
            "+5511999996104");

    assertThat(bufferStatus).isEqualTo(409);
    assertThat(currentScheduleRevision()).isEqualTo(bufferRevision);
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsAfterAccepted);
    assertThat(count("client", tenantId)).isEqualTo(clientsAfterAccepted);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyAfterAccepted);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(auditAfterAccepted);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(outboxAfterAccepted);
  }

  @Test
  void minimumNoticeCanBeOverriddenInsideSpecialAndReplayDoesNotRepeatEffects() {
    ZonedDateTime now = clock.instant().atZone(ZONE).withSecond(0).withNano(0);
    int minuteRemainder = now.getMinute() % 30;
    ZonedDateTime candidate = now.plusMinutes(minuteRemainder == 0 ? 30 : 30 - minuteRemainder);
    if (candidate.toLocalTime().plusMinutes(45).isAfter(LocalTime.of(23, 45))) {
      candidate = candidate.toLocalDate().plusDays(1).atStartOfDay(ZONE);
    }
    LocalDate specialDate = candidate.toLocalDate();
    manageScheduleExceptions.create(
        actor,
        "admin-special-notice-" + UUID.randomUUID(),
        specialDate,
        ScheduleExceptionKind.SPECIAL,
        List.of(new SpecialPeriod(LocalTime.MIDNIGHT, LocalTime.of(23, 45))),
        "Janela especial de teste");
    long revision = currentScheduleRevision();
    String start = candidate.toOffsetDateTime().toString();
    String key = "admin-special-notice-replay-" + UUID.randomUUID();
    int appointmentsBefore = count("appointment", tenantId);
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_CONFIRMED");
    int outboxBefore = countByEvent("AppointmentConfirmed");

    assertThat(
            submitAdministrativeBooking(
                key,
                start,
                revision,
                List.of("MIN_NOTICE"),
                "Solicitação com antecedência curta",
                "+5511999996105"))
        .isEqualTo(201);
    assertThat(
            submitAdministrativeBooking(
                key,
                start,
                revision,
                List.of("MIN_NOTICE"),
                "Solicitação com antecedência curta",
                "+5511999996105"))
        .isEqualTo(201);

    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore + 1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore + 1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(auditBefore + 1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(outboxBefore + 1);
    assertThat(currentScheduleRevision()).isEqualTo(revision);
  }

  @Test
  void administratorAgendaUsesStableKeysetPagesAndValidatesWindowLimits() throws Exception {
    var slots =
        availability
            .execute(tenantId, serviceId, bookingDate, bookingDate)
            .days()
            .getFirst()
            .slots();
    for (int index = 0; index < 3; index++) {
      var slot = slots.get(index * 2);
      executePrepared(
          "page-booking-" + UUID.randomUUID(),
          serviceId,
          java.time.OffsetDateTime.parse(slot.startsAt().toString()),
          "Página " + index,
          "+551199999" + String.format("%04d", 2000 + index));
    }

    var firstPage =
        mvc.perform(
                get("/api/v1/admin/appointments")
                    .with(authentication(principalToken(actor)))
                    .param("from", bookingDate.toString())
                    .param("to", bookingDate.toString())
                    .param("limit", "1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn();
    String cursor =
        objectMapper
            .readTree(firstPage.getResponse().getContentAsString())
            .get("nextCursor")
            .asText();
    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString())
                .param("limit", "1")
                .param("cursor", cursor))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.hasMore").value(true));

    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.plusDays(366).toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString())
                .param("limit", "101"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString())
                .param("status", "CANCELLED"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void administratorAgendaIncludesOccupancyThatStartsBeforeTheLocalDateWindow() throws Exception {
    UUID appointmentId = seedAppointmentCrossingIntoBookingDate();

    mvc.perform(
            get("/api/v1/admin/appointments")
                .with(authentication(principalToken(actor)))
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString()))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath("$.items[*].appointmentId")
                .value(org.hamcrest.Matchers.hasItem(appointmentId.toString())))
        .andExpect(jsonPath("$.items[0].clientName").value("Continuação"));
  }

  private UUID seedAppointmentCrossingIntoBookingDate() {
    UUID clientId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Continuação', ?)",
        clientId,
        tenantId,
        "+551199999" + String.format("%04d", Math.floorMod(System.nanoTime(), 10_000)));
    var startsAt = bookingDate.minusDays(1).atTime(23, 45).atZone(ZONE).toInstant();
    var serviceEndsAt = startsAt.plusSeconds(30 * 60L);
    var occupiedUntil = serviceEndsAt.plusSeconds(15 * 60L);
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Continuação', 'Consulta', 30, 15, 'CONFIRMED', clock_timestamp(), 1)",
        appointmentId,
        tenantId,
        clientId,
        serviceId,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(serviceEndsAt),
        java.sql.Timestamp.from(occupiedUntil),
        ZONE.getId());
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at) VALUES (?, ?, ?, NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING', 'APPOINTMENT_CREATED', clock_timestamp())",
        UUID.randomUUID(),
        tenantId,
        appointmentId);
    return appointmentId;
  }

  private UUID seedForeignAppointment(LocalDate date) {
    UUID foreignTenant = UUID.randomUUID();
    UUID foreignService = UUID.randomUUID();
    UUID foreignClient = UUID.randomUUID();
    UUID foreignAppointment = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Other tenant', ?, ?, 'ACTIVE')",
        foreignTenant,
        "other-" + UUID.randomUUID().toString().substring(0, 8),
        ZONE.getId());
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status, version) VALUES (?, ?, 'Foreign service', 'foreign service', 30, 0, 'INACTIVE', 1)",
        foreignService,
        foreignTenant);
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Private client', '+5511999999911')",
        foreignClient,
        foreignTenant);
    var startsAt = date.atTime(9, 0).atZone(ZONE).toInstant();
    var endsAt = startsAt.plusSeconds(30 * 60L);
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Private client', 'Foreign service', 30, 0, 'CONFIRMED', clock_timestamp(), 1)",
        foreignAppointment,
        foreignTenant,
        foreignClient,
        foreignService,
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(endsAt),
        java.sql.Timestamp.from(endsAt),
        ZONE.getId());
    migrationJdbc.update(
        "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at) VALUES (?, ?, ?, NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING', 'APPOINTMENT_CREATED', clock_timestamp())",
        UUID.randomUUID(),
        foreignTenant,
        foreignAppointment);
    return foreignAppointment;
  }

  @Test
  void recoveryIsReadOnlyMinimalAndScopedToCapabilityAndTenant() throws Exception {
    String key = "recover-" + UUID.randomUUID();
    String capability = newCapability();
    String body = requestBody(serviceId, requestedStart, "Recovery Person", "+5511999991009");
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
    recoveryCapabilities.put(key, capability);

    double lifetimeSeconds =
        appJdbc.queryForObject(
            "SELECT extract(epoch FROM (expires_at - created_at)) FROM agendamento.public_booking_intent WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?",
            Double.class,
            tenantId,
            "appointments.public.create.v1",
            com.appagendamento.idempotency.application.IdempotencyKey.digest(key));
    assertThat(lifetimeSeconds).isBetween(604_790.0, 604_810.0);

    int appointmentsBefore = count("appointment", tenantId);
    int clientsBefore = count("client", tenantId);
    int historiesBefore = count("appointment_history", tenantId);
    int auditsBefore = count("audit_event", tenantId);
    int eventsBefore = countByEvent("AppointmentConfirmed");
    mvc.perform(
            get("/api/v1/public/" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", capability))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("PREPARED_UNKNOWN"))
        .andExpect(jsonPath("$.receipt").doesNotExist());
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);
    assertThat(count("client", tenantId)).isEqualTo(clientsBefore);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historiesBefore);
    assertThat(count("audit_event", tenantId)).isEqualTo(auditsBefore);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(eventsBefore);

    submitBooking(key, body).andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    mvc.perform(
            get("/api/v1/public/" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", capability))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("COMPLETED"))
        .andExpect(jsonPath("$.receipt.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.receipt.clientName").doesNotExist())
        .andExpect(jsonPath("$.receipt.phone").doesNotExist())
        .andExpect(jsonPath("$.client").doesNotExist());
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);

    mvc.perform(
            get("/api/v1/public/foreign-" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", capability))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/public/" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", newCapability()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/public/" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", "invalid"))
        .andExpect(status().isNotFound());
    migrationJdbc.update(
        "UPDATE agendamento.public_booking_intent SET created_at = clock_timestamp() - interval '8 days', expires_at = clock_timestamp() - interval '1 second' WHERE tenant_id = ? AND command_scope = ? AND key_digest = ?",
        tenantId,
        "appointments.public.create.v1",
        com.appagendamento.idempotency.application.IdempotencyKey.digest(key));
    mvc.perform(
            get("/api/v1/public/" + slug + "/booking-intents/current")
                .header("X-Booking-Recovery", capability))
        .andExpect(status().isNotFound());
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.public.create.v1'",
                Integer.class,
                tenantId))
        .isEqualTo(1);
  }

  @Test
  void recoveryBindingRejectsMissingCsrfTokenSwapAndChangedCommand() throws Exception {
    String key = "bound-" + UUID.randomUUID();
    String capability = newCapability();
    String body = requestBody(serviceId, requestedStart, "Original Person", "+5511999991010");
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/public/" + slug + "/appointments")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", newCapability())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());

    mvc.perform(
            post("/api/v1/public/" + slug + "/appointments")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    requestBody(serviceId, requestedStart, "Different Person", "+5511999991010")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(count("appointment", tenantId)).isZero();
    assertThat(count("client", tenantId)).isZero();
    assertThat(countByEvent("AppointmentConfirmed")).isZero();
  }

  @Test
  void rejectsMissingCsrfForeignServiceAndMissingPublication() throws Exception {
    String body = requestBody(serviceId, requestedStart, "Cliente", "+5511999991234");
    mvc.perform(
            post("/api/v1/public/" + slug + "/appointments")
                .header("Idempotency-Key", "missing-csrf-" + UUID.randomUUID())
                .header("X-Booking-Recovery", newCapability())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    UUID otherTenant = UUID.randomUUID();
    UUID otherService = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Private', ?, 'UTC', 'ACTIVE')",
        otherTenant,
        "private-" + UUID.randomUUID().toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO agendamento.service (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status) VALUES (?, ?, 'Private', 'private', 30, 0, 'ACTIVE')",
        otherService,
        otherTenant);
    String foreignKey = "foreign-service-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", foreignKey)
                .header("X-Booking-Recovery", newCapability())
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(otherService, requestedStart, "Cliente", "+5511999991234")))
        .andExpect(status().isNotFound());
    changePublicationStatus.execute(actor, "unpublish-booking-" + UUID.randomUUID(), false, 2);
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", "unpublished-" + UUID.randomUUID())
                .header("X-Booking-Recovery", newCapability())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNotFound());
    assertThat(count("appointment", tenantId)).isZero();
  }

  @Test
  void concurrentDifferentIntentsForOneSlotConfirmOnlyOne() throws Exception {
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (ExecutorService requests = Executors.newFixedThreadPool(2)) {
      var first =
          requests.submit(
              () -> concurrentCreate(barrier, "race-a-" + UUID.randomUUID(), "+5511999991001"));
      var second =
          requests.submit(
              () -> concurrentCreate(barrier, "race-b-" + UUID.randomUUID(), "+5511999991002"));
      assertThat(first.get()).isNotEqualTo(second.get());
    }
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
  }

  @Test
  void postgresExclusionConstraintMapsToSlotConflict() throws Exception {
    executePrepared(
        "constraint-first-" + UUID.randomUUID(),
        serviceId,
        java.time.OffsetDateTime.parse(requestedStart),
        "Cliente inicial",
        "+5511999991221");
    UUID clientId =
        appJdbc.queryForObject(
            "SELECT client_id FROM agendamento.client WHERE tenant_id = ?", UUID.class, tenantId);
    var start = java.time.OffsetDateTime.parse(requestedStart).toInstant();
    var serviceEnd = start.plusSeconds(30 * 60L);
    var occupiedUntil = serviceEnd.plusSeconds(15 * 60L);
    Appointment duplicate =
        new Appointment(
            UUID.randomUUID(),
            tenantId,
            clientId,
            serviceId,
            start,
            serviceEnd,
            occupiedUntil,
            ZONE.getId(),
            "Cliente concorrente",
            "Consulta",
            30,
            15,
            null,
            null,
            AppointmentStatus.CONFIRMED,
            java.time.Instant.now(),
            1);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                appointmentRepository.insertConfirmed(
                    duplicate,
                    UUID.randomUUID().toString(),
                    "00000000-0000-0000-0000-000000000001"))
        .isInstanceOf(SlotNoLongerAvailableException.class);
    assertThat(count("appointment", tenantId)).isEqualTo(1);
  }

  @Test
  void equivalentPhoneFormatsReuseOneTenantClientWhileSeparateSlotsConfirm() throws Exception {
    var slots =
        availability
            .execute(tenantId, serviceId, bookingDate, bookingDate)
            .days()
            .getFirst()
            .slots();
    var firstStart = java.time.OffsetDateTime.parse(requestedStart);
    var secondStart =
        slots.stream()
            .map(slot -> slot.startsAt())
            .filter(
                start -> start.toInstant().isAfter(firstStart.toInstant().plusSeconds(45 * 60L)))
            .findFirst()
            .orElseThrow();
    CyclicBarrier barrier = new CyclicBarrier(2);
    try (ExecutorService requests = Executors.newFixedThreadPool(2)) {
      var first =
          requests.submit(
              () ->
                  createAt(
                      barrier,
                      "same-phone-a-" + UUID.randomUUID(),
                      firstStart,
                      "Visitante A",
                      "+55 (11) 99999-1234"));
      var second =
          requests.submit(
              () ->
                  createAt(
                      barrier,
                      "same-phone-b-" + UUID.randomUUID(),
                      secondStart,
                      "Visitante B",
                      "+5511999991234"));
      assertThat(first.get()).isNotEqualTo(second.get());
    }
    assertThat(count("client", tenantId)).isEqualTo(1);
    assertThat(count("appointment", tenantId)).isEqualTo(2);
  }

  @Test
  void auditOrOutboxFailureRollsBackClientAppointmentHistoryAndIdempotency() {
    for (String table : List.of("audit_event", "outbox_event")) {
      String trigger = "reject_booking_" + table;
      String key = "rollback-" + table + "-" + UUID.randomUUID();
      migrationJdbc.execute(
          "CREATE FUNCTION agendamento."
              + trigger
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
              + (table.equals("audit_event")
                  ? "NEW.action = 'APPOINTMENT_CONFIRMED'"
                  : "NEW.event_type = 'AppointmentConfirmed'")
              + " THEN RAISE EXCEPTION 'injected booking write failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
      migrationJdbc.execute(
          "CREATE TRIGGER "
              + trigger
              + " BEFORE INSERT ON agendamento."
              + table
              + " FOR EACH ROW EXECUTE FUNCTION agendamento."
              + trigger
              + "()");
      try {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () ->
                    executePrepared(
                        key,
                        serviceId,
                        java.time.OffsetDateTime.parse(requestedStart),
                        "Rollback client",
                        "+5511999991200"))
            .isInstanceOf(RuntimeException.class);
      } finally {
        migrationJdbc.execute("DROP TRIGGER " + trigger + " ON agendamento." + table);
        migrationJdbc.execute("DROP FUNCTION agendamento." + trigger + "()");
      }
      assertThat(count("client", tenantId)).isZero();
      assertThat(count("appointment", tenantId)).isZero();
      assertThat(count("appointment_history", tenantId)).isZero();
      assertThat(countByEvent("AppointmentConfirmed")).isZero();
      assertThat(countByAction("APPOINTMENT_CONFIRMED")).isZero();
      assertThat(
              appJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.public.create.v1'",
                  Integer.class,
                  tenantId))
          .isZero();
    }
  }

  @Test
  void administrativeHistoryAuditAndOutboxFailuresRollBackAllBookingEffects() {
    int auditEventsBefore = count("audit_event", tenantId);
    int outboxEventsBefore = count("outbox_event", tenantId);
    for (String table : List.of("appointment_history", "audit_event", "outbox_event")) {
      String trigger = "reject_admin_booking_" + table;
      String key = "admin-rollback-" + table + "-" + UUID.randomUUID();
      migrationJdbc.execute(
          "CREATE FUNCTION agendamento."
              + trigger
              + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF "
              + switch (table) {
                case "appointment_history" ->
                    "NEW.actor_type = 'ADMIN' AND NEW.reason_code IN ('ADMIN_BOOKING', 'ADMIN_BOOKING_OVERRIDE')";
                case "audit_event" ->
                    "NEW.action = 'APPOINTMENT_CONFIRMED' AND NEW.actor_type = 'ADMIN'";
                default -> "NEW.event_type = 'AppointmentConfirmed'";
              }
              + " THEN RAISE EXCEPTION 'injected administrative booking write failure' USING ERRCODE = '23514'; END IF; RETURN NEW; END $$");
      migrationJdbc.execute(
          "CREATE TRIGGER "
              + trigger
              + " BEFORE INSERT ON agendamento."
              + table
              + " FOR EACH ROW EXECUTE FUNCTION agendamento."
              + trigger
              + "()");
      try {
        var command =
            new CreateAdministrativeAppointmentCommand(
                serviceId,
                java.time.OffsetDateTime.parse(requestedStart),
                null,
                "Admin rollback",
                "+5511999996680",
                List.of(),
                null,
                currentScheduleRevision());
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> createAdministrativeAppointment.execute(actor, key, command))
            .isInstanceOf(RuntimeException.class);
      } finally {
        migrationJdbc.execute("DROP TRIGGER " + trigger + " ON agendamento." + table);
        migrationJdbc.execute("DROP FUNCTION agendamento." + trigger + "()");
      }
      assertThat(count("client", tenantId)).isZero();
      assertThat(count("appointment", tenantId)).isZero();
      assertThat(count("appointment_history", tenantId)).isZero();
      assertThat(count("audit_event", tenantId)).isEqualTo(auditEventsBefore);
      assertThat(count("outbox_event", tenantId)).isEqualTo(outboxEventsBefore);
      assertThat(
              appJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.create.v1' AND key_digest = ?",
                  Integer.class,
                  tenantId,
                  com.appagendamento.idempotency.application.IdempotencyKey.digest(key)))
          .isZero();
    }
  }

  @Test
  void reservationWinningSharedLockMakesConcurrentBlockFail() throws Exception {
    installPauseTrigger("appointment");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var reservation = commands.submit(() -> reserve("reserve-wins-" + UUID.randomUUID()));
      awaitInsertInProgress("appointment");
      var block = commands.submit(() -> createConflictingBlock());
      assertThat(reservation.get()).isTrue();
      assertThat(block.get()).isFalse();
    } finally {
      removePauseTrigger("appointment");
    }
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("blocked_period", tenantId)).isZero();
  }

  @Test
  void blockingWinningSharedLockMakesConcurrentReservationFail() throws Exception {
    installPauseTrigger("blocked_period");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var block = commands.submit(this::createConflictingBlock);
      awaitInsertInProgress("blocked_period");
      var reservation = commands.submit(() -> reserve("block-wins-" + UUID.randomUUID()));
      assertThat(block.get()).isTrue();
      assertThat(
              availability
                  .execute(tenantId, serviceId, bookingDate, bookingDate)
                  .days()
                  .getFirst()
                  .slots()
                  .stream()
                  .map(slot -> slot.startsAt().toString()))
          .doesNotContain(requestedStart);
      assertThat(reservation.get()).isFalse();
    } finally {
      removePauseTrigger("blocked_period");
    }
    assertThat(count("appointment", tenantId)).isZero();
    assertThat(count("blocked_period", tenantId)).isEqualTo(1);
  }

  @Test
  void administrativeReservationAndBlockedPeriodUseTheSharedLockInBothOrders() throws Exception {
    long initialRevision = currentScheduleRevision();
    installPauseTrigger("appointment");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var reservation =
          commands.submit(
              () ->
                  submitAdministrativeBooking(
                      "admin-lock-reservation-first-" + UUID.randomUUID(),
                      requestedStart,
                      initialRevision,
                      List.of(),
                      null,
                      "+5511999997810"));
      awaitInsertInProgress("appointment");
      var block = commands.submit(this::createConflictingBlock);
      assertThat(reservation.get()).isEqualTo(201);
      assertThat(block.get()).isFalse();
    } finally {
      removePauseTrigger("appointment");
    }
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("blocked_period", tenantId)).isZero();
  }

  @Test
  void blockedPeriodWinsSharedLockBeforeAdministrativeReservation() throws Exception {
    long revisionBeforeBlock = currentScheduleRevision();
    installPauseTrigger("blocked_period");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var block = commands.submit(this::createConflictingBlock);
      awaitInsertInProgress("blocked_period");
      var reservation =
          commands.submit(
              () ->
                  submitAdministrativeBooking(
                      "admin-lock-block-first-" + UUID.randomUUID(),
                      requestedStart,
                      revisionBeforeBlock,
                      List.of(),
                      null,
                      "+5511999997811"));
      assertThat(block.get()).isTrue();
      assertThat(reservation.get()).isEqualTo(409);
    } finally {
      removePauseTrigger("blocked_period");
    }
    assertThat(count("appointment", tenantId)).isZero();
    assertThat(count("blocked_period", tenantId)).isEqualTo(1);
  }

  @Test
  void administrativeReservationWinningSharedLockRejectsConcurrentPublicBooking() throws Exception {
    long revision = currentScheduleRevision();
    installPauseTrigger("appointment");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var administrative =
          commands.submit(
              () ->
                  submitAdministrativeBooking(
                      "admin-wins-public-race-" + UUID.randomUUID(),
                      requestedStart,
                      revision,
                      List.of(),
                      null,
                      "+5511999997812"));
      awaitInsertInProgress("appointment");
      var publicBooking =
          commands.submit(() -> reserve("public-loses-admin-race-" + UUID.randomUUID()));
      assertThat(administrative.get()).isEqualTo(201);
      assertThat(publicBooking.get()).isFalse();
    } finally {
      removePauseTrigger("appointment");
    }
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
  }

  @Test
  void publicBookingWinningSharedLockRejectsConcurrentAdministrativeReservation() throws Exception {
    long revision = currentScheduleRevision();
    installPauseTrigger("appointment");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var publicBooking =
          commands.submit(() -> reserve("public-wins-admin-race-" + UUID.randomUUID()));
      awaitInsertInProgress("appointment");
      var administrative =
          commands.submit(
              () ->
                  submitAdministrativeBooking(
                      "admin-loses-public-race-" + UUID.randomUUID(),
                      requestedStart,
                      revision,
                      List.of(),
                      null,
                      "+5511999997813"));
      assertThat(publicBooking.get()).isTrue();
      assertThat(administrative.get()).isEqualTo(409);
    } finally {
      removePauseTrigger("appointment");
    }
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CONFIRMED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
  }

  private long currentScheduleRevision() {
    return appJdbc.queryForObject(
        "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
        Long.class,
        tenantId);
  }

  @Test
  void reschedulingUpdatesSameAppointmentAtomicallyAndReplayDoesNotDuplicateEffects()
      throws Exception {
    submitBooking(
            "reschedule-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Snapshot original", "+5511999991801"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    String destination =
        bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString();
    long revisionBefore = currentScheduleRevision();
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    String key = "admin-reschedule-" + UUID.randomUUID();
    String body = rescheduleBody(1, revisionBefore, destination);

    submitReschedule(appointmentId, key, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.timeZone").value(ZONE.getId()))
        .andExpect(header().string("Idempotency-Replayed", "false"));
    submitReschedule(appointmentId, key, body)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(
            appJdbc.queryForObject(
                "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
                UUID.class,
                tenantId))
        .isEqualTo(appointmentId);
    assertThat(
            appJdbc.queryForObject(
                "SELECT client_name_snapshot || ':' || service_name_snapshot || ':' || service_duration_minutes || ':' || service_buffer_after_minutes || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Snapshot original:Consulta:30:15:2");
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore + 1);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore + 1);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore + 1);
    assertThat(currentScheduleRevision()).isEqualTo(revisionBefore + 1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT previous_starts_at = ?::timestamptz AND new_starts_at = ?::timestamptz AND version_before = 1 AND version_after = 2 FROM agendamento.appointment_history WHERE tenant_id = ? AND appointment_id = ? AND reason_code = 'APPOINTMENT_RESCHEDULED'",
                Boolean.class,
                java.time.OffsetDateTime.parse(requestedStart),
                java.time.OffsetDateTime.parse(destination),
                tenantId,
                appointmentId))
        .isTrue();

    var destinationSlots =
        availability.execute(
            tenantId, serviceId, bookingDate.plusWeeks(1), bookingDate.plusWeeks(1));
    assertThat(destinationSlots.days().getFirst().slots())
        .noneMatch(
            slot ->
                slot.startsAt()
                    .toInstant()
                    .equals(java.time.OffsetDateTime.parse(destination).toInstant()));
    var originalSlots = availability.execute(tenantId, serviceId, bookingDate, bookingDate);
    assertThat(originalSlots.days().getFirst().slots())
        .anyMatch(
            slot ->
                slot.startsAt()
                    .toInstant()
                    .equals(java.time.OffsetDateTime.parse(requestedStart).toInstant()));
  }

  @Test
  void reschedulingMayOverlapItsOwnOldIntervalAndConcurrentStaleVersionLoses() throws Exception {
    submitBooking(
            "reschedule-own-overlap-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Overlap próprio", "+5511999991811"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    long revision = currentScheduleRevision();
    String firstDestination =
        bookingDate.plusWeeks(1).atTime(9, 30).atZone(ZONE).toOffsetDateTime().toString();
    installRescheduleUpdatePause();
    try (ExecutorService requests = Executors.newFixedThreadPool(2)) {
      var winner =
          requests.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-concurrent-first-" + UUID.randomUUID(),
                          rescheduleBody(1, revision, firstDestination))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitUpdateInProgress();
      var stale =
          requests.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-concurrent-second-" + UUID.randomUUID(),
                          rescheduleBody(
                              1,
                              revision,
                              bookingDate
                                  .plusWeeks(1)
                                  .atTime(10, 0)
                                  .atZone(ZONE)
                                  .toOffsetDateTime()
                                  .toString()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(winner.get()).isEqualTo(200);
      assertThat(stale.get()).isEqualTo(409);
    } finally {
      removeRescheduleUpdatePause();
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(firstDestination));
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.appointment WHERE appointment_id = ?",
                Long.class,
                appointmentId))
        .isEqualTo(2L);
    assertThat(count("appointment_history", tenantId)).isEqualTo(2);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(1);
    assertThat(currentScheduleRevision()).isEqualTo(revision + 1);
  }

  @Test
  void blockedDestinationRejectsRescheduleWithoutChangingOriginal() throws Exception {
    submitBooking(
            "reschedule-blocked-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Bloqueio", "+5511999991812"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    var destination = bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime();
    manageBlockedPeriods.create(
        actor,
        "reschedule-block-target-" + UUID.randomUUID(),
        destination.toString(),
        destination.plusHours(1).toString(),
        "Bloqueio concorrente de agenda");
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    long revisionBefore = currentScheduleRevision();
    submitReschedule(
            appointmentId,
            "reschedule-blocked-target-" + UUID.randomUUID(),
            rescheduleBody(1, revisionBefore, destination.toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(requestedStart));
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore);
    assertThat(currentScheduleRevision()).isEqualTo(revisionBefore);
  }

  @Test
  void staleScheduleRevisionIsDistinctFromAppointmentVersionConflict() throws Exception {
    submitBooking(
            "reschedule-stale-revision-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Revisão", "+5511999991813"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    long staleRevision = currentScheduleRevision();
    var unrelatedBlock = bookingDate.plusWeeks(8).atTime(9, 0).atZone(ZONE).toOffsetDateTime();
    manageBlockedPeriods.create(
        actor,
        "reschedule-revision-change-" + UUID.randomUUID(),
        unrelatedBlock.toString(),
        unrelatedBlock.plusHours(1).toString(),
        "Alteração de revisão de agenda");
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    submitReschedule(
            appointmentId,
            "reschedule-stale-revision-key-" + UUID.randomUUID(),
            rescheduleBody(
                1,
                staleRevision,
                bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(requestedStart));
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore);
  }

  @Test
  void rescheduleWinningSharedTenantLockRejectsConcurrentDestinationBlock() throws Exception {
    submitBooking(
            "reschedule-lock-first-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Lock remarca primeiro", "+5511999991814"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    long revision = currentScheduleRevision();
    var destination = bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime();
    installRescheduleUpdatePause();
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var reschedule =
          commands.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-lock-winner-" + UUID.randomUUID(),
                          rescheduleBody(1, revision, destination.toString()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      awaitUpdateInProgress();
      var block = commands.submit(() -> createBlockAt(destination));
      assertThat(reschedule.get()).isEqualTo(200);
      assertThat(block.get()).isFalse();
    } finally {
      removeRescheduleUpdatePause();
    }
    assertThat(count("blocked_period", tenantId)).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE appointment_id = ?",
                java.time.OffsetDateTime.class,
                appointmentId))
        .isEqualTo(destination);
  }

  @Test
  void destinationBlockWinningSharedTenantLockRejectsConcurrentReschedule() throws Exception {
    submitBooking(
            "reschedule-block-lock-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Lock bloqueio primeiro", "+5511999991815"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    long revision = currentScheduleRevision();
    var destination = bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime();
    installPauseTrigger("blocked_period");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var block = commands.submit(() -> createBlockAt(destination));
      awaitInsertInProgress("blocked_period");
      var reschedule =
          commands.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-block-lock-loser-" + UUID.randomUUID(),
                          rescheduleBody(1, revision + 1, destination.toString()))
                      .andReturn()
                      .getResponse()
                      .getStatus());
      assertThat(block.get()).isTrue();
      assertThat(reschedule.get()).isEqualTo(409);
    } finally {
      removePauseTrigger("blocked_period");
    }
    assertThat(count("blocked_period", tenantId)).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(requestedStart));
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isZero();
    assertThat(countByEvent("AppointmentRescheduled")).isZero();
  }

  @Test
  void conflictingReschedulePreservesOriginalIntervalAndCreatesNoSuccessEffects() throws Exception {
    submitBooking(
            "reschedule-conflict-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Original", "+5511999991802"))
        .andExpect(status().isCreated());
    java.time.OffsetDateTime occupiedSlot =
        bookingDate.atTime(10, 30).atZone(ZONE).toOffsetDateTime();
    submitBooking(
            "reschedule-conflict-target-" + UUID.randomUUID(),
            requestBody(serviceId, occupiedSlot.toString(), "Other", "+5511999991803"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ? AND client_id = (SELECT client_id FROM agendamento.client WHERE tenant_id = ? AND normalized_phone = '+5511999991802')",
            UUID.class,
            tenantId,
            tenantId);
    long revisionBefore = currentScheduleRevision();
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    submitReschedule(
            appointmentId,
            "admin-reschedule-conflict-" + UUID.randomUUID(),
            rescheduleBody(1, revisionBefore, occupiedSlot.toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("SLOT_NO_LONGER_AVAILABLE"));

    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(requestedStart));
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                Long.class,
                tenantId,
                appointmentId))
        .isEqualTo(1L);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore);
    assertThat(currentScheduleRevision()).isEqualTo(revisionBefore);
  }

  @Test
  void auditFailureRollsBackRescheduledIntervalVersionHistoryOutboxAndRevision() throws Exception {
    submitBooking(
            "reschedule-rollback-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Rollback", "+5511999991804"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    long revisionBefore = currentScheduleRevision();
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    int receiptsBefore =
        appJdbc.queryForObject(
            "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.reschedule.v1'",
            Integer.class,
            tenantId);
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.fail_reschedule_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'APPOINTMENT_RESCHEDULED' THEN RAISE EXCEPTION 'test audit failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER fail_reschedule_audit BEFORE INSERT ON agendamento.audit_event FOR EACH ROW EXECUTE FUNCTION agendamento.fail_reschedule_audit()");
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  submitReschedule(
                      appointmentId,
                      "admin-reschedule-rollback-" + UUID.randomUUID(),
                      rescheduleBody(
                          1,
                          revisionBefore,
                          bookingDate
                              .plusWeeks(1)
                              .atTime(9, 0)
                              .atZone(ZONE)
                              .toOffsetDateTime()
                              .toString())))
          .isInstanceOf(jakarta.servlet.ServletException.class);
    } finally {
      migrationJdbc.execute("DROP TRIGGER fail_reschedule_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.fail_reschedule_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                java.time.OffsetDateTime.class,
                tenantId,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(requestedStart));
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.appointment WHERE appointment_id = ?",
                Long.class,
                appointmentId))
        .isEqualTo(1L);
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.reschedule.v1'",
                Integer.class,
                tenantId))
        .isEqualTo(receiptsBefore);
    assertThat(currentScheduleRevision()).isEqualTo(revisionBefore);
  }

  private ResultActions submitReschedule(UUID id, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/v1/admin/appointments/{id}/reschedule", id)
            .with(authentication(principalToken(actor)))
            .with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private static String rescheduleBody(
      long expectedVersion, long scheduleRevision, String startsAt) {
    return "{\"expectedVersion\":"
        + expectedVersion
        + ",\"expectedScheduleRevision\":"
        + scheduleRevision
        + ",\"startsAt\":\""
        + startsAt
        + "\"}";
  }

  private int submitAdministrativeBooking(
      String key,
      String startsAt,
      long revision,
      List<String> overrides,
      String reason,
      String phone) {
    String policies =
        overrides.stream()
            .map(value -> "\"" + value + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
    String body =
        "{\"serviceId\":\""
            + serviceId
            + "\",\"startsAt\":\""
            + startsAt
            + "\",\"client\":{\"name\":\"Admin race\",\"phone\":\""
            + phone
            + "\"},\"overridePolicies\":["
            + policies
            + "]"
            + reasonJson
            + ",\"expectedScheduleVersion\":"
            + revision
            + "}";
    try {
      return mvc.perform(
              post("/api/v1/admin/appointments")
                  .with(authentication(principalToken(actor)))
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andReturn()
          .getResponse()
          .getStatus();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private boolean concurrentCreate(CyclicBarrier barrier, String key, String phone) {
    try {
      barrier.await();
      executePrepared(
          key,
          serviceId,
          java.time.OffsetDateTime.parse(requestedStart),
          "Cliente concorrente",
          phone);
      return true;
    } catch (SlotNoLongerAvailableException conflict) {
      return false;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private boolean reserve(String key) {
    try {
      executePrepared(
          key,
          serviceId,
          java.time.OffsetDateTime.parse(requestedStart),
          "Cliente concorrente",
          "+5511999991201");
      return true;
    } catch (SlotNoLongerAvailableException conflict) {
      return false;
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID createAt(
      CyclicBarrier barrier,
      String key,
      java.time.OffsetDateTime startsAt,
      String name,
      String phone) {
    try {
      barrier.await();
      return executePrepared(key, serviceId, startsAt, name, phone).value().appointmentId();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private ResultActions submitBooking(String key, String body) throws Exception {
    String recoveryCapability = recoveryCapabilities.get(key);
    if (recoveryCapability == null) {
      recoveryCapability = newCapability();
      mvc.perform(
              post("/api/v1/public/" + slug + "/booking-intents/current")
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .header("X-Booking-Recovery", recoveryCapability)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isNoContent());
      recoveryCapabilities.put(key, recoveryCapability);
    }
    return mvc.perform(
        post("/api/v1/public/" + slug + "/appointments")
            .with(csrf())
            .header("Idempotency-Key", key)
            .header("X-Booking-Recovery", recoveryCapability)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private IdempotentCommandResult<PublicAppointmentReceipt> executePrepared(
      String key,
      UUID commandServiceId,
      java.time.OffsetDateTime startsAt,
      String name,
      String phone)
      throws Exception {
    String body = requestBody(commandServiceId, startsAt.toString(), name, phone);
    String recoveryCapability = newCapability();
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", recoveryCapability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
    return createAppointment.execute(
        slug, key, commandServiceId, startsAt, name, phone, recoveryCapability);
  }

  private static String newCapability() {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
  }

  private boolean createConflictingBlock() {
    var start = java.time.OffsetDateTime.parse(requestedStart);
    return createBlockAt(start);
  }

  private boolean createBlockAt(java.time.OffsetDateTime start) {
    try {
      manageBlockedPeriods.create(
          actor,
          "block-race-" + UUID.randomUUID(),
          start.toString(),
          start.plusHours(1).toString(),
          "Manutenção concorrente");
      return true;
    } catch (ConfirmedAppointmentConflictException conflict) {
      return false;
    }
  }

  private void installPauseTrigger(String table) {
    String function = "pause_agenda_insert_" + table;
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento."
            + function
            + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.75); RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER "
            + function
            + " BEFORE INSERT ON agendamento."
            + table
            + " FOR EACH ROW EXECUTE FUNCTION agendamento."
            + function
            + "()");
  }

  private void installRescheduleUpdatePause() {
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.pause_reschedule_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.75); RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER pause_reschedule_update BEFORE UPDATE ON agendamento.appointment FOR EACH ROW EXECUTE FUNCTION agendamento.pause_reschedule_update()");
  }

  private void removeRescheduleUpdatePause() {
    migrationJdbc.execute("DROP TRIGGER pause_reschedule_update ON agendamento.appointment");
    migrationJdbc.execute("DROP FUNCTION agendamento.pause_reschedule_update()");
  }

  private void awaitUpdateInProgress() throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      Integer active =
          migrationJdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query ILIKE '%UPDATE agendamento.appointment%'",
              Integer.class);
      if (active != null && active > 0) return;
      Thread.sleep(20);
    }
    throw new IllegalStateException("A remarcação não chegou ao UPDATE sob lock de tenant");
  }

  private void removePauseTrigger(String table) {
    String function = "pause_agenda_insert_" + table;
    migrationJdbc.execute("DROP TRIGGER " + function + " ON agendamento." + table);
    migrationJdbc.execute("DROP FUNCTION agendamento." + function + "()");
  }

  private void awaitInsertInProgress(String table) throws InterruptedException {
    String query = "INSERT INTO agendamento." + table;
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      Integer active =
          migrationJdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query ILIKE ?",
              Integer.class,
              "%" + query + "%");
      if (active != null && active > 0) return;
      Thread.sleep(20);
    }
    throw new IllegalStateException("A operação não chegou à escrita sob lock de tenant");
  }

  private int count(String table, UUID tenant) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenant);
  }

  private int countByEvent(String event) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = ?",
        Integer.class,
        tenantId,
        event);
  }

  private int countByAction(String action) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = ?",
        Integer.class,
        tenantId,
        action);
  }

  private static String requestBody(
      UUID requestedService, String start, String name, String phone) {
    return "{\"serviceId\":\""
        + requestedService
        + "\",\"startsAt\":\""
        + start
        + "\",\"client\":{\"name\":\""
        + name
        + "\",\"phone\":\""
        + phone
        + "\"}}";
  }

  private static UsernamePasswordAuthenticationToken principalToken(AuthenticatedAdmin admin) {
    return UsernamePasswordAuthenticationToken.authenticated(
        admin, "N/A", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
  }

  private record TestAdmin(UUID adminUserId, String subject, UUID tenantId)
      implements AuthenticatedAdmin,
          org.springframework.security.core.AuthenticatedPrincipal,
          java.io.Serializable {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String issuer() {
      return ISSUER;
    }

    @Override
    public String role() {
      return "TENANT_ADMIN";
    }
  }
}
