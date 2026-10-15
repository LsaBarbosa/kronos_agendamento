package com.appagendamento.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.identityaccess.api.AuthenticatedTenant;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class TenantPersistenceIT {

  @Autowired private JdbcTemplate jdbc;
  @Autowired private DataSource dataSource;
  @Autowired private Flyway flyway;
  @Autowired private CreateTenant createTenant;
  @Autowired private GetTenant getTenant;
  @Autowired private RenameTenant renameTenant;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @Test
  void appliesV1AndV2ToFreshSchemaAndCanRerunWithoutChanges() {
    String schema = isolatedSchemaName();
    Flyway fresh = isolatedFlyway(schema);

    assertThat(fresh.migrate().migrationsExecuted).isEqualTo(4);
    fresh.validate();
    assertThat(fresh.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4");
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(3);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4");
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForList(
                "SELECT version FROM "
                    + schema
                    + ".flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class))
        .containsExactly(null, "1", "2", "3", "4");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.spring_session')", String.class, schema))
        .isEqualTo(schema + ".spring_session");
  }

  @Test
  void permitsOwnTenantOperationsAndConcealsForeignTenantForReadAndMutation() {
    Tenant own = createTenant.execute("Clínica A", "tenant-a-" + suffix(), "America/Sao_Paulo");
    Tenant foreign = createTenant.execute("Clínica B", "tenant-b-" + suffix(), "America/Fortaleza");
    AuthenticatedTenant actor = () -> own.tenantId();

    assertThat(getTenant.execute(actor, own.tenantId()).tenantId()).isEqualTo(own.tenantId());
    Tenant renamed = renameTenant.execute(actor, own.tenantId(), "Clínica A Atualizada", 0);
    assertThat(renamed.name()).isEqualTo("Clínica A Atualizada");
    assertThat(renamed.version()).isEqualTo(1);

    assertThatThrownBy(() -> getTenant.execute(actor, foreign.tenantId()))
        .isExactlyInstanceOf(TenantNotFoundException.class);
    assertThatThrownBy(() -> renameTenant.execute(actor, foreign.tenantId(), "Vazamento", 0))
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
        .dataSource(dataSource)
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
}
