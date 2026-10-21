package com.appagendamento.appointments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.appointments.application.AppointmentRepository;
import com.appagendamento.appointments.application.CorrectAdministrativeNoShow;
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
import com.appagendamento.clientaccess.api.AccessCodeSender;
import com.appagendamento.clients.api.ClientNoShowRestrictionActivation;
import com.appagendamento.clients.application.ReplaceClientNoShowPolicy;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.PublicationEvidence;
import com.appagendamento.publicpage.application.UpdatePublicContact;
import com.appagendamento.subscriptions.api.PublicSubscriptionEligibility;
import com.appagendamento.subscriptions.application.SubscriptionRepository;
import com.appagendamento.subscriptions.application.SubscriptionTrialLifecycle;
import com.appagendamento.support.PostgresTestEnvironment;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
      "identity.oidc.redirect-uri={baseUrl}/login/oauth2/code/keycloak",
      "app.subscriptions.trial-expiry-worker.enabled=false"
    })
@AutoConfigureMockMvc
class PublicAppointmentIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

  @MockitoSpyBean private JdbcTemplate appJdbc;
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
  @Autowired private CorrectAdministrativeNoShow correctAdministrativeNoShow;
  @Autowired private ReplaceClientNoShowPolicy replaceClientNoShowPolicy;
  @Autowired private ClientNoShowRestrictionActivation noShowRestrictionActivation;
  @Autowired private AppointmentRepository appointmentRepository;
  @Autowired private SubscriptionRepository subscriptionRepository;
  @Autowired private TenantAgendaLock tenantAgendaLock;
  @Autowired private PlatformTransactionManager transactionManager;
  @Autowired private UpdateService updateService;
  @Autowired private SetServiceStatus setServiceStatus;
  @Autowired private MockMvc mvc;
  @Autowired private ObjectMapper objectMapper;
  @MockitoBean private java.time.Clock clock;
  @MockitoBean private PublicationEvidence publicationEvidence;
  @MockitoBean private PublicSubscriptionEligibility subscriptionEligibility;
  @MockitoBean private AccessCodeSender accessCodeSender;

  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private UUID serviceId;
  private AuthenticatedAdmin actor;
  private String slug;
  private LocalDate bookingDate;
  private String requestedStart;
  private final Map<String, String> recoveryCapabilities = new ConcurrentHashMap<>();
  private final Map<UUID, String> clientAccessCodes = new ConcurrentHashMap<>();

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void preparePublishedTenant() throws Exception {
    clientAccessCodes.clear();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              clientAccessCodes.put(invocation.getArgument(1), invocation.getArgument(3));
              return AccessCodeSender.DeliveryResult.ACCEPTED;
            })
        .when(accessCodeSender)
        .send(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.when(clock.instant()).thenReturn(Instant.now());
    org.mockito.Mockito.when(
            publicationEvidence.unavailableEvidence(org.mockito.ArgumentMatchers.any()))
        .thenReturn(List.of());
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    org.mockito.Mockito.when(subscriptionEligibility.allowsNewPublicAppointments(tenantId))
        .thenReturn(true);
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
  void confirmedBookingReplayReturnsOriginalReceiptAfterTrialExpiry() throws Exception {
    Instant bookingBeforeExpiry = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant trialEnd = bookingBeforeExpiry.plusSeconds(60);
    UUID subscriptionId = UUID.randomUUID();
    UUID grantOperationId = UUID.randomUUID();
    org.mockito.Mockito.when(clock.instant()).thenReturn(bookingBeforeExpiry);
    migrationJdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version) VALUES (?, ?, 'TRIALING', ?, ?, 1)",
        subscriptionId,
        tenantId,
        java.sql.Timestamp.from(trialEnd.minusSeconds(14 * 24 * 60 * 60L)),
        java.sql.Timestamp.from(trialEnd));
    migrationJdbc.update(
        "INSERT INTO agendamento.subscription_history (event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'TRIAL_GRANTED', NULL, 'TRIALING', ?, 1, ?)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        java.sql.Timestamp.from(trialEnd.minusSeconds(14 * 24 * 60 * 60L)),
        grantOperationId);

    String key = "trial-expiry-replay-" + UUID.randomUUID();
    String body =
        requestBody(serviceId, requestedStart, "Reserva antes do vencimento", "+5511999991244");
    MvcResult created = submitBooking(key, body).andExpect(status().isCreated()).andReturn();
    UUID appointmentId =
        UUID.fromString(
            objectMapper
                .readTree(created.getResponse().getContentAsString())
                .get("appointmentId")
                .asString());
    org.mockito.Mockito.when(clock.instant()).thenReturn(trialEnd);
    migrationJdbc.update(
        "UPDATE agendamento.subscription SET status = 'SUSPENDED', version = 2 WHERE tenant_id = ? AND subscription_id = ?",
        tenantId,
        subscriptionId);
    org.mockito.Mockito.when(subscriptionEligibility.allowsNewPublicAppointments(tenantId))
        .thenReturn(false);

    submitBooking(key, body)
        .andExpect(status().isCreated())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()));
    assertThat(count("appointment", tenantId)).isEqualTo(1);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByEvent("AppointmentConfirmed")).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.subscription WHERE subscription_id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("SUSPENDED:2");
  }

  @Test
  void publicBookingAndTrialExpirySerializeAtTheTenantLockAndUsePostLockClock() throws Exception {
    Instant bookingBeforeExpiry = Instant.now().truncatedTo(ChronoUnit.MICROS);
    Instant trialEnd = bookingBeforeExpiry.plusSeconds(60);
    AtomicReference<Instant> controlledNow = new AtomicReference<>(bookingBeforeExpiry);
    org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation -> controlledNow.get());
    UUID subscriptionId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version) VALUES (?, ?, 'TRIALING', ?, ?, 1)",
        subscriptionId,
        tenantId,
        java.sql.Timestamp.from(trialEnd.minusSeconds(14 * 24 * 60 * 60L)),
        java.sql.Timestamp.from(trialEnd));
    migrationJdbc.update(
        "INSERT INTO agendamento.subscription_history (event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'TRIAL_GRANTED', NULL, 'TRIALING', ?, 1, ?)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        java.sql.Timestamp.from(trialEnd.minusSeconds(14 * 24 * 60 * 60L)),
        UUID.randomUUID());

    var bookingEnteredGate = new CountDownLatch(1);
    var releaseBookingGate = new CountDownLatch(1);
    var bookingEligibility = new AtomicReference<Boolean>();
    var eligibilityNow = new AtomicReference<Instant>();
    var eligibilityTrialEnd = new AtomicReference<Instant>();
    var eligibilityStatus = new AtomicReference<String>();
    SubscriptionTrialLifecycle lifecycle =
        new SubscriptionTrialLifecycle(subscriptionRepository, tenantAgendaLock, clock);
    org.mockito.Mockito.when(subscriptionEligibility.allowsNewPublicAppointments(tenantId))
        .thenAnswer(
            invocation -> {
              bookingEnteredGate.countDown();
              if (!releaseBookingGate.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release public gate");
              }
              eligibilityNow.set(clock.instant());
              var current = subscriptionRepository.findCurrent(tenantId).orElseThrow();
              eligibilityTrialEnd.set(current.trialEndsAt());
              eligibilityStatus.set(current.status());
              boolean allowed = lifecycle.allowsNewPublicAppointments(tenantId);
              bookingEligibility.set(allowed);
              return allowed;
            });

    String key = "trial-expiry-race-" + UUID.randomUUID();
    String body = requestBody(serviceId, requestedStart, "Corrida do trial", "+5511999991245");
    String capability = newCapability();
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
    recoveryCapabilities.put(key, capability);

    try (ExecutorService workers = Executors.newFixedThreadPool(2);
        var lockConnection = PostgresTestEnvironment.migrationDataSource().getConnection()) {
      Future<MvcResult> pendingBooking =
          workers.submit(
              () ->
                  mvc.perform(
                          post("/api/v1/public/" + slug + "/appointments")
                              .with(csrf())
                              .header("Idempotency-Key", key)
                              .header("X-Booking-Recovery", capability)
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(body))
                      .andReturn());
      assertThat(bookingEnteredGate.await(10, TimeUnit.SECONDS))
          .as("public booking acquired the tenant lock before eligibility check")
          .isTrue();
      controlledNow.set(trialEnd);
      var expiryAttempting = new CountDownLatch(1);
      Future<Boolean> pendingExpiry =
          workers.submit(
              () -> {
                expiryAttempting.countDown();
                return new TransactionTemplate(transactionManager)
                    .execute(status -> lifecycle.materializeExpired(tenantId));
              });
      assertThat(expiryAttempting.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean expiryWaitingOnTenant = false;
      while (System.nanoTime() < deadline) {
        Boolean waiting =
            migrationJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%SELECT tenant_id FROM agendamento.tenant%' AND query ILIKE '%FOR UPDATE%')",
                Boolean.class);
        if (Boolean.TRUE.equals(waiting)) {
          expiryWaitingOnTenant = true;
          break;
        }
        Thread.yield();
      }
      assertThat(expiryWaitingOnTenant)
          .as("expiry worker waits on the booking's tenant row lock")
          .isTrue();

      releaseBookingGate.countDown();
      int bookingStatus = pendingBooking.get(10, TimeUnit.SECONDS).getResponse().getStatus();
      assertThat(eligibilityNow.get()).isEqualTo(trialEnd);
      assertThat(eligibilityTrialEnd.get()).isEqualTo(trialEnd);
      assertThat(eligibilityStatus.get()).isEqualTo("TRIALING");
      assertThat(bookingEligibility.get())
          .as("eligibility is evaluated after the clock reaches trialEndsAt")
          .isFalse();
      assertThat(bookingStatus).isEqualTo(404);
      assertThat(pendingExpiry.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      releaseBookingGate.countDown();
    }
    assertThat(count("appointment", tenantId)).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.subscription WHERE subscription_id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("SUSPENDED:2");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.subscription_history WHERE subscription_id = ?",
                Integer.class,
                subscriptionId))
        .isEqualTo(2);
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
  void administratorCancellationIsAtomicAndAuthorizedReplayKeepsOriginalReceipt() throws Exception {
    submitBooking(
            "outcome-booking-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Cancelamento", "+5511999994321"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    String key = "admin-cancel-" + UUID.randomUUID();
    String body = "{\"expectedVersion\":1,\"reason\":\"Solicitado pelo estabelecimento\"}";
    int auditBefore = countByAction("APPOINTMENT_CANCELLED_BY_PROVIDER");
    int outboxBefore = countByEvent("AppointmentCancelled");

    mvc.perform(
            post("/api/v1/admin/appointments/{id}/cancel", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_PROVIDER"))
        .andExpect(jsonPath("$.version").value(2));

    mvc.perform(
            post("/api/v1/admin/appointments/{id}/cancel", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CANCELLED_BY_PROVIDER:2");
    assertThat(count("appointment_history", tenantId)).isEqualTo(2);
    assertThat(countByAction("APPOINTMENT_CANCELLED_BY_PROVIDER")).isEqualTo(auditBefore + 1);
    assertThat(countByEvent("AppointmentCancelled")).isEqualTo(outboxBefore + 1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT actor_type || ':' || actor_ref || ':' || reason FROM agendamento.appointment_history WHERE appointment_id = ? AND to_status = 'CANCELLED_BY_PROVIDER'",
                String.class,
                appointmentId))
        .isEqualTo("ADMIN:TENANT_ADMIN:Solicitado pelo estabelecimento");

    mvc.perform(
            get("/api/v1/public/" + slug + "/availability")
                .param("serviceId", serviceId.toString())
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].slots.length()").value(5));

    mvc.perform(
            post("/api/v1/admin/appointments/{id}/complete", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "another-command-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    assertThat(count("appointment_history", tenantId)).isEqualTo(2);
  }

  @Test
  void completeAndNoShowUseSeparateCommandsAndOnlyReleaseOccupancyAfterCommit() throws Exception {
    submitBooking(
            "complete-booking-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Conclusão", "+5511999994322"))
        .andExpect(status().isCreated());
    UUID completeId = appointmentIdForTenant();
    setAppointmentInPast(completeId);
    String completeKey = "admin-complete-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/complete", completeId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", completeKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(countByAction("APPOINTMENT_COMPLETED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentCompleted")).isEqualTo(1);

    String nextStart = bookingDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString();
    submitBooking(
            "no-show-booking-" + UUID.randomUUID(),
            requestBody(serviceId, nextStart, "Falta", "+5511999994323"))
        .andExpect(status().isCreated());
    UUID noShowId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ? AND status = 'CONFIRMED'",
            UUID.class,
            tenantId);
    setAppointmentInPast(noShowId);
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/mark-no-show", noShowId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "admin-no-show-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_SHOW"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(countByAction("APPOINTMENT_NO_SHOW_RECORDED")).isEqualTo(1);
    assertThat(countByEvent("ClientNoShowRecorded")).isEqualTo(1);
  }

  @Test
  void noShowActivationBlocksNewPublicBookingButPreservesConfirmedReplay() throws Exception {
    org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation -> Instant.now());
    String phone = "+5511999994358";
    String firstKey = "restriction-original-booking-" + UUID.randomUUID();
    String firstBody = requestBody(serviceId, requestedStart, "Cliente restrito", phone);
    submitBooking(firstKey, firstBody).andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    UUID clientId = clientId(appointmentId);
    replaceClientNoShowPolicy.execute(actor, "restriction-policy-" + UUID.randomUUID(), 0, 1);
    setAppointmentInPast(appointmentId);
    postOutcome(appointmentId, "mark-no-show", "restriction-no-show-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());

    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);
    assertThat(countByAction("CLIENT_SELF_BOOKING_BLOCKED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingBlocked")).isEqualTo(1);

    int appointmentsBefore = count("appointment", tenantId);
    var attemptedStart = bookingDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime();
    String blockedBody = requestBody(serviceId, attemptedStart.toString(), "Outro nome", phone);
    var blocked =
        submitBooking("restriction-blocked-booking-" + UUID.randomUUID(), blockedBody)
            .andExpect(status().isNotFound())
            .andReturn();
    String blockedResponse = blocked.getResponse().getContentAsString();
    assertThat(blockedResponse).doesNotContain("NO_SHOW", "falta", restrictionId.toString());
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);

    submitBooking(firstKey, firstBody).andExpect(status().isCreated());
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE restriction_id = ?",
                String.class,
                restrictionId))
        .isEqualTo("ACTIVE");
  }

  @Test
  void concurrentActivationOfSameNoShowFactCreatesOneRestrictionEffectSet() throws Exception {
    UUID clientId = seedNoShowClient("Concurrent activation");
    Instant asOf = Instant.now();
    NoShowFactRef fact = seedNoShowFacts(clientId, asOf.minusSeconds(90), 1).getFirst();
    replaceClientNoShowPolicy.execute(
        actor, "concurrent-activation-policy-" + UUID.randomUUID(), 0, 1);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    Runnable activate =
        () -> {
          ready.countDown();
          try {
            if (!start.await(5, TimeUnit.SECONDS)) {
              throw new IllegalStateException("Activation workers did not start together");
            }
            noShowRestrictionActivation.evaluateAfterNoShow(
                tenantId, clientId, fact.appointmentId(), asOf);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
          }
        };
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<?> first = workers.submit(activate);
      Future<?> second = workers.submit(activate);
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }
    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                Integer.class,
                tenantId,
                clientId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction_history WHERE tenant_id = ? AND restriction_id = ?",
                Integer.class,
                tenantId,
                restrictionId))
        .isEqualTo(1);
    assertThat(countByAction("CLIENT_SELF_BOOKING_BLOCKED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingBlocked")).isEqualTo(1);
  }

  @Test
  void restrictionActivationFailureRollsBackNoShowAndAllSuccessEffects() throws Exception {
    submitBooking(
            "restriction-activation-rollback-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Rollback restrição", "+5511999994359"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    UUID clientId = clientId(appointmentId);
    replaceClientNoShowPolicy.execute(
        actor, "restriction-rollback-policy-" + UUID.randomUUID(), 0, 1);
    setAppointmentInPast(appointmentId);
    org.mockito.Mockito.when(clock.instant()).thenAnswer(invocation -> Instant.now());
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.fail_restriction_history() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type = 'ACTIVATED' THEN RAISE EXCEPTION 'injected restriction history failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER fail_restriction_history BEFORE INSERT ON agendamento.client_booking_restriction_history FOR EACH ROW EXECUTE FUNCTION agendamento.fail_restriction_history()");
    String key = "restriction-activation-rollback-mark-" + UUID.randomUUID();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> postOutcome(appointmentId, "mark-no-show", key, 1))
          .isInstanceOf(Exception.class);
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER fail_restriction_history ON agendamento.client_booking_restriction_history");
      migrationJdbc.execute("DROP FUNCTION agendamento.fail_restriction_history()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                String.class,
                tenantId,
                appointmentId))
        .isEqualTo("CONFIRMED:1");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment_history WHERE tenant_id = ? AND appointment_id = ?",
                Integer.class,
                tenantId,
                appointmentId))
        .isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_NO_SHOW_RECORDED")).isZero();
    assertThat(countByAction("CLIENT_SELF_BOOKING_BLOCKED")).isZero();
    assertThat(countByEvent("ClientNoShowRecorded")).isZero();
    assertThat(countByEvent("ClientSelfBookingBlocked")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                Integer.class,
                tenantId,
                clientId))
        .isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.mark_no_show.v1' AND key_digest = ?",
                Integer.class,
                tenantId,
                com.appagendamento.idempotency.application.IdempotencyKey.digest(key)))
        .isZero();
  }

  @Test
  void restrictionLiftOutboxFailureRollsBackStateHistoryAuditAndReceipt() throws Exception {
    UUID clientId = seedNoShowClient("Restriction lift rollback");
    Instant asOf = Instant.now();
    NoShowFactRef fact = seedNoShowFacts(clientId, asOf.minusSeconds(60), 1).getFirst();
    replaceClientNoShowPolicy.execute(
        actor, "restriction-lift-rollback-policy-" + UUID.randomUUID(), 0, 1);
    noShowRestrictionActivation.evaluateAfterNoShow(tenantId, clientId, fact.appointmentId(), asOf);
    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.fail_restriction_lift_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type = 'ClientSelfBookingUnblocked' THEN RAISE EXCEPTION 'injected restriction lift outbox failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER fail_restriction_lift_outbox BEFORE INSERT ON agendamento.outbox_event FOR EACH ROW EXECUTE FUNCTION agendamento.fail_restriction_lift_outbox()");
    String key = "restriction-lift-rollback-" + UUID.randomUUID();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  mvc.perform(
                      post(
                              "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                              clientId,
                              restrictionId)
                          .with(authentication(principalToken(actor)))
                          .with(csrf())
                          .header("Idempotency-Key", key)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"expectedVersion\":1,\"reason\":\"Falha injetada\"}")))
          .isInstanceOf(Exception.class);
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER fail_restriction_lift_outbox ON agendamento.outbox_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.fail_restriction_lift_outbox()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND restriction_id = ?",
                String.class,
                tenantId,
                restrictionId))
        .isEqualTo("ACTIVE:1");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction_history WHERE tenant_id = ? AND restriction_id = ?",
                Integer.class,
                tenantId,
                restrictionId))
        .isEqualTo(1);
    assertThat(countByAction("CLIENT_SELF_BOOKING_UNBLOCKED")).isZero();
    assertThat(countByEvent("ClientSelfBookingUnblocked")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'clients.booking-restriction.lift.v1' AND key_digest = ?",
                Integer.class,
                tenantId,
                com.appagendamento.idempotency.application.IdempotencyKey.digest(key)))
        .isZero();
  }

  @Test
  void publicBookingWaitingForTenantLockSeesRestrictionCommittedBeforeIt() throws Exception {
    UUID clientId = seedNoShowClient("Concurrent restricted client");
    String phone =
        migrationJdbc.queryForObject(
            "SELECT normalized_phone FROM agendamento.client WHERE tenant_id = ? AND client_id = ?",
            String.class,
            tenantId,
            clientId);
    Instant asOf = Instant.now();
    NoShowFactRef source = seedNoShowFacts(clientId, asOf.minusSeconds(2), 1).getFirst();
    replaceClientNoShowPolicy.execute(
        actor, "concurrent-restriction-policy-" + UUID.randomUUID(), 0, 1);
    String key = "concurrent-restriction-booking-" + UUID.randomUUID();
    String capability = newCapability();
    String body = requestBody(serviceId, requestedStart, "Novo nome", phone);
    mvc.perform(
            post("/api/v1/public/" + slug + "/booking-intents/current")
                .with(csrf())
                .header("Idempotency-Key", key)
                .header("X-Booking-Recovery", capability)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());

    int appointmentsBefore = count("appointment", tenantId);
    var startGate = new CountDownLatch(1);
    var executor = Executors.newSingleThreadExecutor();
    try (var lockConnection = PostgresTestEnvironment.migrationDataSource().getConnection()) {
      lockConnection.setAutoCommit(false);
      try (var statement =
          lockConnection.prepareStatement(
              "SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE")) {
        statement.setObject(1, tenantId);
        statement.executeQuery().close();
      }
      Future<MvcResult> pendingBooking =
          executor.submit(
              () -> {
                startGate.countDown();
                return mvc.perform(
                        post("/api/v1/public/" + slug + "/appointments")
                            .with(csrf())
                            .header("Idempotency-Key", key)
                            .header("X-Booking-Recovery", capability)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andReturn();
              });
      assertThat(startGate.await(5, TimeUnit.SECONDS)).isTrue();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      boolean waitingOnTenantLock = false;
      while (System.nanoTime() < deadline) {
        Boolean waiting =
            migrationJdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE '%agendamento.tenant%' AND query ILIKE '%FOR UPDATE%')",
                Boolean.class);
        if (Boolean.TRUE.equals(waiting)) {
          waitingOnTenantLock = true;
          break;
        }
        Thread.yield();
      }
      assertThat(waitingOnTenantLock).as("public command waits on the shared tenant lock").isTrue();

      UUID restrictionId = UUID.randomUUID();
      try (var insert =
          lockConnection.prepareStatement(
              "INSERT INTO agendamento.client_booking_restriction (restriction_id, tenant_id, client_id, source_appointment_id, source_history_id, status, evaluated_at, count_at_activation, threshold_at_activation, policy_version, version, created_at) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, 1, 1, 1, 1, ?)")) {
        insert.setObject(1, restrictionId);
        insert.setObject(2, tenantId);
        insert.setObject(3, clientId);
        insert.setObject(4, source.appointmentId());
        insert.setObject(5, source.historyId());
        insert.setTimestamp(6, java.sql.Timestamp.from(asOf));
        insert.setTimestamp(7, java.sql.Timestamp.from(asOf));
        insert.executeUpdate();
      }
      try (var insert =
          lockConnection.prepareStatement(
              "INSERT INTO agendamento.client_booking_restriction_history (history_id, tenant_id, restriction_id, event_type, actor_type, occurred_at, count_at_decision, threshold_at_decision, policy_version, restriction_version) VALUES (?, ?, ?, 'ACTIVATED', 'SYSTEM', ?, 1, 1, 1, 1)")) {
        insert.setObject(1, UUID.randomUUID());
        insert.setObject(2, tenantId);
        insert.setObject(3, restrictionId);
        insert.setTimestamp(4, java.sql.Timestamp.from(asOf));
        insert.executeUpdate();
      }
      lockConnection.commit();
      assertThat(pendingBooking.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(404);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);
    assertThat(count("client", tenantId)).isEqualTo(1);
  }

  @Test
  void restrictionLiftRequiresCsrfValidReasonAndCurrentMembership() throws Exception {
    UUID clientId = seedNoShowClient("Restricted client authorization");
    Instant asOf = Instant.now();
    NoShowFactRef fact = seedNoShowFacts(clientId, asOf.minusSeconds(60), 1).getFirst();
    replaceClientNoShowPolicy.execute(actor, "restriction-auth-policy-" + UUID.randomUUID(), 0, 1);
    noShowRestrictionActivation.evaluateAfterNoShow(tenantId, clientId, fact.appointmentId(), asOf);
    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);

    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    clientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .header("Idempotency-Key", "restriction-no-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Revisão\"}"))
        .andExpect(status().isForbidden());

    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    clientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "restriction-empty-reason-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\" \"}"))
        .andExpect(status().isBadRequest());

    UUID foreignClientId = UUID.randomUUID();
    UUID foreignTenantId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Foreign restriction tenant', ?, 'UTC', 'ACTIVE')",
        foreignTenantId,
        "foreign-restriction-" + foreignTenantId.toString().substring(0, 8));
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Foreign restricted client', ?)",
        foreignClientId,
        foreignTenantId,
        "+5511998" + String.format("%06d", Math.floorMod(System.nanoTime(), 1_000_000)));
    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    foreignClientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "restriction-foreign-client-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Revisão\"}"))
        .andExpect(status().isNotFound());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        actor.adminUserId());
    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    clientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "restriction-revoked-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Revisão\"}"))
        .andExpect(status().isForbidden());

    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND restriction_id = ?",
                String.class,
                tenantId,
                restrictionId))
        .isEqualTo("ACTIVE");
    assertThat(countByAction("CLIENT_SELF_BOOKING_UNBLOCKED")).isZero();
    assertThat(countByEvent("ClientSelfBookingUnblocked")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'clients.booking-restriction.lift.v1'",
                Integer.class,
                tenantId))
        .isZero();
  }

  @Test
  void administratorCanLiftRestrictionWithReceiptReplayAndLaterNoShowCanReactivate()
      throws Exception {
    UUID clientId = seedNoShowClient("Restriction lifecycle");
    Instant asOf = Instant.now();
    var firstFact = seedNoShowFacts(clientId, asOf.minusSeconds(90), 1).getFirst();
    replaceClientNoShowPolicy.execute(
        actor, "restriction-lifecycle-policy-" + UUID.randomUUID(), 0, 1);
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, clientId, firstFact.appointmentId(), asOf);
    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);
    String key = "restriction-lift-" + UUID.randomUUID();
    String body = "{\"expectedVersion\":1,\"reason\":\"Revisão administrativa\"}";
    var lifted =
        mvc.perform(
                post(
                        "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                        clientId,
                        restrictionId)
                    .with(authentication(principalToken(actor)))
                    .with(csrf())
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("LIFTED"))
            .andExpect(jsonPath("$.version").value(2))
            .andReturn();
    assertThat(lifted.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("false");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction_history WHERE restriction_id = ?",
                Integer.class,
                restrictionId))
        .isEqualTo(2);
    assertThat(countByAction("CLIENT_SELF_BOOKING_UNBLOCKED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingUnblocked")).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment_history WHERE tenant_id = ? AND to_status = 'NO_SHOW'",
                Integer.class,
                tenantId))
        .isEqualTo(1);

    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    clientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIFTED"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(header().string("Idempotency-Replayed", "true"));
    assertThat(countByAction("CLIENT_SELF_BOOKING_UNBLOCKED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingUnblocked")).isEqualTo(1);

    var nextFact = seedNoShowFacts(clientId, asOf.plusSeconds(1), 1).getFirst();
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, clientId, nextFact.appointmentId(), asOf.plusSeconds(2));
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
                Integer.class,
                tenantId,
                clientId))
        .isEqualTo(1);
  }

  @Test
  void loweringThresholdRequiresExplicitReconciliationAndSameCauseIsNotReactivatedAfterLift()
      throws Exception {
    UUID clientId = seedNoShowClient("Explicit reconciliation");
    Instant asOf = Instant.now().plusSeconds(3600);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);
    List<NoShowFactRef> causes = seedNoShowFacts(clientId, asOf.minusSeconds(60), 1);
    replaceClientNoShowPolicy.execute(actor, "reconcile-policy-create-" + UUID.randomUUID(), 0, 3);
    replaceClientNoShowPolicy.execute(actor, "reconcile-policy-lower-" + UUID.randomUUID(), 1, 1);

    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                Integer.class,
                tenantId,
                clientId))
        .isZero();
    String key = "restriction-reconcile-" + UUID.randomUUID();
    String body = "{\"expectedPolicyVersion\":2}";
    var barrier = new CyclicBarrier(2);
    var executor = Executors.newFixedThreadPool(2);
    MvcResult first;
    MvcResult second;
    try {
      Future<MvcResult> firstRequest =
          executor.submit(() -> concurrentReconcile(barrier, clientId, key, body));
      Future<MvcResult> secondRequest =
          executor.submit(() -> concurrentReconcile(barrier, clientId, key, body));
      first = firstRequest.get(20, TimeUnit.SECONDS);
      second = secondRequest.get(20, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }
    assertThat(first.getResponse().getStatus()).isEqualTo(200);
    assertThat(second.getResponse().getStatus()).isEqualTo(200);
    assertThat(first.getResponse().getContentAsString())
        .isEqualTo(second.getResponse().getContentAsString());
    assertThat(
            List.of(
                first.getResponse().getHeader("Idempotency-Replayed"),
                second.getResponse().getHeader("Idempotency-Replayed")))
        .containsExactlyInAnyOrder("false", "true");
    MvcResult initial =
        "false".equals(first.getResponse().getHeader("Idempotency-Replayed")) ? first : second;
    org.assertj.core.api.Assertions.assertThat(
            objectMapper
                .readTree(initial.getResponse().getContentAsString())
                .get("decision")
                .asText())
        .isEqualTo("ACTIVATED");
    org.assertj.core.api.Assertions.assertThat(
            objectMapper.readTree(initial.getResponse().getContentAsString()).get("count").asInt())
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            objectMapper
                .readTree(initial.getResponse().getContentAsString())
                .get("threshold")
                .asInt())
        .isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
            objectMapper
                .readTree(initial.getResponse().getContentAsString())
                .get("policyVersion")
                .asInt())
        .isEqualTo(2);
    org.assertj.core.api.Assertions.assertThat(
            objectMapper
                .readTree(initial.getResponse().getContentAsString())
                .get("restrictionVersion")
                .asInt())
        .isEqualTo(1);
    UUID restrictionId =
        UUID.fromString(
            objectMapper
                .readTree(initial.getResponse().getContentAsString())
                .get("restrictionId")
                .asText());
    assertThat(initial.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("false");
    assertThat(
            appJdbc.queryForObject(
                "SELECT source_history_id FROM agendamento.client_booking_restriction WHERE restriction_id = ?",
                UUID.class,
                restrictionId))
        .isIn(causes.stream().map(NoShowFactRef::historyId).toList());
    assertThat(
            appJdbc.queryForObject(
                "SELECT event_type || ':' || actor_type || ':' || policy_version || ':' || count_at_decision "
                    + "FROM agendamento.client_booking_restriction_history WHERE restriction_id = ?",
                String.class,
                restrictionId))
        .isEqualTo("RECONCILED:ADMIN:2:1");
    assertThat(countByAction("CLIENT_SELF_BOOKING_RECONCILED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingBlocked")).isEqualTo(1);

    reconcile(clientId, key, body)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.restrictionId").value(restrictionId.toString()));
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction_history WHERE restriction_id = ?",
                Integer.class,
                restrictionId))
        .isEqualTo(1);
    assertThat(countByAction("CLIENT_SELF_BOOKING_RECONCILED")).isEqualTo(1);
    assertThat(countByEvent("ClientSelfBookingBlocked")).isEqualTo(1);

    mvc.perform(
            post(
                    "/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift",
                    clientId,
                    restrictionId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "reconcile-lift-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Revisão explícita\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("LIFTED"));

    reconcile(clientId, "reconcile-same-cause-" + UUID.randomUUID(), body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.decision").value("NO_UNUSED_CAUSAL_FACT"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE restriction_id = ?",
                String.class,
                restrictionId))
        .isEqualTo("LIFTED");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
                Integer.class,
                tenantId,
                clientId))
        .isZero();

    NoShowFactRef newCause = seedNoShowFacts(clientId, asOf.plusSeconds(1), 1).getFirst();
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, clientId, newCause.appointmentId(), asOf.plusSeconds(2));
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
                Integer.class,
                tenantId,
                clientId))
        .isEqualTo(1);
  }

  @Test
  void correctionAndWindowAgingReduceCountWithoutLiftingActiveRestriction() throws Exception {
    UUID clientId = seedNoShowClient("Restriction persists after count change");
    Instant asOf = Instant.now().plusSeconds(3600);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);
    List<NoShowFactRef> facts = seedNoShowFacts(clientId, asOf.minusSeconds(60), 2);
    replaceClientNoShowPolicy.execute(
        actor, "persistent-restriction-policy-" + UUID.randomUUID(), 0, 2);
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, clientId, facts.getFirst().appointmentId(), asOf);
    UUID restrictionId =
        appJdbc.queryForObject(
            "SELECT restriction_id FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ? AND status = 'ACTIVE'",
            UUID.class,
            tenantId,
            clientId);

    mvc.perform(
            post(
                    "/api/v1/admin/appointments/{id}/correct-no-show",
                    facts.getFirst().appointmentId())
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "reconcile-correct-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2,\"reason\":\"Correção confirmada\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.restrictionStatus").value("RESTRICTED"));

    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf.plusSeconds(366L * 24 * 60 * 60));
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0))
        .andExpect(jsonPath("$.restrictionStatus").value("RESTRICTED"))
        .andExpect(jsonPath("$.activeRestriction.restrictionId").value(restrictionId.toString()));
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.client_booking_restriction WHERE restriction_id = ?",
                String.class,
                restrictionId))
        .isEqualTo("ACTIVE:1");
    assertThat(countByAction("CLIENT_SELF_BOOKING_UNBLOCKED")).isZero();
  }

  @Test
  void reconciliationRequiresConfiguredCurrentPolicyAndCurrentTenantMembership() throws Exception {
    UUID clientId = seedNoShowClient("Reconciliation authorization");
    mvc.perform(
            post("/api/v1/admin/clients/{id}/restrictions/reconcile", clientId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "reconcile-no-policy-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":1}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("POLICY_NOT_CONFIGURED"));

    replaceClientNoShowPolicy.execute(actor, "reconcile-auth-policy-" + UUID.randomUUID(), 0, 1);
    reconcile(
            clientId,
            "reconcile-stale-policy-" + UUID.randomUUID(),
            "{\"expectedPolicyVersion\":2}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    UUID foreignClient = seedForeignAppointment(bookingDate);
    UUID foreignClientId =
        migrationJdbc.queryForObject(
            "SELECT client_id FROM agendamento.appointment WHERE appointment_id = ?",
            UUID.class,
            foreignClient);
    mvc.perform(
            post("/api/v1/admin/clients/{id}/restrictions/reconcile", foreignClientId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "reconcile-foreign-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":1}"))
        .andExpect(status().isNotFound());

    mvc.perform(
            post("/api/v1/admin/clients/{id}/restrictions/reconcile", clientId)
                .with(authentication(principalToken(actor)))
                .header("Idempotency-Key", "reconcile-no-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":1}"))
        .andExpect(status().isForbidden());

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        actor.adminUserId());
    mvc.perform(
            post("/api/v1/admin/clients/{id}/restrictions/reconcile", clientId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "reconcile-revoked-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedPolicyVersion\":1}"))
        .andExpect(status().isForbidden());
    assertThat(countByAction("CLIENT_SELF_BOOKING_RECONCILED")).isZero();
  }

  @Test
  void reconciliationAuditFailureRollsBackRestrictionHistoryOutboxAndReceipt() throws Exception {
    UUID clientId = seedNoShowClient("Reconciliation rollback");
    Instant asOf = Instant.now().plusSeconds(3600);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);
    seedNoShowFacts(clientId, asOf.minusSeconds(60), 1);
    replaceClientNoShowPolicy.execute(
        actor, "reconcile-rollback-policy-" + UUID.randomUUID(), 0, 1);
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.fail_reconciliation_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'CLIENT_SELF_BOOKING_RECONCILED' THEN RAISE EXCEPTION 'injected reconciliation audit failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER fail_reconciliation_audit BEFORE INSERT ON agendamento.audit_event FOR EACH ROW EXECUTE FUNCTION agendamento.fail_reconciliation_audit()");
    String key = "reconcile-rollback-" + UUID.randomUUID();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> reconcile(clientId, key, "{\"expectedPolicyVersion\":1}"))
          .isInstanceOf(jakarta.servlet.ServletException.class)
          .hasStackTraceContaining("injected reconciliation audit failure");
    } finally {
      migrationJdbc.execute("DROP TRIGGER fail_reconciliation_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.fail_reconciliation_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                Integer.class,
                tenantId,
                clientId))
        .isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_booking_restriction_history history JOIN agendamento.client_booking_restriction restriction USING (tenant_id, restriction_id) WHERE restriction.tenant_id = ? AND restriction.client_id = ?",
                Integer.class,
                tenantId,
                clientId))
        .isZero();
    assertThat(countByAction("CLIENT_SELF_BOOKING_RECONCILED")).isZero();
    assertThat(countByEvent("ClientSelfBookingBlocked")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'clients.booking-restriction.reconcile.v1' AND key_digest = ?",
                Integer.class,
                tenantId,
                com.appagendamento.idempotency.application.IdempotencyKey.digest(key)))
        .isZero();
  }

  @Test
  void absentPolicyAllowsUnrestrictedPublicBookingButDoesNotReleaseExistingRestriction()
      throws Exception {
    UUID unrestrictedClient = seedNoShowClient("No policy remains open");
    String unrestrictedPhone =
        migrationJdbc.queryForObject(
            "SELECT normalized_phone FROM agendamento.client WHERE tenant_id = ? AND client_id = ?",
            String.class,
            tenantId,
            unrestrictedClient);
    String allowedStart = requestedStart;
    submitBooking(
            "no-policy-allowed-" + UUID.randomUUID(),
            requestBody(serviceId, allowedStart, "Sem política", unrestrictedPhone))
        .andExpect(status().isCreated());
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", unrestrictedClient)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyStatus").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.restrictionStatus").value("NOT_CONFIGURED"));

    UUID restrictedClient = seedNoShowClient("Policy removed but restriction remains");
    String restrictedPhone =
        migrationJdbc.queryForObject(
            "SELECT normalized_phone FROM agendamento.client WHERE tenant_id = ? AND client_id = ?",
            String.class,
            tenantId,
            restrictedClient);
    Instant asOf = Instant.parse("2026-10-20T18:00:00Z");
    replaceClientNoShowPolicy.execute(
        actor, "no-policy-restriction-policy-" + UUID.randomUUID(), 0, 1);
    NoShowFactRef cause = seedNoShowFacts(restrictedClient, asOf.minusSeconds(60), 1).getFirst();
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, restrictedClient, cause.appointmentId(), asOf);
    migrationJdbc.update(
        "DELETE FROM agendamento.client_no_show_policy WHERE tenant_id = ?", tenantId);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf.plusSeconds(1));

    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", restrictedClient)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyStatus").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.restrictionStatus").value("RESTRICTED"));
    int appointmentsBefore = count("appointment", tenantId);
    String blockedStart = bookingDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString();
    var blocked =
        submitBooking(
                "no-policy-active-restriction-" + UUID.randomUUID(),
                requestBody(serviceId, blockedStart, "Bloqueio mantido", restrictedPhone))
            .andExpect(status().isNotFound())
            .andReturn();
    assertThat(blocked.getResponse().getContentAsString())
        .doesNotContain("NO_SHOW", "falta", "restrição", restrictedClient.toString());
    assertThat(count("appointment", tenantId)).isEqualTo(appointmentsBefore);
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                String.class,
                tenantId,
                restrictedClient))
        .isEqualTo("ACTIVE");
  }

  private ResultActions reconcile(UUID clientId, String key, String body) throws Exception {
    return mvc.perform(
        post("/api/v1/admin/clients/{id}/restrictions/reconcile", clientId)
            .with(authentication(principalToken(actor)))
            .with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
  }

  private MvcResult concurrentReconcile(
      CyclicBarrier barrier, UUID clientId, String key, String body) {
    try {
      barrier.await();
      return reconcile(clientId, key, body).andReturn();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  void noShowFactsAreCalendarWindowTenantScopedAndCorrectionPreservesOriginalFact()
      throws Exception {
    submitBooking(
            "facts-no-show-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Fato de falta", "+5511999994350"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    UUID clientId = clientId(appointmentId);
    setAppointmentInPast(appointmentId);
    postOutcome(appointmentId, "mark-no-show", "facts-no-show-mark-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());
    String cancelStart = bookingDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString();
    submitBooking(
            "facts-cancelled-" + UUID.randomUUID(),
            requestBody(serviceId, cancelStart, "Fato de falta", "+5511999994350"))
        .andExpect(status().isCreated());
    UUID cancelledAppointmentId = appointmentIdForTenant();
    postOutcome(cancelledAppointmentId, "cancel", "facts-cancel-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());
    String completedStart = bookingDate.atTime(11, 0).atZone(ZONE).toOffsetDateTime().toString();
    submitBooking(
            "facts-completed-" + UUID.randomUUID(),
            requestBody(serviceId, completedStart, "Fato de falta", "+5511999994350"))
        .andExpect(status().isCreated());
    UUID completedAppointmentId = appointmentIdForTenant();
    setAppointmentInPast(completedAppointmentId);
    postOutcome(completedAppointmentId, "complete", "facts-complete-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());
    Instant asOf = Instant.now().plusSeconds(10);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);

    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
        .andExpect(jsonPath("$.policyStatus").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.threshold").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.restrictionStatus").value("NOT_CONFIGURED"))
        .andExpect(jsonPath("$.count").value(1))
        .andExpect(jsonPath("$.facts[0].contributes").value(true))
        .andExpect(jsonPath("$.timeZone").value(ZONE.getId()));
    replaceClientNoShowPolicy.execute(actor, "facts-policy-" + UUID.randomUUID(), 0, 3);
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policyStatus").value("CONFIGURED"))
        .andExpect(jsonPath("$.threshold").value(3))
        .andExpect(jsonPath("$.count").value(1));

    UUID foreignAppointment = seedForeignAppointment(bookingDate);
    UUID foreignClient =
        appJdbc.queryForObject(
            "SELECT client_id FROM agendamento.appointment WHERE appointment_id = ?",
            UUID.class,
            foreignAppointment);
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", foreignClient)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", foreignAppointment)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "foreign-no-show-correction-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Não deve revelar\"}"))
        .andExpect(status().isNotFound());

    String correctionKey = "correct-no-show-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "stale-no-show-correction-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Versão obsoleta\"}"))
        .andExpect(status().isConflict());
    assertThat(countHistoryReason(appointmentId, "APPOINTMENT_NO_SHOW_CORRECTED")).isZero();
    assertThat(countByAction("APPOINTMENT_NO_SHOW_CORRECTED")).isZero();
    assertThat(countByEvent("ClientNoShowCorrected")).isZero();
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", correctionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":2,\"reason\":\"Presença confirmada pelo estabelecimento\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.version").value(3));
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", correctionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":2,\"reason\":\"Presença confirmada pelo estabelecimento\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(3));
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", correctionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2,\"reason\":\"Outro comando\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0))
        .andExpect(jsonPath("$.totalFacts").value(1))
        .andExpect(jsonPath("$.facts[0].contributes").value(false))
        .andExpect(
            jsonPath("$.facts[0].correctionReason")
                .value("Presença confirmada pelo estabelecimento"));
    mvc.perform(
            get("/api/v1/admin/appointments/{id}/history", appointmentId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(
            jsonPath(
                    "$.items[?(@.reasonCode == 'APPOINTMENT_NO_SHOW_CORRECTED')].correctsHistoryId")
                .exists())
        .andExpect(
            jsonPath("$.items[?(@.reasonCode == 'APPOINTMENT_NO_SHOW_CORRECTED')].toStatus")
                .value("COMPLETED"));

    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("COMPLETED:3");
    assertThat(countHistoryReason(appointmentId, "APPOINTMENT_NO_SHOW")).isEqualTo(1);
    assertThat(countHistoryReason(appointmentId, "APPOINTMENT_NO_SHOW_CORRECTED")).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_NO_SHOW_CORRECTED")).isEqualTo(1);
    assertThat(countByEvent("ClientNoShowCorrected")).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT service_name_snapshot || ':' || service_duration_minutes || ':' || service_buffer_after_minutes FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Consulta:30:15");
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.threshold").value(3))
        .andExpect(jsonPath("$.count").value(0));
    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE tenant_id = ? AND user_id = ?",
        tenantId,
        actor.adminUserId());
    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", correctionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":2,\"reason\":\"Presença confirmada pelo estabelecimento\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void noShowFactsPaginationWithTimestampTiesHasNoOmissionsOrDuplicates() throws Exception {
    Instant asOf = Instant.parse("2026-10-20T18:00:00Z");
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);
    UUID clientId = seedNoShowClient("paginated-no-shows");
    List<NoShowFactRef> expected = seedNoShowFacts(clientId, asOf.minusSeconds(1), 57);
    expected.sort(
        java.util.Comparator.comparing(NoShowFactRef::occurredAt)
            .reversed()
            .thenComparing(
                fact -> fact.historyId().toString(), java.util.Comparator.reverseOrder()));

    List<UUID> actualHistoryIds = new java.util.ArrayList<>();
    String cursor = null;
    int pageCount = 0;
    do {
      var request =
          get("/api/v1/admin/clients/{id}/no-shows", clientId)
              .with(authentication(principalToken(actor)))
              .param("limit", "20");
      if (cursor != null) request.param("cursor", cursor);
      var response = mvc.perform(request).andExpect(status().isOk()).andReturn();
      var json = objectMapper.readTree(response.getResponse().getContentAsString());
      assertThat(json.get("asOf").asText()).isEqualTo(asOf.toString());
      assertThat(json.get("count").asLong()).isEqualTo(57);
      assertThat(json.get("totalFacts").asLong()).isEqualTo(57);
      assertThat(json.get("policyStatus").asText()).isEqualTo("NOT_CONFIGURED");
      assertThat(json.get("restrictionStatus").asText()).isEqualTo("NOT_CONFIGURED");
      assertThat(json.get("facts").size()).isBetween(1, 20);
      for (var fact : json.get("facts")) {
        actualHistoryIds.add(UUID.fromString(fact.get("historyId").asText()));
        assertThat(fact.get("contributes").asBoolean()).isTrue();
      }
      pageCount++;
      boolean hasMore = json.get("hasMore").asBoolean();
      if (hasMore) {
        assertThat(json.get("nextCursor").asText()).isNotBlank();
        cursor = json.get("nextCursor").asText();
      } else {
        assertThat(json.get("nextCursor").isNull()).isTrue();
        cursor = null;
      }
    } while (cursor != null);

    assertThat(pageCount).isEqualTo(3);
    assertThat(actualHistoryIds).hasSize(57).doesNotHaveDuplicates();
    assertThat(actualHistoryIds)
        .containsExactlyElementsOf(expected.stream().map(NoShowFactRef::historyId).toList());
  }

  @Test
  void noShowFactsReadKeepsRepeatableReadSnapshotDuringConcurrentCorrection() throws Exception {
    Instant asOf = Instant.now().plusSeconds(3600);
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);
    UUID clientId = seedNoShowClient("concurrent-no-show-facts");
    UUID appointmentId =
        seedNoShowFacts(clientId, asOf.minusSeconds(120), 1).getFirst().appointmentId();
    CountDownLatch countRead = new CountDownLatch(1);
    CountDownLatch continueRead = new CountDownLatch(1);
    try (ExecutorService requests = Executors.newSingleThreadExecutor()) {
      Future<MvcResult> firstRead =
          requests.submit(
              () ->
                  readNoShowSnapshotBetweenCountAndFacts(clientId, asOf, countRead, continueRead));
      assertThat(countRead.await(10, TimeUnit.SECONDS)).isTrue();

      correctAdministrativeNoShow.execute(
          actor,
          "concurrent-facts-correction-" + UUID.randomUUID(),
          appointmentId,
          2,
          "Correção durante consulta de fatos");
      continueRead.countDown();
      assertThat(firstRead.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
    } finally {
      continueRead.countDown();
    }

    var current =
        mvc.perform(
                get("/api/v1/admin/clients/{id}/no-shows", clientId)
                    .with(authentication(principalToken(actor))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.count").value(0))
            .andExpect(jsonPath("$.facts[0].contributes").value(false))
            .andExpect(jsonPath("$.facts[0].currentStatus").value("COMPLETED"))
            .andReturn();
    assertThat(current.getResponse().getStatus()).isEqualTo(200);
  }

  private MvcResult readNoShowSnapshotBetweenCountAndFacts(
      UUID clientId, Instant asOf, CountDownLatch countRead, CountDownLatch continueRead)
      throws Exception {
    try (var connection = PostgresTestEnvironment.migrationDataSource().getConnection()) {
      connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
      connection.setAutoCommit(false);
      try (var statement =
          connection.prepareStatement(
              "SELECT count(DISTINCT a.appointment_id) FROM agendamento.appointment_history h "
                  + "JOIN agendamento.appointment a ON a.tenant_id = h.tenant_id "
                  + "AND a.appointment_id = h.appointment_id LEFT JOIN agendamento.appointment_history correction "
                  + "ON correction.tenant_id = h.tenant_id AND correction.appointment_id = h.appointment_id "
                  + "AND correction.corrects_history_id = h.history_id AND correction.occurred_at <= ? "
                  + "WHERE h.tenant_id = ? AND a.client_id = ? AND h.to_status = 'NO_SHOW' "
                  + "AND h.reason_code = 'APPOINTMENT_NO_SHOW' AND h.occurred_at >= ? "
                  + "AND h.occurred_at < ? AND correction.history_id IS NULL")) {
        statement.setTimestamp(1, java.sql.Timestamp.from(asOf));
        statement.setObject(2, tenantId);
        statement.setObject(3, clientId);
        statement.setTimestamp(
            4,
            java.sql.Timestamp.from(
                asOf.atZone(java.time.ZoneOffset.UTC).minusMonths(12).toInstant()));
        statement.setTimestamp(5, java.sql.Timestamp.from(asOf));
        try (var result = statement.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getLong(1)).isEqualTo(1);
        }
      }
      countRead.countDown();
      if (!continueRead.await(15, TimeUnit.SECONDS)) {
        throw new AssertionError("Leitura não retomada pelo teste de concorrência");
      }
      try (var statement =
          connection.prepareStatement(
              "SELECT a.status, correction.history_id FROM agendamento.appointment_history h "
                  + "JOIN agendamento.appointment a ON a.tenant_id = h.tenant_id "
                  + "AND a.appointment_id = h.appointment_id LEFT JOIN agendamento.appointment_history correction "
                  + "ON correction.tenant_id = h.tenant_id AND correction.appointment_id = h.appointment_id "
                  + "AND correction.corrects_history_id = h.history_id AND correction.occurred_at <= ? "
                  + "WHERE h.tenant_id = ? AND a.client_id = ? AND h.to_status = 'NO_SHOW' "
                  + "AND h.reason_code = 'APPOINTMENT_NO_SHOW' AND h.appointment_id = ?")) {
        statement.setTimestamp(1, java.sql.Timestamp.from(asOf));
        statement.setObject(2, tenantId);
        statement.setObject(3, clientId);
        statement.setObject(4, appointmentIdFor(clientId));
        try (var result = statement.executeQuery()) {
          assertThat(result.next()).isTrue();
          assertThat(result.getString("status")).isEqualTo("NO_SHOW");
          assertThat(result.getObject("history_id")).isNull();
        }
      }
      connection.commit();
    }
    return mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0))
        .andExpect(jsonPath("$.facts[0].contributes").value(false))
        .andExpect(jsonPath("$.facts[0].currentStatus").value("COMPLETED"))
        .andReturn();
  }

  private UUID appointmentIdFor(UUID clientId) {
    return appJdbc.queryForObject(
        "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ? AND client_id = ?",
        UUID.class,
        tenantId,
        clientId);
  }

  @Test
  void noShowFactsUseInclusiveStartExclusiveAsOfAndCountUniqueAppointments() throws Exception {
    Instant asOf = Instant.parse("2024-02-29T17:42:31.123Z");
    Instant windowStart = Instant.parse("2023-02-28T17:42:31.123Z");
    replaceWeeklySchedule.execute(
        actor,
        "boundary-schedule-" + UUID.randomUUID(),
        1,
        List.of(new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.of(13, 0))));
    List<UUID> appointmentIds = new java.util.ArrayList<>();
    for (int index = 0; index < 4; index++) {
      String start =
          bookingDate
              .atTime(9, 0)
              .plusMinutes(index * 60L)
              .atZone(ZONE)
              .toOffsetDateTime()
              .toString();
      submitBooking(
              "boundary-no-show-" + index + "-" + UUID.randomUUID(),
              requestBody(serviceId, start, "Mesmo cliente faltoso", "+5511999994360"))
          .andExpect(status().isCreated());
      UUID appointmentId = appointmentIdForTenant();
      appointmentIds.add(appointmentId);
      setAppointmentInPast(appointmentId);
      postOutcome(
              appointmentId, "mark-no-show", "boundary-mark-" + index + "-" + UUID.randomUUID(), 1)
          .andExpect(status().isOk());
    }
    UUID clientId = clientId(appointmentIds.getFirst());
    Instant[] occurrences = {windowStart.minusNanos(1), windowStart, asOf.minusNanos(1), asOf};
    for (int index = 0; index < appointmentIds.size(); index++) {
      migrationJdbc.update(
          "UPDATE agendamento.appointment_history SET occurred_at = ? WHERE appointment_id = ? AND reason_code = 'APPOINTMENT_NO_SHOW'",
          java.sql.Timestamp.from(occurrences[index]),
          appointmentIds.get(index));
    }
    org.mockito.Mockito.when(clock.instant()).thenReturn(asOf);

    mvc.perform(
            get("/api/v1/admin/clients/{id}/no-shows", clientId)
                .with(authentication(principalToken(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.windowStart").value("2023-02-28T17:42:31.123Z"))
        .andExpect(jsonPath("$.asOf").value("2024-02-29T17:42:31.123Z"))
        .andExpect(jsonPath("$.count").value(2))
        .andExpect(jsonPath("$.totalFacts").value(2));
  }

  @Test
  void concurrentNoShowCorrectionsHaveOneCasWinnerAndOneEffectSet() throws Exception {
    submitBooking(
            "concurrent-no-show-correction-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Correção concorrente", "+5511999994361"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    setAppointmentInPast(appointmentId);
    postOutcome(appointmentId, "mark-no-show", "concurrent-no-show-mark-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());

    CyclicBarrier barrier = new CyclicBarrier(2);
    try (ExecutorService requests = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          requests.submit(
              () -> concurrentNoShowCorrection(barrier, appointmentId, "Primeira correção"));
      Future<Integer> second =
          requests.submit(
              () -> concurrentNoShowCorrection(barrier, appointmentId, "Segunda correção"));
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(persistedStatus(appointmentId)).isEqualTo("COMPLETED:3");
    assertThat(countHistoryReason(appointmentId, "APPOINTMENT_NO_SHOW")).isEqualTo(1);
    assertThat(countHistoryReason(appointmentId, "APPOINTMENT_NO_SHOW_CORRECTED")).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_NO_SHOW_CORRECTED")).isEqualTo(1);
    assertThat(countByEvent("ClientNoShowCorrected")).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.correct-no-show.v1' AND state = 'COMPLETED'",
                Integer.class,
                tenantId))
        .isEqualTo(1);
  }

  @Test
  void noShowCorrectionOutboxFailureRollsBackStateHistoryAuditAndReceipt() throws Exception {
    submitBooking(
            "facts-rollback-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Rollback falta", "+5511999994352"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    setAppointmentInPast(appointmentId);
    postOutcome(appointmentId, "mark-no-show", "facts-rollback-mark-" + UUID.randomUUID(), 1)
        .andExpect(status().isOk());
    int historyBefore = count("appointment_history", tenantId);
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.reject_no_show_correction_outbox() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type = 'ClientNoShowCorrected' THEN RAISE EXCEPTION 'injected correction outbox failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_no_show_correction_outbox BEFORE INSERT ON agendamento.outbox_event FOR EACH ROW EXECUTE FUNCTION agendamento.reject_no_show_correction_outbox()");
    String key = "rollback-no-show-correction-" + UUID.randomUUID();
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  correctAdministrativeNoShow.execute(
                      actor, key, appointmentId, 2, "Correção de teste"))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              appJdbc.queryForObject(
                  "SELECT status || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                  String.class,
                  appointmentId))
          .isEqualTo("NO_SHOW:2");
      assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
      assertThat(countByAction("APPOINTMENT_NO_SHOW_CORRECTED")).isZero();
      assertThat(countByEvent("ClientNoShowCorrected")).isZero();
      assertThat(
              appJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.correct-no-show.v1'",
                  Integer.class,
                  tenantId))
          .isZero();
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER reject_no_show_correction_outbox ON agendamento.outbox_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_no_show_correction_outbox()");
    }
  }

  @Test
  void futureCompletionAndNoShowAreRejectedWithoutAnySuccessEffects() throws Exception {
    submitBooking(
            "future-outcome-booking-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Futuro", "+5511999994324"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    int historyBefore = count("appointment_history", tenantId);
    for (String action : List.of("complete", "mark-no-show")) {
      mvc.perform(
              post("/api/v1/admin/appointments/{id}/" + action, appointmentId)
                  .with(authentication(principalToken(actor)))
                  .with(csrf())
                  .header("Idempotency-Key", "blocked-" + action + "-" + UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":1}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    }
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_COMPLETED")).isZero();
    assertThat(countByAction("APPOINTMENT_NO_SHOW_RECORDED")).isZero();
    assertThat(countByEvent("AppointmentCompleted")).isZero();
    assertThat(countByEvent("ClientNoShowRecorded")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND (command_scope LIKE 'appointments.admin.complete%' OR command_scope LIKE 'appointments.admin.mark_no_show%')",
                Integer.class, tenantId))
        .isZero();
  }

  @Test
  void outcomesWaitUntilOccupiedUntilIncludingBufferAndReplayAtTheBoundary() throws Exception {
    submitBooking(
            "positive-buffer-outcome-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Buffer positivo", "+5511999994341"))
        .andExpect(status().isCreated());
    UUID positiveBufferId = appointmentIdForTenant();

    UUID zeroBufferService =
        createService
            .execute(
                actor,
                "zero-buffer-outcome-" + UUID.randomUUID(),
                "Consulta sem buffer",
                "Atendimento",
                30,
                0,
                ServiceStatus.ACTIVE)
            .value()
            .serviceId();
    String zeroBufferStart = bookingDate.atTime(10, 0).atZone(ZONE).toOffsetDateTime().toString();
    submitBooking(
            "zero-buffer-outcome-booking-" + UUID.randomUUID(),
            requestBody(zeroBufferService, zeroBufferStart, "Buffer zero", "+5511999994342"))
        .andExpect(status().isCreated());
    UUID zeroBufferId = appointmentIdForClientName("Buffer zero");

    Instant positiveStart = java.time.OffsetDateTime.parse(requestedStart).toInstant();
    Instant positiveOccupiedUntil = positiveStart.plusSeconds(45 * 60L);
    setAppointmentInterval(
        positiveBufferId,
        positiveStart,
        positiveStart.plusSeconds(30 * 60L),
        positiveOccupiedUntil);
    String positiveKey = "complete-positive-boundary-" + UUID.randomUUID();
    int idempotencyRecordsBeforePositiveBoundary = idempotencyRecordCount();
    org.mockito.Mockito.when(clock.instant()).thenReturn(positiveOccupiedUntil.minusNanos(1));
    postOutcome(positiveBufferId, "complete", positiveKey, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    assertThat(persistedStatus(positiveBufferId)).isEqualTo("CONFIRMED:1");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ? AND status = 'CONFIRMED' AND occupied_until = ? AND tstzrange(starts_at, occupied_until, '[)') @> ?::timestamptz",
                Integer.class,
                tenantId,
                positiveBufferId,
                java.sql.Timestamp.from(positiveOccupiedUntil),
                java.sql.Timestamp.from(positiveStart)))
        .isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_COMPLETED")).isZero();
    assertThat(countByEvent("AppointmentCompleted")).isZero();
    assertThat(countHistoryReason(positiveBufferId, "APPOINTMENT_COMPLETED")).isZero();
    assertThat(idempotencyRecordCount()).isEqualTo(idempotencyRecordsBeforePositiveBoundary);

    org.mockito.Mockito.when(clock.instant()).thenReturn(positiveOccupiedUntil);
    postOutcome(positiveBufferId, "complete", positiveKey, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.version").value(2));
    org.mockito.Mockito.when(clock.instant()).thenReturn(positiveOccupiedUntil.plusNanos(1));
    postOutcome(positiveBufferId, "complete", positiveKey, 1)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(2));

    Instant zeroStart = bookingDate.atTime(10, 0).atZone(ZONE).toInstant();
    Instant zeroOccupiedUntil = zeroStart.plusSeconds(30 * 60L);
    setAppointmentInterval(zeroBufferId, zeroStart, zeroOccupiedUntil, zeroOccupiedUntil);
    String zeroKey = "no-show-zero-boundary-" + UUID.randomUUID();
    int idempotencyRecordsBeforeZeroBoundary = idempotencyRecordCount();
    org.mockito.Mockito.when(clock.instant()).thenReturn(zeroOccupiedUntil.minusNanos(1));
    postOutcome(zeroBufferId, "mark-no-show", zeroKey, 1)
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("INVALID_STATE_TRANSITION"));
    assertThat(persistedStatus(zeroBufferId)).isEqualTo("CONFIRMED:1");
    assertThat(countByAction("APPOINTMENT_NO_SHOW_RECORDED")).isZero();
    assertThat(countByEvent("ClientNoShowRecorded")).isZero();
    assertThat(countHistoryReason(zeroBufferId, "APPOINTMENT_NO_SHOW")).isZero();
    assertThat(idempotencyRecordCount()).isEqualTo(idempotencyRecordsBeforeZeroBoundary);

    org.mockito.Mockito.when(clock.instant()).thenReturn(zeroOccupiedUntil);
    postOutcome(zeroBufferId, "mark-no-show", zeroKey, 1)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("NO_SHOW"))
        .andExpect(jsonPath("$.version").value(2));
    org.mockito.Mockito.when(clock.instant()).thenReturn(zeroOccupiedUntil.plusNanos(1));
    postOutcome(zeroBufferId, "mark-no-show", zeroKey, 1)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(countByAction("APPOINTMENT_COMPLETED")).isEqualTo(1);
    assertThat(countByEvent("AppointmentCompleted")).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_NO_SHOW_RECORDED")).isEqualTo(1);
    assertThat(countByEvent("ClientNoShowRecorded")).isEqualTo(1);
    assertThat(countHistoryReason(positiveBufferId, "APPOINTMENT_COMPLETED")).isEqualTo(1);
    assertThat(countHistoryReason(zeroBufferId, "APPOINTMENT_NO_SHOW")).isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackCancellationAndDoesNotReleaseTheSlot() throws Exception {
    submitBooking(
            "rollback-outcome-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Rollback", "+5511999994325"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    String key = "rollback-cancel-" + UUID.randomUUID();
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.fail_outcome_audit() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.action = 'APPOINTMENT_CANCELLED_BY_PROVIDER' THEN RAISE EXCEPTION 'injected failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER fail_outcome_audit BEFORE INSERT ON agendamento.audit_event FOR EACH ROW EXECUTE FUNCTION agendamento.fail_outcome_audit()");
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () ->
                  mvc.perform(
                      post("/api/v1/admin/appointments/{id}/cancel", appointmentId)
                          .with(authentication(principalToken(actor)))
                          .with(csrf())
                          .header("Idempotency-Key", key)
                          .contentType(MediaType.APPLICATION_JSON)
                          .content("{\"expectedVersion\":1}")))
          .isInstanceOf(Exception.class);
    } finally {
      migrationJdbc.execute("DROP TRIGGER fail_outcome_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.fail_outcome_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CONFIRMED:1");
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);
    assertThat(countByAction("APPOINTMENT_CANCELLED_BY_PROVIDER")).isZero();
    assertThat(countByEvent("AppointmentCancelled")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.cancel_by_provider.v1' AND key_digest = ?",
                Integer.class,
                tenantId,
                com.appagendamento.idempotency.application.IdempotencyKey.digest(key)))
        .isZero();
    mvc.perform(
            get("/api/v1/public/" + slug + "/availability")
                .param("serviceId", serviceId.toString())
                .param("from", bookingDate.toString())
                .param("to", bookingDate.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.days[0].slots.length()").value(3));
  }

  @Test
  void outcomesRequireCsrfAndConcealAppointmentsFromOtherTenants() throws Exception {
    submitBooking(
            "csrf-outcome-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "CSRF", "+5511999994326"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/cancel", appointmentId)
                .with(authentication(principalToken(actor)))
                .header("Idempotency-Key", "missing-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isForbidden());

    UUID foreignId = seedForeignAppointment(bookingDate);
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/cancel", foreignId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "foreign-outcome-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CONFIRMED");
    assertThat(countByAction("APPOINTMENT_CANCELLED_BY_PROVIDER")).isZero();
  }

  @Test
  void administratorCanCancelAnExistingAppointmentAfterServiceDeactivation() throws Exception {
    submitBooking(
            "inactive-service-outcome-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Inativo", "+5511999994327"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    setServiceStatus.execute(
        actor,
        serviceId,
        "deactivate-before-cancel-" + UUID.randomUUID(),
        0,
        ServiceStatus.INACTIVE);
    mvc.perform(
            post("/api/v1/admin/appointments/{id}/cancel", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "inactive-service-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED_BY_PROVIDER"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CANCELLED_BY_PROVIDER");
  }

  @Test
  void cancellationAndCompletionRaceHasOnlyOneCommittedTerminalTransition() throws Exception {
    submitBooking(
            "race-outcome-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Corrida", "+5511999994328"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    setAppointmentInPast(appointmentId);
    installRescheduleUpdatePause(appointmentId);
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var cancellation =
          commands.submit(
              () -> submitOutcome(appointmentId, "cancel", "cancel-race-" + UUID.randomUUID()));
      awaitUpdateInProgress();
      var completion =
          commands.submit(
              () -> submitOutcome(appointmentId, "complete", "complete-race-" + UUID.randomUUID()));
      int cancelStatus = cancellation.get();
      int completeStatus = completion.get();
      assertThat(List.of(cancelStatus, completeStatus)).contains(200, 409);
    } finally {
      removeRescheduleUpdatePause();
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status || ':' || version FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .matches("(CANCELLED_BY_PROVIDER|COMPLETED):2");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = ? AND from_status = 'CONFIRMED'",
                Integer.class,
                appointmentId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE target_id = ? AND action IN ('APPOINTMENT_CANCELLED_BY_PROVIDER','APPOINTMENT_COMPLETED')",
                Integer.class,
                appointmentId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = ? AND event_type IN ('AppointmentCancelled','AppointmentCompleted')",
                Integer.class,
                appointmentId))
        .isEqualTo(1);
  }

  @Test
  void concurrentBookingCanReuseCancelledSlotOnlyAfterCancellationCommits() throws Exception {
    submitBooking(
            "cancel-booking-race-source-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Cancelamento concorrente", "+5511999994329"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    installRescheduleUpdatePause(appointmentId);
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var cancellation =
          commands.submit(
              () ->
                  submitOutcome(
                      appointmentId, "cancel", "cancel-before-booking-" + UUID.randomUUID()));
      awaitUpdateInProgress();
      var newBooking = commands.submit(() -> reserve("booking-after-cancel-" + UUID.randomUUID()));
      assertThat(cancellation.get()).isEqualTo(200);
      assertThat(newBooking.get()).isTrue();
    } finally {
      removeRescheduleUpdatePause();
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CANCELLED_BY_PROVIDER");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment WHERE tenant_id = ? AND starts_at = ? AND status = 'CONFIRMED'",
                Integer.class,
                tenantId,
                java.time.OffsetDateTime.parse(requestedStart)))
        .isEqualTo(1);
  }

  @Test
  void concurrentBlockCanUseCancelledSlotOnlyAfterCancellationCommits() throws Exception {
    submitBooking(
            "cancel-block-race-source-" + UUID.randomUUID(),
            requestBody(
                serviceId, requestedStart, "Cancelamento antes de bloqueio", "+5511999994330"))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentIdForTenant();
    installRescheduleUpdatePause(appointmentId);
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var cancellation =
          commands.submit(
              () ->
                  submitOutcome(
                      appointmentId, "cancel", "cancel-before-block-" + UUID.randomUUID()));
      awaitUpdateInProgress();
      var block =
          commands.submit(() -> createBlockAt(java.time.OffsetDateTime.parse(requestedStart)));
      assertThat(cancellation.get()).isEqualTo(200);
      assertThat(block.get()).isTrue();
    } finally {
      removeRescheduleUpdatePause();
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("CANCELLED_BY_PROVIDER");
    assertThat(count("blocked_period", tenantId)).isEqualTo(1);
  }

  private int submitOutcome(UUID appointmentId, String action, String key) {
    try {
      return mvc.perform(
              post("/api/v1/admin/appointments/{id}/" + action, appointmentId)
                  .with(authentication(principalToken(actor)))
                  .with(csrf())
                  .header("Idempotency-Key", key)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"expectedVersion\":1}"))
          .andReturn()
          .getResponse()
          .getStatus();
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID appointmentIdForTenant() {
    return appJdbc.queryForObject(
        "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ? AND status = 'CONFIRMED' ORDER BY created_at DESC LIMIT 1",
        UUID.class,
        tenantId);
  }

  private int concurrentNoShowCorrection(CyclicBarrier barrier, UUID appointmentId, String reason)
      throws Exception {
    barrier.await();
    return mvc.perform(
            post("/api/v1/admin/appointments/{id}/correct-no-show", appointmentId)
                .with(authentication(principalToken(actor)))
                .with(csrf())
                .header("Idempotency-Key", "concurrent-no-show-correction-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        Map.of("expectedVersion", 2, "reason", reason))))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private UUID appointmentIdForClientName(String name) {
    return appJdbc.queryForObject(
        "SELECT appointment.appointment_id FROM agendamento.appointment appointment JOIN agendamento.client client USING (tenant_id, client_id) WHERE appointment.tenant_id = ? AND client.display_name = ? ORDER BY appointment.created_at DESC LIMIT 1",
        UUID.class,
        tenantId,
        name);
  }

  private UUID clientId(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT client_id FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        UUID.class,
        tenantId,
        appointmentId);
  }

  private void setAppointmentInterval(
      UUID appointmentId, Instant startsAt, Instant serviceEndsAt, Instant occupiedUntil) {
    migrationJdbc.update(
        "UPDATE agendamento.appointment SET starts_at = ?, service_ends_at = ?, occupied_until = ? WHERE tenant_id = ? AND appointment_id = ?",
        java.sql.Timestamp.from(startsAt),
        java.sql.Timestamp.from(serviceEndsAt),
        java.sql.Timestamp.from(occupiedUntil),
        tenantId,
        appointmentId);
  }

  private String persistedStatus(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT status || ':' || version FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        String.class,
        tenantId,
        appointmentId);
  }

  private int idempotencyRecordCount() {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private int countHistoryReason(UUID appointmentId, String reasonCode) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.appointment_history WHERE tenant_id = ? AND appointment_id = ? AND reason_code = ?",
        Integer.class,
        tenantId,
        appointmentId,
        reasonCode);
  }

  private ResultActions postOutcome(UUID appointmentId, String action, String key, long version)
      throws Exception {
    return mvc.perform(
        post("/api/v1/admin/appointments/{id}/" + action, appointmentId)
            .with(authentication(principalToken(actor)))
            .with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":" + version + "}"));
  }

  private void setAppointmentInPast(UUID appointmentId) {
    migrationJdbc.update(
        "WITH past AS (SELECT clock_timestamp() - interval '90 minutes' AS starts_at) UPDATE agendamento.appointment AS appointment SET starts_at = past.starts_at, service_ends_at = past.starts_at + interval '30 minutes', occupied_until = past.starts_at + interval '45 minutes' FROM past WHERE appointment.appointment_id = ?",
        appointmentId);
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
  void persistedRestrictionAllowsAdminBookingAndRecordsRestrictedResult() throws Exception {
    UUID restrictedClient = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Cliente com restrição', '+5511999996671')",
        restrictedClient,
        tenantId);
    Instant asOf = Instant.now();
    migrationJdbc.update(
        "INSERT INTO agendamento.client_no_show_policy (tenant_id, threshold, version) VALUES (?, 1, 1)",
        tenantId);
    UUID sourceAppointment =
        seedNoShowFacts(restrictedClient, asOf.minusSeconds(60), 1).getFirst().appointmentId();
    noShowRestrictionActivation.evaluateAfterNoShow(
        tenantId, restrictedClient, sourceAppointment, asOf);
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
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.client_booking_restriction WHERE tenant_id = ? AND client_id = ?",
                String.class,
                tenantId,
                restrictedClient))
        .isEqualTo("ACTIVE");
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
    Instant fixedNow = LocalDate.now(ZONE).atTime(10, 3).atZone(ZONE).toInstant();
    org.mockito.Mockito.when(clock.instant()).thenReturn(fixedNow);
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

  private UUID seedNoShowClient(String label) {
    UUID clientId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, ?, ?)",
        clientId,
        tenantId,
        label,
        "+5511999" + String.format("%06d", Math.floorMod(System.nanoTime(), 1_000_000)));
    return clientId;
  }

  private List<NoShowFactRef> seedNoShowFacts(UUID clientId, Instant firstOccurredAt, int count) {
    List<NoShowFactRef> facts = new java.util.ArrayList<>();
    for (int index = 0; index < count; index++) {
      UUID appointmentId = UUID.randomUUID();
      UUID initialHistoryId = UUID.randomUUID();
      UUID historyId = UUID.randomUUID();
      Instant occurredAt = firstOccurredAt.minusSeconds(index / 3L);
      Instant startsAt = occurredAt.minusSeconds(60 * 60L);
      Instant serviceEndsAt = startsAt.plusSeconds(30 * 60L);
      Instant occupiedUntil = serviceEndsAt.plusSeconds(15 * 60L);
      migrationJdbc.update(
          "INSERT INTO agendamento.appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'Cliente de teste', 'Consulta', 30, 15, 'NO_SHOW', ?, 2)",
          appointmentId,
          tenantId,
          clientId,
          serviceId,
          java.sql.Timestamp.from(startsAt),
          java.sql.Timestamp.from(serviceEndsAt),
          java.sql.Timestamp.from(occupiedUntil),
          ZONE.getId(),
          java.sql.Timestamp.from(startsAt));
      migrationJdbc.update(
          "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at) VALUES (?, ?, ?, NULL, 'CONFIRMED', 'PUBLIC', 'PUBLIC_BOOKING', 'APPOINTMENT_CREATED', ?)",
          initialHistoryId,
          tenantId,
          appointmentId,
          java.sql.Timestamp.from(startsAt));
      migrationJdbc.update(
          "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, version_before, version_after) VALUES (?, ?, ?, 'CONFIRMED', 'NO_SHOW', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_NO_SHOW', ?, 1, 2)",
          historyId,
          tenantId,
          appointmentId,
          java.sql.Timestamp.from(occurredAt));
      facts.add(new NoShowFactRef(historyId, appointmentId, occurredAt));
    }
    return facts;
  }

  private record NoShowFactRef(UUID historyId, UUID appointmentId, Instant occurredAt) {}

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
  void rescheduleRejectsInactiveCurrentServiceWithoutSuccessEffects() throws Exception {
    submitBooking(
            "reschedule-inactive-service-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Snapshot", "+5511999991840"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    setServiceStatus.execute(
        actor,
        serviceId,
        "deactivate-before-reschedule-" + UUID.randomUUID(),
        0,
        ServiceStatus.INACTIVE);
    long revisionBefore = currentScheduleRevision();
    int historyBefore = count("appointment_history", tenantId);
    int auditBefore = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxBefore = countByEvent("AppointmentRescheduled");
    int receiptsBefore = rescheduleReceiptCount();

    submitReschedule(
            appointmentId,
            "reschedule-inactive-rejected-" + UUID.randomUUID(),
            rescheduleBody(
                1,
                revisionBefore,
                bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("ADMIN_BOOKING_POLICY_REJECTED"));

    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at = ?::timestamptz AND version = 1 FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
                Boolean.class,
                java.time.OffsetDateTime.parse(requestedStart),
                tenantId,
                appointmentId))
        .isTrue();
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyBefore);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditBefore);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxBefore);
    assertThat(currentScheduleRevision()).isEqualTo(revisionBefore);
    assertThat(rescheduleReceiptCount()).isEqualTo(receiptsBefore);
  }

  @Test
  void rescheduleUsesCurrentServiceEligibilityButKeepsOriginalSnapshotAndReplay() throws Exception {
    submitBooking(
            "reschedule-snapshot-service-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Snapshot original", "+5511999991841"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    updateService.execute(
        actor,
        serviceId,
        "catalog-change-before-reschedule-" + UUID.randomUUID(),
        0,
        "Catálogo atualizado",
        null,
        60,
        30);
    long revision = currentScheduleRevision();
    String destination = bookingDate.atTime(9, 30).atZone(ZONE).toOffsetDateTime().toString();
    String key = "reschedule-current-active-service-" + UUID.randomUUID();
    String body = rescheduleBody(1, revision, destination);

    submitReschedule(appointmentId, key, body)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2));
    assertThat(
            appJdbc.queryForObject(
                "SELECT service_name_snapshot || ':' || service_duration_minutes || ':' || service_buffer_after_minutes || ':' || (occupied_until - starts_at) FROM agendamento.appointment WHERE appointment_id = ?",
                String.class,
                appointmentId))
        .isEqualTo("Consulta:30:15:00:45:00");
    int historyAfterSuccess = count("appointment_history", tenantId);
    int auditAfterSuccess = countByAction("APPOINTMENT_RESCHEDULED");
    int outboxAfterSuccess = countByEvent("AppointmentRescheduled");
    int receiptsAfterSuccess = rescheduleReceiptCount();
    setServiceStatus.execute(
        actor,
        serviceId,
        "deactivate-after-reschedule-" + UUID.randomUUID(),
        1,
        ServiceStatus.INACTIVE);

    submitReschedule(appointmentId, key, body)
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.version").value(2));
    assertThat(count("appointment_history", tenantId)).isEqualTo(historyAfterSuccess);
    assertThat(countByAction("APPOINTMENT_RESCHEDULED")).isEqualTo(auditAfterSuccess);
    assertThat(countByEvent("AppointmentRescheduled")).isEqualTo(outboxAfterSuccess);
    assertThat(rescheduleReceiptCount()).isEqualTo(receiptsAfterSuccess);
  }

  @Test
  void reschedulingMayOverlapItsOwnOldOccupiedInterval() throws Exception {
    submitBooking(
            "reschedule-own-overlap-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Overlap próprio", "+5511999991842"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    var oldInterval =
        appJdbc.queryForObject(
            "SELECT starts_at, occupied_until FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
            (rs, row) ->
                new java.time.OffsetDateTime[] {
                  rs.getObject(1, java.time.OffsetDateTime.class),
                  rs.getObject(2, java.time.OffsetDateTime.class)
                },
            tenantId,
            appointmentId);
    var newStart = bookingDate.atTime(9, 30).atZone(ZONE).toOffsetDateTime();
    var newOccupiedUntil = newStart.plusMinutes(45);
    long revision = currentScheduleRevision();

    submitReschedule(
            appointmentId,
            "reschedule-real-overlap-" + UUID.randomUUID(),
            rescheduleBody(1, revision, newStart.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.appointmentId").value(appointmentId.toString()))
        .andExpect(jsonPath("$.version").value(2));

    assertThat(oldInterval[0].toInstant()).isBefore(newOccupiedUntil.toInstant());
    assertThat(newStart.toInstant()).isBefore(oldInterval[1].toInstant());
    assertThat(
            appJdbc.queryForObject(
                "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ? AND starts_at = ? AND occupied_until = ? AND version = 2",
                UUID.class,
                tenantId,
                appointmentId,
                newStart,
                newOccupiedUntil))
        .isEqualTo(appointmentId);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment WHERE tenant_id = ? AND status = 'CONFIRMED' AND appointment_id <> ? AND tstzrange(starts_at, occupied_until, '[)') && tstzrange(?::timestamptz, ?::timestamptz, '[)')",
                Integer.class,
                tenantId,
                appointmentId,
                newStart,
                newOccupiedUntil))
        .isZero();
  }

  @Test
  void historyConstraintRejectsUnknownAndInvalidRescheduleCombinations() throws Exception {
    submitBooking(
            "reschedule-history-constraint-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "History", "+5511999991843"))
        .andExpect(status().isCreated());
    UUID appointmentId =
        appJdbc.queryForObject(
            "SELECT appointment_id FROM agendamento.appointment WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = ? AND reason_code = 'APPOINTMENT_CREATED' AND previous_starts_at IS NULL AND previous_occupied_until IS NULL AND new_starts_at IS NULL AND new_occupied_until IS NULL AND version_before IS NULL AND version_after IS NULL",
                Integer.class,
                appointmentId))
        .isEqualTo(1);

    var start = java.time.OffsetDateTime.parse(requestedStart);
    var end = start.plusMinutes(45);
    assertInvalidRescheduleHistory(appointmentId, start, null, start, end, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, start, null, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, null, start, null, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, null, end, start, end, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, null, end, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, start, end, null, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, start, end, 2L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, start, start, end, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, start, start, 1L, 2L);
    assertInvalidRescheduleHistory(appointmentId, start, end, start, end, 1L, 3L);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                migrationJdbc.update(
                    "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until, version_before, version_after) VALUES (?, ?, ?, 'PENDING', 'CONFIRMED', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_RESCHEDULED', clock_timestamp(), ?, ?, ?, ?, 1, 2)",
                    UUID.randomUUID(),
                    tenantId,
                    appointmentId,
                    start,
                    end,
                    start,
                    end))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                migrationJdbc.update(
                    "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until, version_before, version_after) VALUES (?, ?, ?, 'CONFIRMED', 'CONFIRMED', 'ADMIN', 'TENANT_ADMIN', NULL, clock_timestamp(), ?, ?, ?, ?, 1, 2)",
                    UUID.randomUUID(),
                    tenantId,
                    appointmentId,
                    start,
                    end,
                    start,
                    end))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(count("appointment_history", tenantId)).isEqualTo(1);

    String destination =
        bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString();
    submitReschedule(
            appointmentId,
            "reschedule-valid-history-" + UUID.randomUUID(),
            rescheduleBody(1, currentScheduleRevision(), destination))
        .andExpect(status().isOk());
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.appointment_history WHERE appointment_id = ? AND reason_code = 'APPOINTMENT_RESCHEDULED' AND previous_starts_at IS NOT NULL AND previous_occupied_until > previous_starts_at AND new_starts_at IS NOT NULL AND new_occupied_until > new_starts_at AND version_before = 1 AND version_after = 2",
                Integer.class,
                appointmentId))
        .isEqualTo(1);
  }

  @Test
  void concurrentRescheduleWithStaleVersionLoses() throws Exception {
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
    installRescheduleUpdatePause(appointmentId);
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
    installRescheduleUpdatePause(appointmentId);
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var reschedule =
          commands.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-lock-winner-" + UUID.randomUUID(),
                          rescheduleBody(1, revision, destination.toString()))
                      .andReturn());
      awaitUpdateInProgress();
      var block = commands.submit(() -> createBlockAt(destination));
      var rescheduleResponse = reschedule.get().getResponse();
      assertThat(rescheduleResponse.getStatus())
          .as(rescheduleResponse.getContentAsString())
          .isEqualTo(200);
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
  void clientCancellationWinningSharedTenantLockRejectsConcurrentReschedule() throws Exception {
    String phone = "+5511999992197";
    submitBooking(
            "client-cancel-reschedule-race-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Corrida cliente", phone))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentForPhone(phone);
    String clientCookie = verifiedClientCookie(phone);
    long revision = currentScheduleRevision();
    String destination =
        bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString();
    installClientCommandPause(appointmentId, "cancel");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var cancellation =
          commands.submit(
              () -> submitClientCancellation(appointmentId, clientCookie, 1).andReturn());
      awaitAppointmentUpdateInProgress();
      var reschedule =
          commands.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "client-cancel-race-admin-" + UUID.randomUUID(),
                          rescheduleBody(1, revision, destination))
                      .andReturn());
      assertThat(cancellation.get().getResponse().getStatus()).isEqualTo(200);
      assertThat(reschedule.get().getResponse().getStatus()).isEqualTo(409);
    } finally {
      removeClientCommandPause();
    }
    assertThat(statusOf(appointmentId)).isEqualTo("CANCELLED_BY_CLIENT");
    assertThat(versionOf(appointmentId)).isEqualTo(2);
    assertThat(countHistory(appointmentId)).isEqualTo(2);
    assertThat(countAuditForTarget(appointmentId, "APPOINTMENT_CANCELLED_BY_CLIENT")).isEqualTo(1);
    assertThat(countAuditForTarget(appointmentId, "APPOINTMENT_RESCHEDULED")).isZero();
    assertThat(countOutboxForAggregate(appointmentId, "AppointmentCancelled")).isEqualTo(1);
    assertThat(countOutboxForAggregate(appointmentId, "AppointmentRescheduled")).isZero();
  }

  @Test
  void rescheduleWinningSharedTenantLockRejectsConcurrentClientCancellation() throws Exception {
    String phone = "+5511999992198";
    submitBooking(
            "reschedule-client-cancel-race-" + UUID.randomUUID(),
            requestBody(serviceId, requestedStart, "Corrida remarcação", phone))
        .andExpect(status().isCreated());
    UUID appointmentId = appointmentForPhone(phone);
    String clientCookie = verifiedClientCookie(phone);
    long revision = currentScheduleRevision();
    String destination =
        bookingDate.plusWeeks(1).atTime(9, 0).atZone(ZONE).toOffsetDateTime().toString();
    installClientCommandPause(appointmentId, "reschedule");
    try (ExecutorService commands = Executors.newFixedThreadPool(2)) {
      var reschedule =
          commands.submit(
              () ->
                  submitReschedule(
                          appointmentId,
                          "reschedule-client-race-admin-" + UUID.randomUUID(),
                          rescheduleBody(1, revision, destination))
                      .andReturn());
      awaitAppointmentUpdateInProgress();
      var cancellation =
          commands.submit(
              () -> submitClientCancellation(appointmentId, clientCookie, 1).andReturn());
      assertThat(reschedule.get().getResponse().getStatus()).isEqualTo(200);
      assertThat(cancellation.get().getResponse().getStatus()).isEqualTo(409);
    } finally {
      removeClientCommandPause();
    }
    assertThat(statusOf(appointmentId)).isEqualTo("CONFIRMED");
    assertThat(versionOf(appointmentId)).isEqualTo(2);
    assertThat(countHistory(appointmentId)).isEqualTo(2);
    assertThat(countAuditForTarget(appointmentId, "APPOINTMENT_RESCHEDULED")).isEqualTo(1);
    assertThat(countAuditForTarget(appointmentId, "APPOINTMENT_CANCELLED_BY_CLIENT")).isZero();
    assertThat(countOutboxForAggregate(appointmentId, "AppointmentRescheduled")).isEqualTo(1);
    assertThat(countOutboxForAggregate(appointmentId, "AppointmentCancelled")).isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT starts_at FROM agendamento.appointment WHERE appointment_id = ?",
                java.time.OffsetDateTime.class,
                appointmentId))
        .isEqualTo(java.time.OffsetDateTime.parse(destination));
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

  private ResultActions submitClientCancellation(UUID id, String cookie, long expectedVersion)
      throws Exception {
    return mvc.perform(
        post("/api/v1/client/appointments/{id}/cancel", id)
            .with(csrf())
            .header("Cookie", cookie)
            .header("Idempotency-Key", "client-race-cancel-" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":" + expectedVersion + "}"));
  }

  private String verifiedClientCookie(String phone) throws Exception {
    MvcResult requested =
        mvc.perform(
                post("/api/v1/public/" + slug + "/access-challenges")
                    .with(csrf())
                    .header("Idempotency-Key", "client-race-challenge-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"phone\":\"" + phone + "\"}"))
            .andExpect(status().isAccepted())
            .andReturn();
    UUID challengeId =
        UUID.fromString(
            objectMapper
                .readTree(requested.getResponse().getContentAsString())
                .get("challengeId")
                .asText());
    String code = clientAccessCodes.get(challengeId);
    assertThat(code).isNotBlank();
    MvcResult verified =
        mvc.perform(
                post("/api/v1/public/" + slug + "/access-challenges/" + challengeId + "/verify")
                    .with(csrf())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + code + "\"}"))
            .andExpect(status().isNoContent())
            .andReturn();
    String setCookie = verified.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).startsWith("AGDCLIENT=");
    return setCookie.substring(0, setCookie.indexOf(';'));
  }

  private UUID appointmentForPhone(String phone) {
    return appJdbc.queryForObject(
        "SELECT appointment_id FROM agendamento.appointment a JOIN agendamento.client c USING (tenant_id, client_id) WHERE a.tenant_id = ? AND c.normalized_phone = ?",
        UUID.class,
        tenantId,
        phone);
  }

  private void installClientCommandPause(UUID appointmentId, String winningCommand) {
    String predicate =
        winningCommand.equals("cancel")
            ? "NEW.status = 'CANCELLED_BY_CLIENT'"
            : "NEW.starts_at IS DISTINCT FROM OLD.starts_at";
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.pause_client_command_race() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.appointment_id = '"
            + appointmentId
            + "'::uuid AND "
            + predicate
            + " THEN PERFORM pg_sleep(1); END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER pause_client_command_race BEFORE UPDATE ON agendamento.appointment FOR EACH ROW EXECUTE FUNCTION agendamento.pause_client_command_race()");
  }

  private void awaitAppointmentUpdateInProgress() throws InterruptedException {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadline) {
      Integer active =
          migrationJdbc.queryForObject(
              "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND wait_event = 'PgSleep' AND query ILIKE '%UPDATE agendamento.appointment%'",
              Integer.class);
      if (active != null && active > 0) return;
      Thread.sleep(20);
    }
    throw new IllegalStateException("O comando não chegou ao UPDATE PostgreSQL sob lock");
  }

  private void removeClientCommandPause() {
    migrationJdbc.execute("DROP TRIGGER pause_client_command_race ON agendamento.appointment");
    migrationJdbc.execute("DROP FUNCTION agendamento.pause_client_command_race()");
  }

  private int countAuditForTarget(UUID appointmentId, String action) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.audit_event WHERE target_id = ?::uuid AND action = ?",
        Integer.class,
        appointmentId,
        action);
  }

  private int countOutboxForAggregate(UUID appointmentId, String eventType) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.outbox_event WHERE aggregate_id = ? AND event_type = ?",
        Integer.class,
        appointmentId,
        eventType);
  }

  private String statusOf(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT status FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        String.class,
        tenantId,
        appointmentId);
  }

  private long versionOf(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT version FROM agendamento.appointment WHERE tenant_id = ? AND appointment_id = ?",
        Long.class,
        tenantId,
        appointmentId);
  }

  private int countHistory(UUID appointmentId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.appointment_history WHERE tenant_id = ? AND appointment_id = ?",
        Integer.class,
        tenantId,
        appointmentId);
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

  private void installRescheduleUpdatePause(UUID appointmentId) {
    migrationJdbc.execute(
        "CREATE FUNCTION agendamento.pause_reschedule_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.appointment_id = '"
            + appointmentId
            + "'::uuid THEN PERFORM pg_sleep(0.75); END IF; RETURN NEW; END $$");
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
              "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND wait_event = 'PgSleep' AND query ILIKE '%UPDATE agendamento.appointment%'",
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

  private int rescheduleReceiptCount() {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'appointments.admin.reschedule.v1'",
        Integer.class,
        tenantId);
  }

  private void assertInvalidRescheduleHistory(
      UUID appointmentId,
      java.time.OffsetDateTime previousStartsAt,
      java.time.OffsetDateTime previousOccupiedUntil,
      java.time.OffsetDateTime newStartsAt,
      java.time.OffsetDateTime newOccupiedUntil,
      Long versionBefore,
      Long versionAfter) {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                migrationJdbc.update(
                    "INSERT INTO agendamento.appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until, version_before, version_after) VALUES (?, ?, ?, 'CONFIRMED', 'CONFIRMED', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_RESCHEDULED', clock_timestamp(), ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    tenantId,
                    appointmentId,
                    previousStartsAt,
                    previousOccupiedUntil,
                    newStartsAt,
                    newOccupiedUntil,
                    versionBefore,
                    versionAfter))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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
