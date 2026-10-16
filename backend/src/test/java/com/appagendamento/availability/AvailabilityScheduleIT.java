package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.availability.application.AppointmentOccupancyQuery;
import com.appagendamento.availability.application.AvailabilityReadTransaction;
import com.appagendamento.availability.application.AvailabilityServiceNotFoundException;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.availability.application.InvalidAvailabilityWindowException;
import com.appagendamento.availability.application.ManageBlockedPeriods;
import com.appagendamento.availability.application.ManageScheduleExceptions;
import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.availability.application.ScheduleRepository;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

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
class AvailabilityScheduleIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private ReplaceWeeklySchedule replaceWeeklySchedule;
  @Autowired private ReplaceAvailabilityPolicy replaceAvailabilityPolicy;
  @Autowired private CreateService createService;
  @Autowired private ServiceAvailabilityQuery serviceAvailabilityQuery;
  @Autowired private ScheduleRepository scheduleRepository;
  @Autowired private AvailabilityReadTransaction availabilityReadTransaction;
  @Autowired private ManageBlockedPeriods manageBlockedPeriods;
  @Autowired private ManageScheduleExceptions manageScheduleExceptions;
  @Autowired private MockMvc mvc;
  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private UUID adminId;
  private String subject;
  private AuthenticatedAdmin actor;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void prepareTenant() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    adminId = UUID.randomUUID();
    subject = UUID.randomUUID().toString();
    String slug = "schedule-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        tenantId,
        "Schedule test",
        slug,
        "America/Sao_Paulo");
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    actor = authenticatedAdmin(tenantId, adminId, subject);
  }

  @Test
  void readsUnconfiguredSeparatelyFromAnExplicitlyClosedWeekAndPolicy() throws Exception {
    mvc.perform(get("/api/v1/admin/schedule/weekly-periods").with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.periods").isEmpty());
    mvc.perform(get("/api/v1/admin/availability-policy").with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.slotStepMinutes").doesNotExist());

    replaceWeeklySchedule.execute(actor, "closed-" + UUID.randomUUID(), 0, List.of());

    mvc.perform(get("/api/v1/admin/schedule/weekly-periods").with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.periods").isEmpty());
  }

  @Test
  void availabilityReadsRunInPostgresRepeatableReadTransaction() {
    String isolation =
        availabilityReadTransaction.execute(
            () -> appJdbc.queryForObject("SHOW transaction_isolation", String.class));

    assertThat(isolation).isEqualTo("repeatable read");
  }

  @Test
  void calculatesFromPostgresSourcesWithClockAndTestOccupancyWithoutWriting() {
    LocalDate date = LocalDate.of(2026, 10, 19);
    replaceWeeklySchedule.execute(
        actor,
        "availability-calc-week-" + UUID.randomUUID(),
        0,
        List.of(new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.of(9, 0), LocalTime.NOON)));
    replaceAvailabilityPolicy.execute(
        actor, "availability-calc-policy-" + UUID.randomUUID(), 0, 30, 1, 7, 2, 2);
    var service =
        createService
            .execute(
                actor,
                "availability-calc-service-" + UUID.randomUUID(),
                "Availability service",
                null,
                30,
                15,
                ServiceStatus.ACTIVE)
            .value();
    var block =
        manageBlockedPeriods
            .create(
                actor,
                "availability-calc-block-" + UUID.randomUUID(),
                "2026-10-19T09:45:00-03:00",
                "2026-10-19T10:00:00-03:00",
                "test fixture")
            .value();
    Instant occupiedStart = Instant.parse("2026-10-19T13:15:00Z");
    AppointmentOccupancyQuery testOccupancies =
        (requestedTenant, from, to) -> {
          assertThat(requestedTenant).isEqualTo(tenantId);
          assertThat(from).isEqualTo(date.atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant());
          assertThat(to)
              .isEqualTo(date.plusDays(1).atStartOfDay(ZoneId.of("America/Sao_Paulo")).toInstant());
          return List.of(
              new com.appagendamento.availability.domain.OccupiedInterval(
                  occupiedStart, occupiedStart.plusSeconds(15 * 60L)));
        };
    CalculateAvailabilityForDateRange calculation =
        new CalculateAvailabilityForDateRange(
            availabilityReadTransaction,
            scheduleRepository,
            serviceAvailabilityQuery,
            testOccupancies,
            Clock.fixed(Instant.parse("2026-10-19T11:00:00Z"), ZoneId.of("UTC")));
    int auditBefore = countForTenant("audit_event");
    int outboxBefore = countForTenant("outbox_event");

    var result = calculation.execute(tenantId, service.serviceId(), date, date);

    assertThat(result.days()).hasSize(1);
    assertThat(result.days().getFirst().date()).isEqualTo(date);
    assertThat(result.timeZone()).isEqualTo("America/Sao_Paulo");
    assertThat(localStarts(result.days().getFirst().slots()))
        .containsExactly("09:00", "10:30", "11:00");
    assertThat(result.days().getFirst().slots().getFirst().startsAt().getOffset().getId())
        .isEqualTo("-03:00");
    assertThat(countForTenant("audit_event")).isEqualTo(auditBefore);
    assertThat(countForTenant("outbox_event")).isEqualTo(outboxBefore);

    manageBlockedPeriods.cancel(
        actor,
        "availability-calc-cancel-" + UUID.randomUUID(),
        block.blockId(),
        block.version(),
        "block ended");
    var restoredByBlockCancellation =
        calculation.execute(tenantId, service.serviceId(), date, date);
    assertThat(localStarts(restoredByBlockCancellation.days().getFirst().slots()))
        .containsExactly("09:00", "09:30", "10:30", "11:00");

    var special =
        manageScheduleExceptions
            .create(
                actor,
                "availability-calc-special-" + UUID.randomUUID(),
                date,
                ScheduleExceptionKind.SPECIAL,
                List.of(new SpecialPeriod(LocalTime.of(13, 0), LocalTime.of(14, 0))),
                null)
            .value();
    var specialResult = calculation.execute(tenantId, service.serviceId(), date, date);
    assertThat(localStarts(specialResult.days().getFirst().slots())).containsExactly("13:00");
    manageScheduleExceptions.remove(
        actor,
        "availability-calc-remove-special-" + UUID.randomUUID(),
        special.exceptionId(),
        special.version());
    var restoredByExceptionRemoval = calculation.execute(tenantId, service.serviceId(), date, date);
    assertThat(localStarts(restoredByExceptionRemoval.days().getFirst().slots()))
        .containsExactly("09:00", "09:30", "10:30", "11:00");
  }

  private static List<String> localStarts(
      List<com.appagendamento.availability.application.AvailabilityDateResult.AvailabilitySlotView>
          slots) {
    return slots.stream().map(slot -> slot.startsAt().toLocalTime().toString()).toList();
  }

  @Test
  void dateRangeIsInclusiveLimitedTo31DatesAndOutOfHorizonDatesRemainEmpty() {
    LocalDate first = LocalDate.of(2026, 10, 16);
    replaceWeeklySchedule.execute(
        actor,
        "range-week-" + UUID.randomUUID(),
        0,
        Arrays.stream(DayOfWeek.values())
            .map(day -> new WeeklyPeriod(day, LocalTime.of(9, 0), LocalTime.NOON))
            .toList());
    replaceAvailabilityPolicy.execute(
        actor, "range-policy-" + UUID.randomUUID(), 0, 30, 1, 7, 2, 2);
    CountingClock countingClock = new CountingClock(Instant.parse("2026-10-16T12:00:00Z"));
    var calculator =
        new CalculateAvailabilityForDateRange(
            availabilityReadTransaction,
            scheduleRepository,
            serviceAvailabilityQuery,
            (requestedTenant, from, to) -> List.of(),
            countingClock);
    UUID serviceId =
        createService
            .execute(
                actor,
                "range-service-" + UUID.randomUUID(),
                "Range service",
                null,
                30,
                0,
                ServiceStatus.ACTIVE)
            .value()
            .serviceId();
    manageScheduleExceptions.create(
        actor,
        "range-closed-outside-horizon-" + UUID.randomUUID(),
        first.plusDays(7),
        ScheduleExceptionKind.CLOSED,
        List.of(),
        null);

    var oneDate = calculator.execute(tenantId, serviceId, first, first);
    assertThat(oneDate.days()).hasSize(1).extracting(day -> day.date()).containsExactly(first);
    var thirtyOneDates = calculator.execute(tenantId, serviceId, first, first.plusDays(30));
    assertThat(thirtyOneDates.days()).hasSize(31);
    assertThat(thirtyOneDates.configured()).isTrue();
    assertThat(thirtyOneDates.days().get(6).date()).isEqualTo(LocalDate.of(2026, 10, 22));
    assertThat(thirtyOneDates.days().get(6).slots()).isNotEmpty();
    assertThat(thirtyOneDates.days().get(7).date()).isEqualTo(LocalDate.of(2026, 10, 23));
    assertThat(thirtyOneDates.days().get(7).slots()).isEmpty();
    assertThat(thirtyOneDates.days().get(7).explicitlyClosed()).isTrue();
    assertThat(thirtyOneDates.days().get(3).slots()).isNotEmpty();
    assertThat(countingClock.calls()).isEqualTo(2);
    assertThatThrownBy(() -> calculator.execute(tenantId, serviceId, first, first.plusDays(31)))
        .isInstanceOf(InvalidAvailabilityWindowException.class);
    assertThatThrownBy(() -> calculator.execute(tenantId, serviceId, first.plusDays(1), first))
        .isInstanceOf(InvalidAvailabilityWindowException.class);
  }

  @Test
  void rejectsInactiveServiceForAvailabilityInsteadOfReturningAnEmptyOffer() {
    var service =
        createService
            .execute(
                actor,
                "inactive-availability-" + UUID.randomUUID(),
                "Inactive availability",
                null,
                30,
                0,
                ServiceStatus.ACTIVE)
            .value();
    migrationJdbc.update(
        "UPDATE agendamento.service SET status = 'INACTIVE' WHERE tenant_id = ? AND service_id = ?",
        tenantId,
        service.serviceId());
    var calculator =
        new CalculateAvailabilityForDateRange(
            availabilityReadTransaction,
            scheduleRepository,
            serviceAvailabilityQuery,
            (requestedTenant, from, to) -> List.of(),
            Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneId.of("UTC")));

    assertThatThrownBy(
            () ->
                calculator.execute(
                    tenantId,
                    service.serviceId(),
                    LocalDate.of(2026, 10, 16),
                    LocalDate.of(2026, 10, 16)))
        .isInstanceOf(AvailabilityServiceNotFoundException.class);
  }

  private static final class CountingClock extends Clock {
    private final Instant instant;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls.incrementAndGet();
      return instant;
    }

    private int calls() {
      return calls.get();
    }
  }

  @Test
  void replacesScheduleAtomicallyCanonicalizesReplayAndRejectsInvalidSchedule() throws Exception {
    String key = "weekly-" + UUID.randomUUID();
    String body =
        """
        {"expectedVersion":0,"periods":[
          {"weekday":"TUESDAY","localStart":"09:00","localEnd":"12:00"},
          {"weekday":"MONDAY","localStart":"13:00","localEnd":"17:00"},
          {"weekday":"MONDAY","localStart":"09:00","localEnd":"12:00"}]}
        """;
    var request =
        put("/api/v1/admin/schedule/weekly-periods")
            .with(authentication(token(actor)))
            .with(csrf())
            .header("Idempotency-Key", key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(body);
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.periods[0].weekday").value("MONDAY"))
        .andExpect(jsonPath("$.periods[1].weekday").value("MONDAY"));

    String reordered =
        """
        {"expectedVersion":0,"periods":[
          {"weekday":"MONDAY","localStart":"09:00","localEnd":"12:00"},
          {"weekday":"TUESDAY","localStart":"09:00","localEnd":"12:00"},
          {"weekday":"MONDAY","localStart":"13:00","localEnd":"17:00"}]}
        """;
    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(reordered))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "same-value-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(reordered.replace("\"expectedVersion\":0", "\"expectedVersion\":1")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"periods":[
                      {"weekday":"MONDAY","localStart":"09:00","localEnd":"12:00"},
                      {"weekday":"MONDAY","localStart":"11:00","localEnd":"13:00"}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "overnight-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"periods":[
                      {"weekday":"MONDAY","localStart":"23:45","localEnd":"00:15"}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "unaligned-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"periods":[
                      {"weekday":"MONDAY","localStart":"09:10","localEnd":"12:00"}]}
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "aligned-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"periods":[
                      {"weekday":"MONDAY","localStart":"09:15","localEnd":"12:45"}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.periods[0].localStart").value("09:15"));

    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.weekly_period WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_UPDATED")).isEqualTo(2);
    assertThat(count("outbox_event", "ScheduleChanged")).isEqualTo(2);
    assertThat(count("idempotency_record", "availability.weekly-schedule.replace.v1")).isEqualTo(3);
  }

  @Test
  void replacesPolicyByDocumentedOptionsAndPersistsAfterReload() throws Exception {
    String key = "policy-" + UUID.randomUUID();
    String body =
        """
        {"expectedVersion":0,"slotStepMinutes":30,"minimumNoticeHours":12,
         "bookingHorizonDays":30,"cancellationCutoffHours":4,"reminderOffsetHours":6}
        """;
    mvc.perform(
            put("/api/v1/admin/availability-policy")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.slotStepMinutes").value(30))
        .andExpect(jsonPath("$.minimumNoticeHours").value(12));
    mvc.perform(get("/api/v1/admin/availability-policy").with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.bookingHorizonDays").value(30))
        .andExpect(jsonPath("$.cancellationCutoffHours").value(4))
        .andExpect(jsonPath("$.reminderOffsetHours").value(6));

    mvc.perform(
            put("/api/v1/admin/availability-policy")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-policy-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"slotStepMinutes":45,"minimumNoticeHours":12,
                     "bookingHorizonDays":30,"cancellationCutoffHours":4,"reminderOffsetHours":6}
                    """))
        .andExpect(status().isBadRequest());

    mvc.perform(
            put("/api/v1/admin/availability-policy")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-shape-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

    assertThat(count("audit_event", "AVAILABILITY_POLICY_UPDATED")).isEqualTo(1);
    assertThat(count("outbox_event", "AvailabilityPolicyChanged")).isEqualTo(1);
  }

  @Test
  void exceptionLifecycleUsesTenantZoneCasIdempotencyAndAtomicAuditOutbox() throws Exception {
    LocalDate date = java.time.LocalDate.now().plusDays(40);
    String closedBody =
        "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[],\"reason\":\"Feriado\"}"
            .formatted(date);
    String key = "exception-create-" + UUID.randomUUID();
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(closedBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("CLOSED"))
        .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"));
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(closedBody))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(closedBody.replace("Feriado", "Outro motivo")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    mvc.perform(
            get("/api/v1/admin/schedule/exceptions")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
        .andExpect(jsonPath("$.exceptions[0].kind").value("CLOSED"));
    UUID exceptionId =
        appJdbc.queryForObject(
            "SELECT exception_id FROM agendamento.schedule_exception WHERE tenant_id = ? AND local_date = ?",
            UUID.class,
            tenantId,
            date);
    String invalid =
        "{\"localDate\":\"%s\",\"kind\":\"SPECIAL\",\"periods\":[{\"localStart\":\"09:10\",\"localEnd\":\"12:00\"}],\"expectedVersion\":1}"
            .formatted(date);
    mvc.perform(
            patch("/api/v1/admin/schedule/exceptions/{id}", exceptionId)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-exception-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalid))
        .andExpect(status().isBadRequest());
    String special =
        "{\"localDate\":\"%s\",\"kind\":\"SPECIAL\",\"periods\":[{\"localStart\":\"09:00\",\"localEnd\":\"10:00\"},{\"localStart\":\"10:00\",\"localEnd\":\"11:00\"}],\"expectedVersion\":1}"
            .formatted(date);
    mvc.perform(
            patch("/api/v1/admin/schedule/exceptions/{id}", exceptionId)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "exception-update-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(special))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.kind").value("SPECIAL"))
        .andExpect(jsonPath("$.periods.length()").value(2))
        .andExpect(jsonPath("$.periods[0].localStart").value("09:00"))
        .andExpect(jsonPath("$.periods[1].localEnd").value("11:00"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception_period WHERE exception_id = ?",
                Integer.class,
                exceptionId))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '10:30', '11:30')",
                    tenantId,
                    exceptionId))
        .isInstanceOf(DataIntegrityViolationException.class);
    mvc.perform(
            patch("/api/v1/admin/schedule/exceptions/{id}", exceptionId)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "stale-exception-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(special))
        .andExpect(status().isConflict());
    mvc.perform(
            delete("/api/v1/admin/schedule/exceptions/{id}", exceptionId)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "exception-remove-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.removed").value(true));
    mvc.perform(
            get("/api/v1/admin/schedule/exceptions")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exceptions").isEmpty());
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_UPDATED")).isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_REMOVED")).isEqualTo(1);
    assertThat(count("outbox_event", "ScheduleChanged")).isEqualTo(3);
  }

  @Test
  void specialExceptionPersistsExplicitDstOccurrenceAndReturnsActionableValidation()
      throws Exception {
    LocalDate fallBackDate = LocalDate.parse("2026-11-01");
    migrationJdbc.update(
        "UPDATE agendamento.tenant SET timezone = 'America/New_York' WHERE tenant_id = ?",
        tenantId);
    String missingOffset =
        """
        {"localDate":"2026-11-01","kind":"SPECIAL","periods":[{"localStart":"01:15","localEnd":"02:15"}]}
        """;
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "ambiguous-special-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(missingOffset))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_LOCAL_TIME"))
        .andExpect(
            jsonPath("$.error.message")
                .value(org.hamcrest.Matchers.containsString("offset válido")));
    String selectedOccurrenceKey = "selected-special-" + UUID.randomUUID();
    String selectedOccurrenceBody =
        """
        {"localDate":"2026-11-01","kind":"SPECIAL","periods":[{"localStart":"01:15","localEnd":"02:15","localStartOffset":"-04:00"}]}
        """;
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", selectedOccurrenceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(selectedOccurrenceBody))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periods[0].localStart").value("01:15"))
        .andExpect(jsonPath("$.periods[0].localStartOffset").value("-04:00"))
        .andExpect(
            jsonPath("$.periods[0].localEndOffset").value(org.hamcrest.Matchers.nullValue()));
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", selectedOccurrenceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(selectedOccurrenceBody))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", selectedOccurrenceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(selectedOccurrenceBody.replace("-04:00", "-05:00")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT local_start_offset_seconds FROM agendamento.schedule_exception_period WHERE tenant_id = ? AND exception_id = (SELECT exception_id FROM agendamento.schedule_exception WHERE tenant_id = ? AND local_date = ?)",
                Integer.class,
                tenantId,
                tenantId,
                fallBackDate))
        .isEqualTo(-14_400);
  }

  @Test
  void postgresRejectsInstantOverlapAcrossAnAmbiguousLocalBoundary() {
    migrationJdbc.update(
        "UPDATE agendamento.tenant SET timezone = 'America/New_York' WHERE tenant_id = ?",
        tenantId);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(appJdbc.getDataSource()));
    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      UUID exceptionId = UUID.randomUUID();
                      appJdbc.update(
                          "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind) VALUES (?, ?, '2026-11-01', 'SPECIAL')",
                          exceptionId,
                          tenantId);
                      appJdbc.update(
                          "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end, local_start_offset_seconds, local_end_offset_seconds) VALUES (?, ?, '01:00', '01:30', -14400, -18000)",
                          tenantId,
                          exceptionId);
                      appJdbc.update(
                          "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end, local_start_offset_seconds) VALUES (?, ?, '01:30', '02:00', -14400)",
                          tenantId,
                          exceptionId);
                    }))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void postgresRejectsAmbiguousBoundaryWithoutExplicitOffset() {
    migrationJdbc.update(
        "UPDATE agendamento.tenant SET timezone = 'America/New_York' WHERE tenant_id = ?",
        tenantId);
    UUID exceptionId = UUID.randomUUID();
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(appJdbc.getDataSource()));
    transaction.executeWithoutResult(
        status -> {
          appJdbc.update(
              "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind) VALUES (?, ?, '2026-11-01', 'SPECIAL')",
              exceptionId,
              tenantId);
          appJdbc.update(
              "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '09:00', '10:00')",
              tenantId,
              exceptionId);
        });

    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '01:15', '02:00')",
                    tenantId,
                    exceptionId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void concurrentInstantOverlappingPeriodsHaveOnePostgresWinner() throws Exception {
    migrationJdbc.update(
        "UPDATE agendamento.tenant SET timezone = 'America/New_York' WHERE tenant_id = ?",
        tenantId);
    UUID exceptionId = UUID.randomUUID();
    TransactionTemplate setup =
        new TransactionTemplate(new DataSourceTransactionManager(appJdbc.getDataSource()));
    setup.executeWithoutResult(
        status -> {
          appJdbc.update(
              "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind) VALUES (?, ?, '2026-11-01', 'SPECIAL')",
              exceptionId,
              tenantId);
          appJdbc.update(
              "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '08:00', '08:15')",
              tenantId,
              exceptionId);
        });

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first =
          writers.submit(
              () ->
                  insertAmbiguousPeriodAfterBarrier(
                      ready, start, exceptionId, "01:00", "01:30", -14_400, -18_000));
      Future<Boolean> second =
          writers.submit(
              () ->
                  insertAmbiguousPeriodAfterBarrier(
                      ready, start, exceptionId, "01:30", "02:00", -14_400, null));
      ready.await();
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception_period WHERE exception_id = ?",
                Integer.class,
                exceptionId))
        .isEqualTo(2);
  }

  @Test
  void scheduleChangedRevisionIsMonotonicAcrossWeeklyAndExceptionMutations() throws Exception {
    replaceWeeklySchedule.execute(
        actor, "revision-weekly-" + UUID.randomUUID(), 0, List.of(period("09:00", "12:00")));
    var date = LocalDate.now().plusDays(60);
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "revision-exception-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[]}".formatted(date)))
        .andExpect(status().isOk());
    assertThat(
            appJdbc.queryForList(
                "SELECT aggregate_version FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'ScheduleChanged' ORDER BY aggregate_version",
                Long.class,
                tenantId))
        .containsExactly(1L, 2L);
  }

  @Test
  void concurrentExceptionCreationForSameTenantDateHasOneWinner() throws Exception {
    LocalDate date = LocalDate.now().plusDays(70);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first = writers.submit(() -> createExceptionAfterBarrier(ready, start, date));
      Future<Integer> second =
          writers.submit(() -> createExceptionAfterBarrier(ready, start, date));
      ready.await();
      start.countDown();
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 409);
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception WHERE tenant_id = ? AND local_date = ?",
                Integer.class,
                tenantId,
                date))
        .isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isEqualTo(1);
  }

  @Test
  void exceptionAuditFailureRollsBackMutationIdempotencyAndOutbox() {
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_exception_audit() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN
          IF NEW.action = 'SCHEDULE_EXCEPTION_CREATED' THEN
            RAISE EXCEPTION 'injected exception audit failure' USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END $$
        """);
    migrationJdbc.execute(
        "CREATE TRIGGER reject_exception_audit BEFORE INSERT ON agendamento.audit_event "
            + "FOR EACH ROW EXECUTE FUNCTION agendamento.reject_exception_audit()");
    try {
      var date = LocalDate.now().plusDays(80);
      assertThatThrownBy(
              () ->
                  mvc.perform(
                      post("/api/v1/admin/schedule/exceptions")
                          .with(authentication(token(actor)))
                          .with(csrf())
                          .header("Idempotency-Key", "audit-exception-" + UUID.randomUUID())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[]}"
                                  .formatted(date))))
          .isInstanceOf(Exception.class);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    } finally {
      migrationJdbc.execute("DROP TRIGGER reject_exception_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_exception_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isZero();
    assertThat(count("outbox_event", "ScheduleChanged")).isZero();
    assertThat(count("idempotency_record", "availability.schedule-exception.create.v1")).isZero();
  }

  @Test
  void exceptionReplayRequiresStillActiveTenantAssociation() throws Exception {
    var date = LocalDate.now().plusDays(75);
    String key = "exception-revoked-replay-" + UUID.randomUUID();
    String body = "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[]}".formatted(date);
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ? AND tenant_id = ?",
        adminId,
        tenantId);
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isEqualTo(1);
  }

  @Test
  void blocksCrossDatesAndRetainCancellationHistoryAndReason() throws Exception {
    var startDate = java.time.LocalDate.now().plusDays(50);
    String start = startDate + "T23:45";
    String end = startDate.plusDays(1) + "T00:15";
    String body =
        "{\"startsAt\":\"%s\",\"endsAt\":\"%s\",\"reason\":\"Manutenção\"}".formatted(start, end);
    mvc.perform(
            post("/api/v1/admin/blocked-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "block-create-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    UUID blockId =
        appJdbc.queryForObject(
            "SELECT block_id FROM agendamento.blocked_period WHERE tenant_id = ?",
            UUID.class,
            tenantId);
    mvc.perform(
            get("/api/v1/admin/blocked-periods")
                .param("from", startDate.toString())
                .param("to", startDate.toString())
                .with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].blockId").value(blockId.toString()));
    mvc.perform(
            delete("/api/v1/admin/blocked-periods/{id}", blockId)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "block-cancel-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Reprogramado\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.reason").value("Manutenção"))
        .andExpect(jsonPath("$.cancellationReason").value("Reprogramado"));
    assertThat(
            appJdbc.queryForObject(
                "SELECT status FROM agendamento.blocked_period WHERE block_id = ?",
                String.class,
                blockId))
        .isEqualTo("CANCELLED");
    assertThat(count("audit_event", "BLOCKED_PERIOD_CREATED")).isEqualTo(1);
    assertThat(count("audit_event", "BLOCKED_PERIOD_CANCELLED")).isEqualTo(1);
  }

  @Test
  void invalidExceptionAndMissingCsrfDoNotMutateOrAudit() throws Exception {
    LocalDate date = java.time.LocalDate.now().plusDays(60);
    mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-period-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"localDate\":\"%s\",\"kind\":\"SPECIAL\",\"periods\":[{\"localStart\":\"09:00\",\"localEnd\":\"09:00\"}]}"
                        .formatted(date)))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/blocked-periods")
                .with(authentication(token(actor)))
                .header("Idempotency-Key", "missing-csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"startsAt\":\"2026-11-01T09:00\",\"endsAt\":\"2026-11-01T10:00\",\"reason\":\"test\"}"))
        .andExpect(status().isForbidden());
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.blocked_period WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isZero();
    assertThat(count("audit_event", "BLOCKED_PERIOD_CREATED")).isZero();
    UUID closedId = UUID.randomUUID();
    LocalDate closedDate = date.plusDays(1);
    appJdbc.update(
        "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind) VALUES (?, ?, ?, 'CLOSED')",
        closedId,
        tenantId,
        closedDate);
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '09:00', '10:00')",
                    tenantId,
                    closedId))
        .isInstanceOf(DataIntegrityViolationException.class);
    appJdbc.update(
        "DELETE FROM agendamento.schedule_exception WHERE tenant_id = ? AND exception_id = ?",
        tenantId,
        closedId);
    UUID emptySpecialId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind) VALUES (?, ?, ?, 'SPECIAL')",
                    emptySpecialId,
                    tenantId,
                    closedDate.plusDays(1)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void foreignExceptionAndBlockIdsAreNonRevealingAndDoNotWriteSuccessEvents() throws Exception {
    UUID foreignTenant = insertTenant("America/Fortaleza");
    UUID foreignException = UUID.randomUUID();
    UUID foreignBlock = UUID.randomUUID();
    LocalDate date = LocalDate.now().plusDays(80);
    migrationJdbc.update(
        "INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind, version) VALUES (?, ?, ?, 'CLOSED', 1)",
        foreignException,
        foreignTenant,
        date);
    migrationJdbc.update(
        "INSERT INTO agendamento.blocked_period(block_id, tenant_id, starts_at, ends_at, time_zone, reason, status, version) VALUES (?, ?, ?, ?, 'America/Fortaleza', 'Privado', 'ACTIVE', 1)",
        foreignBlock,
        foreignTenant,
        java.sql.Timestamp.from(
            date.atTime(9, 0).atZone(java.time.ZoneId.of("America/Fortaleza")).toInstant()),
        java.sql.Timestamp.from(
            date.atTime(10, 0).atZone(java.time.ZoneId.of("America/Fortaleza")).toInstant()));
    mvc.perform(
            get("/api/v1/admin/schedule/exceptions")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.exceptions").isEmpty());
    mvc.perform(
            patch("/api/v1/admin/schedule/exceptions/{id}", foreignException)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "foreign-exception-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[],\"expectedVersion\":1}"
                        .formatted(date)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    mvc.perform(
            delete("/api/v1/admin/blocked-periods/{id}", foreignBlock)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "foreign-block-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"reason\":\"Não aplicável\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_UPDATED")).isZero();
    assertThat(count("audit_event", "BLOCKED_PERIOD_CANCELLED")).isZero();
  }

  @Test
  void outboxFailureRollsBackExceptionMutationAndIdempotencyResult() {
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_agd012_outbox() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN
          IF NEW.event_type = 'ScheduleChanged' THEN RAISE EXCEPTION 'injected schedule outbox failure' USING ERRCODE = '23514'; END IF;
          RETURN NEW;
        END $$
        """);
    migrationJdbc.execute(
        "CREATE TRIGGER reject_agd012_outbox BEFORE INSERT ON agendamento.outbox_event FOR EACH ROW EXECUTE FUNCTION agendamento.reject_agd012_outbox()");
    try {
      var date = LocalDate.now().plusDays(90);
      assertThatThrownBy(
              () ->
                  mvc.perform(
                      post("/api/v1/admin/schedule/exceptions")
                          .with(authentication(token(actor)))
                          .with(csrf())
                          .header("Idempotency-Key", "rollback-exception-" + UUID.randomUUID())
                          .contentType(MediaType.APPLICATION_JSON)
                          .content(
                              "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[]}"
                                  .formatted(date))))
          .isInstanceOf(Exception.class);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    } finally {
      migrationJdbc.execute("DROP TRIGGER reject_agd012_outbox ON agendamento.outbox_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_agd012_outbox()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.schedule_exception WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(count("audit_event", "SCHEDULE_EXCEPTION_CREATED")).isZero();
    assertThat(count("outbox_event", "ScheduleChanged")).isZero();
    assertThat(count("idempotency_record", "availability.schedule-exception.create.v1")).isZero();
  }

  @Test
  void quarterHourBoundariesDoNotDependOnConfiguredThirtyOrSixtyMinuteSlot() throws Exception {
    for (int slotMinutes : List.of(30, 60)) {
      long policyVersion = slotMinutes == 30 ? 0 : 1;
      mvc.perform(
              put("/api/v1/admin/availability-policy")
                  .with(authentication(token(actor)))
                  .with(csrf())
                  .header("Idempotency-Key", "slot-" + slotMinutes + "-" + UUID.randomUUID())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      """
                      {"expectedVersion":%d,"slotStepMinutes":%d,"minimumNoticeHours":1,
                       "bookingHorizonDays":7,"cancellationCutoffHours":2,"reminderOffsetHours":2}
                      """
                          .formatted(policyVersion, slotMinutes)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.slotStepMinutes").value(slotMinutes));
    }

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "quarter-hour-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":0,"periods":[
                      {"weekday":"MONDAY","localStart":"09:15","localEnd":"12:45"}]}
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.periods[0].localStart").value("09:15"))
        .andExpect(jsonPath("$.periods[0].localEnd").value("12:45"));
  }

  @Test
  void tenantContextCannotBeSelectedByRequestAndCsrfAndRevocationAreEnforced() throws Exception {
    UUID foreignTenant = insertTenant("America/Fortaleza");
    mvc.perform(
            get("/api/v1/admin/schedule/weekly-periods")
                .param("tenantId", foreignTenant.toString())
                .with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeZone").value("America/Sao_Paulo"))
        .andExpect(jsonPath("$.configured").value(false));

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .param("tenantId", foreignTenant.toString())
                .with(authentication(token(actor)))
                .header("Idempotency-Key", "csrf-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"periods\":[]}"))
        .andExpect(status().isForbidden());

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .param("tenantId", foreignTenant.toString())
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "tenant-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"periods\":[]}"))
        .andExpect(status().isOk());
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Integer.class,
                foreignTenant))
        .isZero();

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ? AND tenant_id = ?",
        adminId,
        tenantId);
    mvc.perform(get("/api/v1/admin/availability-policy").with(authentication(token(actor))))
        .andExpect(status().isForbidden());
  }

  @Test
  void concurrentReplacementsWithSameExpectedVersionHaveSingleWinner() throws Exception {
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first =
          writers.submit(
              () -> updateAfterBarrier(ready, start, "race-a-" + UUID.randomUUID(), "09:00"));
      Future<Boolean> second =
          writers.submit(
              () -> updateAfterBarrier(ready, start, "race-b-" + UUID.randomUUID(), "10:00"));
      ready.await();
      start.countDown();
      assertThat(first.get()).isNotEqualTo(second.get());
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_UPDATED")).isEqualTo(1);
    assertThat(count("outbox_event", "ScheduleChanged")).isEqualTo(1);
  }

  @Test
  void auditFailureRollsBackScheduleIdempotencyAndOutbox() {
    replaceWeeklySchedule.execute(
        actor, "initial-schedule-" + UUID.randomUUID(), 0, List.of(period("09:00", "12:00")));
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_schedule_audit() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN
          IF NEW.action = 'SCHEDULE_UPDATED' THEN
            RAISE EXCEPTION 'injected audit failure' USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END $$
        """);
    migrationJdbc.execute(
        "CREATE TRIGGER reject_schedule_audit BEFORE INSERT ON agendamento.audit_event "
            + "FOR EACH ROW EXECUTE FUNCTION agendamento.reject_schedule_audit()");
    String key = "audit-failure-" + UUID.randomUUID();
    try {
      assertThatThrownBy(
              () -> replaceWeeklySchedule.execute(actor, key, 1, List.of(period("13:00", "17:00"))))
          .isInstanceOf(RuntimeException.class);
    } finally {
      migrationJdbc.execute("DROP TRIGGER reject_schedule_audit ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_schedule_audit()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT local_start::text FROM agendamento.weekly_period WHERE tenant_id = ?",
                String.class,
                tenantId))
        .startsWith("09:00");
    assertThat(count("idempotency_record", "availability.weekly-schedule.replace.v1")).isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_UPDATED")).isEqualTo(1);
    assertThat(count("outbox_event", "ScheduleChanged")).isEqualTo(1);
  }

  @Test
  void replayIsRejectedAfterMembershipRevocation() throws Exception {
    String key = "revoked-replay-" + UUID.randomUUID();
    String body =
        """
        {"expectedVersion":0,"periods":[
          {"weekday":"MONDAY","localStart":"09:00","localEnd":"12:00"}]}
        """;
    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());
    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ? AND tenant_id = ?",
        adminId,
        tenantId);

    mvc.perform(
            put("/api/v1/admin/schedule/weekly-periods")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.weekly_schedule WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(1);
    assertThat(count("audit_event", "SCHEDULE_UPDATED")).isEqualTo(1);
    assertThat(count("outbox_event", "ScheduleChanged")).isEqualTo(1);
    assertThat(count("idempotency_record", "availability.weekly-schedule.replace.v1")).isEqualTo(1);
  }

  @Test
  void outboxFailureRollsBackPolicyAndAudit() {
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_schedule_outbox() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN
          IF NEW.event_type = 'AvailabilityPolicyChanged' THEN
            RAISE EXCEPTION 'injected outbox failure' USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END $$
        """);
    migrationJdbc.execute(
        "CREATE TRIGGER reject_schedule_outbox BEFORE INSERT ON agendamento.outbox_event "
            + "FOR EACH ROW EXECUTE FUNCTION agendamento.reject_schedule_outbox()");
    String key = "outbox-failure-" + UUID.randomUUID();
    try {
      assertThatThrownBy(() -> replaceAvailabilityPolicy.execute(actor, key, 0, 30, 12, 30, 4, 6))
          .isInstanceOf(RuntimeException.class);
    } finally {
      migrationJdbc.execute("DROP TRIGGER reject_schedule_outbox ON agendamento.outbox_event");
      migrationJdbc.execute("DROP FUNCTION agendamento.reject_schedule_outbox()");
    }
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.availability_policy WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(count("audit_event", "AVAILABILITY_POLICY_UPDATED")).isZero();
    assertThat(count("idempotency_record", "availability.policy.replace.v1")).isZero();
  }

  @Test
  void postgresRejectsOverlapsAndRuntimeHasOnlyRequiredPrivileges() {
    appJdbc.update(
        "INSERT INTO agendamento.weekly_schedule (tenant_id, version) VALUES (?, 1)", tenantId);
    appJdbc.update(
        "INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'MONDAY', '09:00', '12:00')",
        tenantId);
    appJdbc.update(
        "INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'MONDAY', '12:00', '13:00')",
        tenantId);
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'MONDAY', '11:00', '13:00')",
                    tenantId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.weekly_period WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'TUESDAY', '09:10', '12:00')",
                    tenantId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'WEDNESDAY', '23:45', '24:00')",
                    tenantId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', 'agendamento.weekly_schedule', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.weekly_schedule', 'DELETE') "
                    + "AND has_table_privilege('agendamento_app', 'agendamento.weekly_period', 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.weekly_period', 'UPDATE') "
                    + "AND has_table_privilege('agendamento_app', 'agendamento.availability_policy', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.availability_policy', 'DELETE')",
                Boolean.class))
        .isTrue();
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_app', 'agendamento.blocked_period', 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', 'agendamento.blocked_period', 'cancellation_reason', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', 'agendamento.blocked_period', 'starts_at', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.blocked_period', 'DELETE') "
                    + "AND has_column_privilege('agendamento_app', 'agendamento.schedule_exception', 'local_date', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', 'agendamento.schedule_exception', 'tenant_id', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', 'agendamento.schedule_revision', 'revision', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', 'agendamento.schedule_revision', 'DELETE')",
                Boolean.class))
        .isTrue();
    assertThatThrownBy(
            () ->
                appJdbc.update(
                    "UPDATE agendamento.blocked_period SET starts_at = starts_at + interval '1 day' WHERE tenant_id = ?",
                    tenantId))
        .isInstanceOf(DataAccessException.class);
  }

  private boolean updateAfterBarrier(
      CountDownLatch ready, CountDownLatch start, String key, String startTime) throws Exception {
    ready.countDown();
    start.await();
    try {
      replaceWeeklySchedule.execute(actor, key, 0, List.of(period(startTime, "12:00")));
      return true;
    } catch (ScheduleVersionConflictException conflict) {
      return false;
    }
  }

  private int createExceptionAfterBarrier(
      CountDownLatch ready, CountDownLatch start, LocalDate date) throws Exception {
    ready.countDown();
    start.await();
    return mvc.perform(
            post("/api/v1/admin/schedule/exceptions")
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "exception-race-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"localDate\":\"%s\",\"kind\":\"CLOSED\",\"periods\":[]}".formatted(date)))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  private boolean insertAmbiguousPeriodAfterBarrier(
      CountDownLatch ready,
      CountDownLatch start,
      UUID exceptionId,
      String localStart,
      String localEnd,
      Integer startOffset,
      Integer endOffset)
      throws Exception {
    ready.countDown();
    start.await();
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(appJdbc.getDataSource()));
    try {
      transaction.executeWithoutResult(
          status ->
              appJdbc.update(
                  "INSERT INTO agendamento.schedule_exception_period(tenant_id, exception_id, local_start, local_end, local_start_offset_seconds, local_end_offset_seconds) VALUES (?, ?, ?, ?, ?, ?)",
                  tenantId,
                  exceptionId,
                  LocalTime.parse(localStart),
                  LocalTime.parse(localEnd),
                  startOffset,
                  endOffset));
      return true;
    } catch (DataAccessException exception) {
      return false;
    }
  }

  private WeeklyPeriod period(String start, String end) {
    return new WeeklyPeriod(DayOfWeek.MONDAY, LocalTime.parse(start), LocalTime.parse(end));
  }

  private UUID insertTenant(String timeZone) {
    UUID id = UUID.randomUUID();
    String slug = "foreign-" + UUID.randomUUID().toString().substring(0, 8);
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        id,
        "Foreign tenant",
        slug,
        timeZone);
    return id;
  }

  private int count(String table, String key) {
    String field = table.equals("idempotency_record") ? "command_scope" : "event_type";
    if (table.equals("audit_event")) field = "action";
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ? AND " + field + " = ?",
        Integer.class,
        tenantId,
        key);
  }

  private int countForTenant(String table) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private static AuthenticatedAdmin authenticatedAdmin(
      UUID tenant, UUID userId, String userSubject) {
    return new TestAdmin(userId, userSubject, tenant);
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

  private static UsernamePasswordAuthenticationToken token(AuthenticatedAdmin admin) {
    return UsernamePasswordAuthenticationToken.authenticated(
        admin, "N/A", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
  }
}
