package com.appagendamento.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.application.GetTenant;
import com.appagendamento.tenant.application.RenameTenant;
import com.appagendamento.tenant.domain.Tenant;
import com.appagendamento.tenant.domain.TenantNotFoundException;
import com.appagendamento.tenant.domain.TenantSlugAlreadyExistsException;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class TenantPersistenceIT {

  private JdbcTemplate jdbc;
  @Autowired private JdbcTemplate appJdbc;
  @Autowired private CreateTenant createTenant;
  @Autowired private GetTenant getTenant;
  @Autowired private RenameTenant renameTenant;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void privilegedFixtures() {
    jdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
  }

  @Test
  void appliesV1AndV2ToFreshSchemaAndCanRerunWithoutChanges() {
    String schema = isolatedSchemaName();
    Flyway fresh = isolatedFlyway(schema);

    assertThat(fresh.migrate().migrationsExecuted).isEqualTo(23);
    fresh.validate();
    assertThat(fresh.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(
            null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
            "16", "17", "18", "19", "20", "21", "22", "23");
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'tenant')",
                Boolean.class,
                schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'spring_session')",
                Boolean.class,
                schema))
        .isTrue();
  }

  @Test
  void upgradesV22ToV23AndGrantsOnlyEvidenceCorrelationUpdate() {
    String schema = isolatedSchemaName();
    Flyway v22 = isolatedFlyway(schema, MigrationVersion.fromVersion("22"));
    assertThat(v22.migrate().migrationsExecuted).isEqualTo(22);
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND has_column_privilege('agendamento_app', ?, 'notification_id', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', ?, 'provider_event_id', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".notification_evidence_inbox",
                schema + ".notification_evidence_inbox",
                schema + ".notification_evidence_inbox",
                schema + ".notification_evidence_inbox",
                schema + ".notification_evidence_inbox",
                schema + ".notification_evidence_inbox"))
        .isTrue();
  }

  @Test
  void upgradesV1SchemaToV2AndKeepsMigrationHistoryValid() {
    String schema = isolatedSchemaName();
    Flyway v1 = isolatedFlyway(schema, MigrationVersion.fromVersion("1"));
    v1.migrate();

    assertThat(v1.info().current().getVersion().getVersion()).isEqualTo("1");
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(22);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(
            null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
            "16", "17", "18", "19", "20", "21", "22", "23");
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'tenant')",
                Boolean.class,
                schema))
        .isTrue();
  }

  @Test
  void upgradesAgd002V2SchemaWithIdentityAndSessionTables() {
    String schema = isolatedSchemaName();
    Flyway v2 = isolatedFlyway(schema, MigrationVersion.fromVersion("2"));
    assertThat(v2.migrate().migrationsExecuted).isEqualTo(2);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(21);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(
            null, "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15",
            "16", "17", "18", "19", "20", "21", "22", "23");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.spring_session')", String.class, schema))
        .isEqualTo(schema + ".spring_session");
  }

  @Test
  void upgradesAgd004V5SchemaWithAppendOnlyAuditThroughV13() {
    String schema = isolatedSchemaName();
    Flyway v5 = isolatedFlyway(schema, MigrationVersion.fromVersion("5"));
    assertThat(v5.migrate().migrationsExecuted).isEqualTo(5);
    assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(18);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(24);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE')",
                Boolean.class,
                schema + ".audit_event",
                schema + ".audit_event",
                schema + ".audit_event"))
        .isTrue();
  }

  @Test
  void upgradesPublishedV6SchemaToV12AndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v6 = isolatedFlyway(schema, MigrationVersion.fromVersion("6"));
    assertThat(v6.migrate().migrationsExecuted).isEqualTo(6);
    assertThat(v6.info().current().getVersion().getVersion()).isEqualTo("6");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(17);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject("SELECT to_regclass(?||'.service')", String.class, schema))
        .isEqualTo(schema + ".service");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.weekly_schedule')", String.class, schema))
        .isEqualTo(schema + ".weekly_schedule");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(24);
  }

  @Test
  void upgradesPublishedV7SchemaToV12AndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v7 = isolatedFlyway(schema, MigrationVersion.fromVersion("7"));
    assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);
    assertThat(v7.info().current().getVersion().getVersion()).isEqualTo("7");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(16);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass(?||'.availability_policy')", String.class, schema))
        .isEqualTo(schema + ".availability_policy");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(24);
  }

  @Test
  void v9StopsForLegacyUnalignedPeriodsWithoutChangingThemAndAllowsExplicitRepair() {
    String schema = isolatedSchemaName();
    Flyway v8 = isolatedFlyway(schema, MigrationVersion.fromVersion("8"));
    assertThat(v8.migrate().migrationsExecuted).isEqualTo(8);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Legacy', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "legacy-" + suffix());
    jdbc.update(
        "INSERT INTO " + schema + ".weekly_schedule (tenant_id, version) VALUES (?, 1)", tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'MONDAY', '09:10', '12:00')",
        tenantId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThatThrownBy(upgrade::migrate)
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining(
            "V9 cannot apply: weekly_period has boundaries incompatible with quarter-hour rules");
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("8");
    assertThat(
            jdbc.queryForObject(
                "SELECT local_start::text FROM " + schema + ".weekly_period WHERE tenant_id = ?",
                String.class,
                tenantId))
        .startsWith("09:10");

    jdbc.update(
        "UPDATE " + schema + ".weekly_period SET local_start = '09:15' WHERE tenant_id = ?",
        tenantId);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(15);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT local_start::text FROM " + schema + ".weekly_period WHERE tenant_id = ?",
                String.class,
                tenantId))
        .startsWith("09:15");
  }

  @Test
  void upgradesV8WithAlignedExistingPeriodsAndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v8 = isolatedFlyway(schema, MigrationVersion.fromVersion("8"));
    assertThat(v8.migrate().migrationsExecuted).isEqualTo(8);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Aligned', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "aligned-" + suffix());
    jdbc.update(
        "INSERT INTO " + schema + ".weekly_schedule (tenant_id, version) VALUES (?, 1)", tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".weekly_period (tenant_id, weekday, local_start, local_end) VALUES (?, 'MONDAY', '09:15', '12:45')",
        tenantId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(15);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".weekly_period WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(1);
  }

  @Test
  void upgradesV9ToV20AndRerunsWithoutChangingHistory() {
    String schema = isolatedSchemaName();
    Flyway v9 = isolatedFlyway(schema, MigrationVersion.fromVersion("9"));
    assertThat(v9.migrate().migrationsExecuted).isEqualTo(9);
    assertThat(v9.info().current().getVersion().getVersion()).isEqualTo("9");
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(14);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass(?||'.schedule_exception')", String.class, schema))
        .isEqualTo(schema + ".schedule_exception");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.blocked_period')", String.class, schema))
        .isEqualTo(schema + ".blocked_period");
  }

  @Test
  void upgradesV10SpecialPeriodsWithoutChangingLocalTimesAndRerunsV13() {
    String schema = isolatedSchemaName();
    Flyway v10 = isolatedFlyway(schema, MigrationVersion.fromVersion("10"));
    assertThat(v10.migrate().migrationsExecuted).isEqualTo(10);
    UUID tenantId = UUID.randomUUID();
    UUID exceptionId = UUID.randomUUID();
    DataSource privilegedDataSource = PostgresTestEnvironment.migrationDataSource();
    JdbcTemplate privilegedJdbc = new JdbcTemplate(privilegedDataSource);
    TransactionTemplate transaction =
        new TransactionTemplate(new DataSourceTransactionManager(privilegedDataSource));
    transaction.executeWithoutResult(
        status -> {
          privilegedJdbc.update(
              "INSERT INTO "
                  + schema
                  + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Legacy exception', ?, 'America/New_York', 'ACTIVE')",
              tenantId,
              "legacy-exception-" + suffix());
          privilegedJdbc.update(
              "INSERT INTO "
                  + schema
                  + ".schedule_exception (exception_id, tenant_id, local_date, kind) VALUES (?, ?, '2026-11-01', 'SPECIAL')",
              exceptionId,
              tenantId);
          privilegedJdbc.update(
              "INSERT INTO "
                  + schema
                  + ".schedule_exception_period (tenant_id, exception_id, local_start, local_end) VALUES (?, ?, '09:15', '10:15')",
              tenantId,
              exceptionId);
        });

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(13);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            privilegedJdbc.queryForObject(
                "SELECT local_start::text FROM "
                    + schema
                    + ".schedule_exception_period WHERE exception_id = ?",
                String.class,
                exceptionId))
        .startsWith("09:15");
    assertThat(
            privilegedJdbc.queryForObject(
                "SELECT local_start_offset_seconds FROM "
                    + schema
                    + ".schedule_exception_period WHERE exception_id = ?",
                Integer.class,
                exceptionId))
        .isNull();
    assertThat(
            privilegedJdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(24);
  }

  @Test
  void upgradesV11AndBackfillsUnpublishedStateWithoutChangingTenantData() {
    String schema = isolatedSchemaName();
    Flyway v11 = isolatedFlyway(schema, MigrationVersion.fromVersion("11"));
    assertThat(v11.migrate().migrationsExecuted).isEqualTo(11);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Existing business', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "existing-business-" + suffix());

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(12);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT published FROM " + schema + ".tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT name || ':' || slug FROM " + schema + ".tenant WHERE tenant_id = ?",
                String.class,
                tenantId))
        .startsWith("Existing business:existing-business-");

    UUID postMigrationTenant = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'New business', ?, 'UTC', 'ACTIVE')",
        postMigrationTenant,
        "new-business-" + suffix());
    assertThat(
            jdbc.queryForObject(
                "SELECT published FROM " + schema + ".tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                postMigrationTenant))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE')",
                Boolean.class,
                schema + ".tenant_publication",
                schema + ".tenant_publication",
                schema + ".tenant_publication"))
        .isTrue();
  }

  @Test
  void upgradesPublishedV12ToV13WithTenantScopedAppointmentConstraintsAndRuntimeGrants() {
    String schema = isolatedSchemaName();
    Flyway v12 = isolatedFlyway(schema, MigrationVersion.fromVersion("12"));
    assertThat(v12.migrate().migrationsExecuted).isEqualTo(12);
    UUID tenantId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Booking tenant', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "booking-" + suffix());

    Flyway upgrade = isolatedFlyway(schema, MigrationVersion.fromVersion("13"));
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("13");
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Cliente', '+5511999999999')",
        clientId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".service (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status) VALUES (?, ?, 'Serviço', 'serviço', 30, 15, 'ACTIVE')",
        serviceId,
        tenantId);
    UUID appointmentId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, '2026-10-19T12:00:00Z', '2026-10-19T12:30:00Z', '2026-10-19T12:45:00Z', 'America/Sao_Paulo', 'Cliente', 'Serviço', 30, 15, 'CONFIRMED', clock_timestamp(), 1)",
        appointmentId,
        tenantId,
        clientId,
        serviceId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, '2026-10-19T12:30:00Z', '2026-10-19T13:00:00Z', '2026-10-19T13:15:00Z', 'America/Sao_Paulo', 'Cliente', 'Serviço', 30, 15, 'CONFIRMED', clock_timestamp(), 1)",
                    UUID.randomUUID(),
                    tenantId,
                    clientId,
                    serviceId))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".appointment WHERE appointment_id = ?",
                Integer.class,
                appointmentId))
        .isEqualTo(1);
  }

  @Test
  void upgradesPublishedV13ToV19WithoutRewritingLegacyDigestsAndKeepsRuntimeLeastPrivilege() {
    String schema = isolatedSchemaName();
    Flyway v13 = isolatedFlyway(schema, MigrationVersion.fromVersion("13"));
    assertThat(v13.migrate().migrationsExecuted).isEqualTo(13);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Existing booking', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "existing-booking-" + suffix());
    String keyDigest = "a".repeat(64);
    String legacyDigest = "b".repeat(64);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".idempotency_record (tenant_id, command_scope, key_digest, request_digest, operation_id, state) VALUES (?, 'appointments.public.create.v1', ?, ?, ?, 'IN_PROGRESS')",
        tenantId,
        keyDigest,
        legacyDigest,
        UUID.randomUUID());

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(10);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT rtrim(request_digest) FROM "
                    + schema
                    + ".idempotency_record WHERE tenant_id = ? AND key_digest = ?",
                String.class,
                tenantId,
                keyDigest))
        .isEqualTo(legacyDigest);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE')",
                Boolean.class,
                schema + ".public_booking_intent",
                schema + ".public_booking_intent",
                schema + ".public_booking_intent",
                schema + ".public_booking_intent"))
        .isTrue();
  }

  @Test
  void upgradesPublishedV14SchemaToV19AndRerunsWithoutChangingHistoryOrPrivileges() {
    String schema = isolatedSchemaName();
    Flyway v14 = isolatedFlyway(schema, MigrationVersion.fromVersion("14"));
    assertThat(v14.migrate().migrationsExecuted).isEqualTo(14);
    assertThat(v14.info().current().getVersion().getVersion()).isEqualTo("14");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(9);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'appointment_history' AND column_name = 'reason')",
                Boolean.class,
                schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE')",
                Boolean.class,
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history"))
        .isTrue();
  }

  @Test
  void upgradesPublishedV15ToV19AndGrantsOnlyOutcomeAndRescheduleColumns() {
    String schema = isolatedSchemaName();
    Flyway v15 = isolatedFlyway(schema, MigrationVersion.fromVersion("15"));
    assertThat(v15.migrate().migrationsExecuted).isEqualTo(15);
    assertThat(v15.info().current().getVersion().getVersion()).isEqualTo("15");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(8);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_app', ?, 'starts_at', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', ?, 'service_ends_at', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', ?, 'occupied_until', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', ?, 'version', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', ?, 'status', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', ?, 'client_id', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', ?, 'service_name_snapshot', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE')",
                Boolean.class,
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment"))
        .isTrue();
  }

  @Test
  void upgradesV16ToV19WithValidHistoryAndKeepsRuntimePrivileges() {
    String schema = isolatedSchemaName();
    Flyway v16 = isolatedFlyway(schema, MigrationVersion.fromVersion("16"));
    assertThat(v16.migrate().migrationsExecuted).isEqualTo(16);
    Flyway upgrade = isolatedFlyway(schema);

    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE')",
                Boolean.class,
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history"))
        .isTrue();
  }

  @Test
  void upgradesV17ToV19AndRerunsWithLeastPrivilegeOutcomeGrant() {
    String schema = isolatedSchemaName();
    Flyway v17 = isolatedFlyway(schema, MigrationVersion.fromVersion("17"));
    assertThat(v17.migrate().migrationsExecuted).isEqualTo(17);
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(6);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_app', ?, 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_app', ?, 'version', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_app', ?, 'client_id', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE')",
                Boolean.class,
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment",
                schema + ".appointment"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'TRUNCATE') "
                    + "AND has_table_privilege('agendamento_app', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_delivery_evidence",
                schema + ".notification_delivery_evidence",
                schema + ".notification_delivery_evidence",
                schema + ".notification_delivery_evidence",
                schema + ".notification_delivery_evidence"))
        .isTrue();
  }

  @Test
  void v17FailsExplicitlyAndPreservesLegacyRescheduleHistoryWithUnknownCheckResult() {
    String schema = isolatedSchemaName();
    Flyway v16 = isolatedFlyway(schema, MigrationVersion.fromVersion("16"));
    v16.migrate();
    UUID tenantId = UUID.randomUUID();
    UUID serviceId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID historyId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Legacy', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "legacy-" + suffix());
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".service (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status) VALUES (?, ?, 'Service', 'service', 30, 0, 'ACTIVE')",
        serviceId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".client (client_id, tenant_id, display_name, normalized_phone) VALUES (?, ?, 'Client', '+5511999990000')",
        clientId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".appointment (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at, occupied_until, time_zone, client_name_snapshot, service_name_snapshot, service_duration_minutes, service_buffer_after_minutes, status, created_at, version) VALUES (?, ?, ?, ?, '2026-10-19 09:00+00', '2026-10-19 09:30+00', '2026-10-19 09:30+00', 'UTC', 'Client', 'Service', 30, 0, 'CONFIRMED', clock_timestamp(), 1)",
        appointmentId,
        tenantId,
        clientId,
        serviceId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".appointment_history (history_id, tenant_id, appointment_id, from_status, to_status, actor_type, actor_ref, reason_code, occurred_at, previous_starts_at, previous_occupied_until, new_starts_at, new_occupied_until, version_before, version_after) VALUES (?, ?, ?, 'CONFIRMED', 'CONFIRMED', 'ADMIN', 'TENANT_ADMIN', 'APPOINTMENT_RESCHEDULED', clock_timestamp(), '2026-10-19 09:00+00', NULL, '2026-10-19 10:00+00', '2026-10-19 10:30+00', 1, 2)",
        historyId,
        tenantId,
        appointmentId);
    assertThat(
            jdbc.queryForObject(
                "SELECT previous_occupied_until IS NULL FROM "
                    + schema
                    + ".appointment_history WHERE history_id = ?",
                Boolean.class,
                historyId))
        .isTrue();

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT previous_occupied_until IS NULL FROM "
                    + schema
                    + ".appointment_history WHERE history_id = ?",
                Boolean.class,
                historyId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                String.class))
        .isEqualTo("16");
  }

  @Test
  void permitsOwnTenantOperationsAndConcealsForeignTenantForReadAndMutation() {
    Tenant own = createTenant.execute("Clínica A", "tenant-a-" + suffix(), "America/Sao_Paulo");
    Tenant foreign = createTenant.execute("Clínica B", "tenant-b-" + suffix(), "America/Fortaleza");
    AuthenticatedAdmin actor = adminActor(own.tenantId());

    assertThat(getTenant.execute(actor, own.tenantId()).tenantId()).isEqualTo(own.tenantId());
    Tenant renamed =
        renameTenant
            .execute(
                actor, own.tenantId(), "rename-" + UUID.randomUUID(), "Clínica A Atualizada", 0)
            .value();
    assertThat(renamed.name()).isEqualTo("Clínica A Atualizada");
    assertThat(renamed.version()).isEqualTo(1);

    assertThatThrownBy(() -> getTenant.execute(actor, foreign.tenantId()))
        .isExactlyInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(
            () ->
                renameTenant.execute(
                    actor, foreign.tenantId(), "rename-" + UUID.randomUUID(), "Vazamento", 0))
        .isExactlyInstanceOf(TenantNotFoundException.class);
    assertThat(getTenant.execute(() -> foreign.tenantId(), foreign.tenantId()).name())
        .isEqualTo("Clínica B");
  }

  @Test
  void concurrentInsertsOfSameCanonicalSlugHaveOneWinner() throws Exception {
    String slug = "race-" + suffix();
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = writers.submit(() -> insertAfterBarrier(start, slug, "Race A"));
      Future<Boolean> second = writers.submit(() -> insertAfterBarrier(start, slug, "Race B"));

      assertThat(first.get()).isNotEqualTo(second.get());
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM agendamento.tenant WHERE slug = ?", Integer.class, slug))
        .isEqualTo(1);
  }

  private boolean insertAfterBarrier(CyclicBarrier start, String slug, String name)
      throws Exception {
    start.await();
    try {
      createTenant.execute(name, slug.toUpperCase(), "America/Sao_Paulo");
      return true;
    } catch (TenantSlugAlreadyExistsException conflict) {
      return false;
    }
  }

  @Test
  void upgradesV19ClientSessionsWithLegacyRowsToChallengeBoundV20WithoutRewritingRows() {
    String schema = isolatedSchemaName();
    Flyway v19 = isolatedFlyway(schema, MigrationVersion.fromVersion("19"));
    assertThat(v19.migrate().migrationsExecuted).isEqualTo(19);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Legacy client', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "legacy-client-" + suffix());
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".client_access_session(session_digest, tenant_id, phone_ciphertext, phone_key_id, created_at, expires_at) VALUES (?, ?, ?, 'legacy-key', TIMESTAMPTZ '2026-10-17 12:00:00+00', TIMESTAMPTZ '2026-10-17 12:15:00+00')",
        new byte[32],
        tenantId,
        new byte[29]);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(4);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("23");
    assertThat(
            jdbc.queryForObject(
                "SELECT challenge_id IS NULL FROM "
                    + schema
                    + ".client_access_session WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_app', ?, 'INSERT') "
                    + "AND NOT has_column_privilege('agendamento_app', ?, 'challenge_id', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_app', ?, 'DELETE')",
                Boolean.class,
                schema + ".client_access_session",
                schema + ".client_access_session",
                schema + ".client_access_session"))
        .isTrue();
  }

  private Flyway isolatedFlyway(String schema) {
    return isolatedFlyway(schema, MigrationVersion.LATEST);
  }

  private Flyway isolatedFlyway(String schema, MigrationVersion target) {
    return Flyway.configure()
        .dataSource(PostgresTestEnvironment.migrationDataSource())
        .locations("classpath:db/migration")
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .createSchemas(true)
        .cleanDisabled(true)
        .load();
  }

  private static String isolatedSchemaName() {
    return "tenant_test_" + suffix().replace("-", "");
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  private AuthenticatedAdmin adminActor(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        "https://identity.test/realms/agendamento",
        subject);
    jdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    return new AuthenticatedAdmin() {
      @Override
      public UUID adminUserId() {
        return adminId;
      }

      @Override
      public String issuer() {
        return "https://identity.test/realms/agendamento";
      }

      @Override
      public String subject() {
        return subject;
      }

      @Override
      public String role() {
        return "TENANT_ADMIN";
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }
    };
  }
}
