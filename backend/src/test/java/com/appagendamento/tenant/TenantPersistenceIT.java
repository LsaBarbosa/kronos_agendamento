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
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

    assertThat(fresh.migrate().migrationsExecuted).isEqualTo(7);
    fresh.validate();
    assertThat(fresh.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4", "5", "6", "7");
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
  void upgradesV1SchemaToV2AndKeepsMigrationHistoryValid() {
    String schema = isolatedSchemaName();
    Flyway v1 = isolatedFlyway(schema, MigrationVersion.fromVersion("1"));
    v1.migrate();

    assertThat(v1.info().current().getVersion().getVersion()).isEqualTo("1");
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(6);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4", "5", "6", "7");
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(5);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4", "5", "6", "7");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.spring_session')", String.class, schema))
        .isEqualTo(schema + ".spring_session");
  }

  @Test
  void upgradesAgd004V5SchemaWithAppendOnlyAuditThroughV7() {
    String schema = isolatedSchemaName();
    Flyway v5 = isolatedFlyway(schema, MigrationVersion.fromVersion("5"));
    assertThat(v5.migrate().migrationsExecuted).isEqualTo(5);
    assertThat(v5.info().current().getVersion().getVersion()).isEqualTo("5");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(8);
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
  void upgradesPublishedV6SchemaToV7AndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v6 = isolatedFlyway(schema, MigrationVersion.fromVersion("6"));
    assertThat(v6.migrate().migrationsExecuted).isEqualTo(6);
    assertThat(v6.info().current().getVersion().getVersion()).isEqualTo("6");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject("SELECT to_regclass(?||'.service')", String.class, schema))
        .isEqualTo(schema + ".service");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(8);
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
