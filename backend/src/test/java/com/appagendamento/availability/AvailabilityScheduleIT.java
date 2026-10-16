package com.appagendamento.availability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.availability.application.ReplaceAvailabilityPolicy;
import com.appagendamento.availability.application.ReplaceWeeklySchedule;
import com.appagendamento.availability.domain.ScheduleVersionConflictException;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

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
