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

    assertThat(fresh.migrate().migrationsExecuted).isEqualTo(40);
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
            "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29",
            "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40");
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
  void upgradesV34ToV35WithStableHistoryAndMinimalDunningRuntimeGrants() {
    String schema = isolatedSchemaName();
    Flyway v34 = isolatedFlyway(schema, MigrationVersion.fromVersion("34"));
    assertThat(v34.migrate().migrationsExecuted).isEqualTo(34);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', NULL, NULL, 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', 1)",
        subscriptionId,
        tenantId);

    Flyway upgrade = isolatedFlyway(schema, MigrationVersion.fromVersion("35"));
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("35");
    assertThat(
            jdbc.queryForObject(
                "SELECT grace_ends_at IS NULL FROM "
                    + schema
                    + ".subscription WHERE subscription_id = ?",
                Boolean.class,
                subscriptionId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'grace_ends_at', 'UPDATE')",
                Boolean.class,
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".subscription"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'state', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'closed_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'due_at', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'state', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'updated_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'claim_token', 'UPDATE')",
                Boolean.class,
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_cycle",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt"))
        .isTrue();
  }

  @Test
  void upgradesV35ToV36WithStableAttemptIdentityExactGraceAndLeastRuntimeGrants() {
    String schema = isolatedSchemaName();
    Flyway v35 = isolatedFlyway(schema, MigrationVersion.fromVersion("35"));
    assertThat(v35.migrate().migrationsExecuted).isEqualTo(35);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, grace_ends_at, version) VALUES (?, ?, 'PAST_DUE', NULL, NULL, 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 2)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at) VALUES (?, ?, ?, TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 'OPEN', TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2027-01-01 00:00:00+00', 'SCHEDULED', TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        attemptId,
        tenantId,
        cycleId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(5);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT external_idempotency_key = attempt_id AND NOT ever_started AND state = 'SCHEDULED' FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                Boolean.class,
                attemptId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'state', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'claim_token', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'send_claim_token', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'external_idempotency_key', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt"))
        .isTrue();
  }

  @Test
  void v40FailsClosedWhenLegacyContractCannotProveTheAttemptSnapshot() {
    String schema = isolatedSchemaName();
    Flyway v39 = isolatedFlyway(schema, MigrationVersion.fromVersion("39"));
    assertThat(v39.migrate().migrationsExecuted).isEqualTo(39);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_checkout_operation(operation_id, tenant_id, plan_code, amount_minor_units, currency, terms_version, offer_version, expected_subscription_version, provider_code, provider_account_id, provider_reference, state) VALUES (?, ?, 'MONTHLY', 2500, 'XTS', 'terms-v1', 'offer-v1', 0, 'provider', 'account', ?, 'CONFIRMED')",
        sourceOperationId,
        tenantId,
        "migration-invalid-source-" + sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', 3)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_subscription_contract(subscription_id, tenant_id, source_operation_id, amount_minor_units, currency, terms_version, offer_version, recorded_at) VALUES (?, ?, ?, 2500, 'XTS', 'terms-v1', 'offer-v1', TIMESTAMPTZ '2026-10-01 00:00:00+00')",
        subscriptionId,
        tenantId,
        sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription_history(event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', NULL, 'ACTIVE', TIMESTAMPTZ '2026-10-01 00:00:00+00', 1, ?)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at, closed_at) VALUES (?, ?, ?, TIMESTAMPTZ '2026-10-01 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00', 'SUSPENDED', TIMESTAMPTZ '2026-10-02 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at, external_idempotency_key, contract_amount_minor_units, contract_currency, contract_terms_version, contract_offer_version) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2026-10-01 00:00:00+00', 'SCHEDULED', TIMESTAMPTZ '2026-10-02 00:00:00+00', ?, 2600, 'XTS', 'terms-v1', 'offer-v1')",
        attemptId,
        tenantId,
        cycleId,
        attemptId);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
        .hasMessageContaining("V40 cannot apply");
    assertThat(
            jdbc.queryForObject(
                "SELECT amount_minor_units FROM "
                    + schema
                    + ".billing_subscription_contract WHERE subscription_id = ?",
                Integer.class,
                subscriptionId))
        .isEqualTo(2500);
    assertThat(
            jdbc.queryForObject(
                "SELECT contract_amount_minor_units FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                Integer.class,
                attemptId))
        .isEqualTo(2600);
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass(? || '.billing_subscription_contract') IS NOT NULL AND NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'billing_subscription_contract' AND column_name = 'plan_code')",
                Boolean.class,
                schema,
                schema))
        .isTrue();
    assertThat(v39.info().current().getVersion().getVersion()).isEqualTo("39");
  }

  @Test
  void v40FailsClosedWhenConfirmedContractHasNoHistoricalPaymentLink() {
    String schema = isolatedSchemaName();
    Flyway v39 = isolatedFlyway(schema, MigrationVersion.fromVersion("39"));
    assertThat(v39.migrate().migrationsExecuted).isEqualTo(39);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_checkout_operation(operation_id, tenant_id, plan_code, amount_minor_units, currency, terms_version, offer_version, expected_subscription_version, provider_code, provider_account_id, provider_reference, state) VALUES (?, ?, 'ANNUAL', 2500, 'XTS', 'terms-v1', 'offer-v1', 0, 'provider', 'account', ?, 'CONFIRMED')",
        sourceOperationId,
        tenantId,
        "migration-unlinked-source-" + sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'ANNUAL', TIMESTAMPTZ '2027-01-01 00:00:00+00', 3)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_subscription_contract(subscription_id, tenant_id, source_operation_id, amount_minor_units, currency, terms_version, offer_version, recorded_at) VALUES (?, ?, ?, 2500, 'XTS', 'terms-v1', 'offer-v1', TIMESTAMPTZ '2026-10-01 00:00:00+00')",
        subscriptionId,
        tenantId,
        sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at, closed_at) VALUES (?, ?, ?, TIMESTAMPTZ '2026-10-01 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00', 'SUSPENDED', TIMESTAMPTZ '2026-10-02 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at, external_idempotency_key, contract_amount_minor_units, contract_currency, contract_terms_version, contract_offer_version) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2026-10-01 00:00:00+00', 'SCHEDULED', TIMESTAMPTZ '2026-10-02 00:00:00+00', ?, 2500, 'XTS', 'terms-v1', 'offer-v1')",
        attemptId,
        tenantId,
        cycleId,
        attemptId);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
        .hasMessageContaining("V40 cannot apply");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".subscription_history WHERE operation_id = ?",
                Integer.class,
                sourceOperationId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'billing_subscription_contract' AND column_name = 'plan_code')",
                Boolean.class,
                schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT contract_amount_minor_units FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                Integer.class,
                attemptId))
        .isEqualTo(2500);
  }

  @Test
  void upgradesV37ToV38WithNoticeStatesValidateRerunAndLeastGrants() {
    String schema = isolatedSchemaName();
    Flyway v36 = isolatedFlyway(schema, MigrationVersion.fromVersion("36"));
    assertThat(v36.migrate().migrationsExecuted).isEqualTo(36);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    Flyway v37 = isolatedFlyway(schema, MigrationVersion.fromVersion("37"));
    assertThat(v37.migrate().migrationsExecuted).isEqualTo(1);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, grace_ends_at, version) VALUES (?, ?, 'ACTIVE', NULL, NULL, 'ANNUAL', TIMESTAMPTZ '2027-01-01 00:00:00+00', NULL, 1)",
        subscriptionId,
        tenantId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(3);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    UUID expiredNoticeId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_annual_renewal_notice(notice_id, tenant_id, subscription_id, subscription_version, paid_through_at, scheduled_at, state, obsolete_reason, created_at, updated_at) VALUES (?, ?, ?, 1, TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2026-12-25 00:00:00+00', 'OBSOLETE', 'PAID_PERIOD_EXPIRED', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
        expiredNoticeId,
        tenantId,
        subscriptionId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".billing_annual_renewal_notice(notice_id, tenant_id, subscription_id, subscription_version, paid_through_at, scheduled_at, state, obsolete_reason, created_at, updated_at) VALUES (?, ?, ?, 1, TIMESTAMPTZ '2028-01-01 00:00:00+00', TIMESTAMPTZ '2027-12-25 00:00:00+00', 'OBSOLETE', NULL, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(constraint_row.oid) "
                    + "FROM pg_catalog.pg_constraint constraint_row "
                    + "JOIN pg_catalog.pg_namespace namespace ON namespace.oid = constraint_row.connamespace "
                    + "WHERE namespace.nspname = ? AND constraint_row.conname = 'billing_event_inbox_disposition_check'",
                String.class,
                schema))
        .contains("EXPIRED_PERIOD");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'plan_code', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'tenant_id', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'subscription_id', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract",
                schema + ".billing_subscription_contract"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'state', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".billing_annual_renewal_notice",
                schema + ".billing_annual_renewal_notice",
                schema + ".billing_annual_renewal_notice",
                schema + ".billing_annual_renewal_notice",
                schema + ".billing_annual_renewal_notice"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'obsolete_reason', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE')",
                Boolean.class,
                schema + ".billing_annual_renewal_notice",
                schema + ".billing_annual_renewal_notice"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'tenant_id', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'operation_id', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'dunning_attempt_id', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'disposition', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'provider_reference', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'outcome', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'amount_minor_units', 'UPDATE')",
                Boolean.class,
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox",
                schema + ".billing_event_inbox"))
        .isTrue();
  }

  @Test
  void upgradesV38ToV40ForMonotonicPeriodExtensionsAndAttemptSnapshots() throws Exception {
    String schema = isolatedSchemaName();
    Flyway v38 = isolatedFlyway(schema, MigrationVersion.fromVersion("38"));
    assertThat(v38.migrate().migrationsExecuted).isEqualTo(38);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID sourceOperationId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_checkout_operation(operation_id, tenant_id, plan_code, amount_minor_units, currency, terms_version, offer_version, expected_subscription_version, provider_code, provider_account_id, provider_reference, state) VALUES (?, ?, 'MONTHLY', 2500, 'XTS', 'terms-v1', 'offer-v1', 0, 'provider', 'account', ?, 'CONFIRMED')",
        sourceOperationId,
        tenantId,
        "migration-source-" + sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', 3)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_subscription_contract(subscription_id, tenant_id, source_operation_id, amount_minor_units, currency, terms_version, offer_version, recorded_at) VALUES (?, ?, ?, 2500, 'XTS', 'terms-v1', 'offer-v1', TIMESTAMPTZ '2026-10-01 00:00:00+00')",
        subscriptionId,
        tenantId,
        sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription_history(event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', NULL, 'ACTIVE', TIMESTAMPTZ '2026-10-01 00:00:00+00', 1, ?)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        sourceOperationId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at, closed_at) VALUES (?, ?, ?, TIMESTAMPTZ '2026-10-01 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00', 'SUSPENDED', TIMESTAMPTZ '2026-10-02 00:00:00+00', TIMESTAMPTZ '2026-10-08 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, updated_at, external_idempotency_key, contract_amount_minor_units, contract_currency, contract_terms_version, contract_offer_version) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2026-10-01 00:00:00+00', 'SCHEDULED', TIMESTAMPTZ '2026-10-02 00:00:00+00', ?, 2500, 'XTS', 'terms-v1', 'offer-v1')",
        attemptId,
        tenantId,
        cycleId,
        attemptId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(2);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT plan_code FROM "
                    + schema
                    + ".billing_subscription_contract WHERE subscription_id = ?",
                String.class,
                subscriptionId))
        .isEqualTo("MONTHLY");
    jdbc.execute("GRANT USAGE ON SCHEMA " + schema + " TO agendamento_runtime");
    assertThatThrownBy(
            () -> {
              try (var connection = PostgresTestEnvironment.migrationDataSource().getConnection();
                  var statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema + ", public");
                statement.execute("SET ROLE agendamento_runtime");
                try (var update =
                    connection.prepareStatement(
                        "UPDATE "
                            + schema
                            + ".billing_subscription_contract SET plan_code = 'ANNUAL' WHERE subscription_id = ?")) {
                  update.setObject(1, subscriptionId);
                  update.executeUpdate();
                }
              }
            })
        .isInstanceOf(java.sql.SQLException.class)
        .hasMessageContaining("authenticated checkout evidence");
    assertThat(
            jdbc.queryForObject(
                "SELECT contract_plan_code FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("MONTHLY");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'contract_plan_code', 'SELECT') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'contract_plan_code', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE')",
                Boolean.class,
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt",
                schema + ".billing_dunning_attempt"))
        .isTrue();

    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription_history(event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', 'ACTIVE', 'ACTIVE', TIMESTAMPTZ '2026-10-23 00:00:00+00', 4, ?)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".subscription_history(event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, 'PAYMENT_CONFIRMED', 'ACTIVE', 'ACTIVE', TIMESTAMPTZ '2026-10-23 00:00:00+00', 1, ?)",
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    UUID extensionOperationId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".outbox_event(event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, schema_version, operation_id, correlation_id, payload) VALUES (?, ?, 'SUBSCRIPTION', ?, 4, 'SubscriptionPeriodExtended', 1, ?, ?, ?::jsonb)",
        UUID.randomUUID(),
        tenantId,
        subscriptionId,
        extensionOperationId,
        UUID.randomUUID(),
        "{\"subscriptionId\":\"" + subscriptionId + "\",\"version\":4}");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO "
                        + schema
                        + ".outbox_event(event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version, event_type, schema_version, operation_id, correlation_id, payload) VALUES (?, ?, 'SUBSCRIPTION', ?, 4, 'SubscriptionPeriodExtended', 1, ?, ?, ?::jsonb)",
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "{\"subscriptionId\":\"" + subscriptionId + "\",\"version\":3}"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'INSERT') AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".outbox_event",
                schema + ".outbox_event",
                schema + ".outbox_event"))
        .isTrue();
  }

  @Test
  void v37FailsClosedForLegacyProviderReferenceAndPreservesIt() {
    String schema = isolatedSchemaName();
    Flyway v36 = isolatedFlyway(schema, MigrationVersion.fromVersion("36"));
    assertThat(v36.migrate().migrationsExecuted).isEqualTo(36);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, grace_ends_at, version) VALUES (?, ?, 'PAST_DUE', NULL, NULL, 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 2)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at) VALUES (?, ?, ?, TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 'OPEN', TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, provider_reference, provider_code, ever_started, external_idempotency_key, updated_at) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2027-01-01 00:00:00+00', 'ACCEPTED', 'legacy-ref', 'legacy-provider', TRUE, ?, TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        attemptId,
        tenantId,
        cycleId,
        attemptId);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .hasMessageContaining(
            "legacy dunning provider references have no persisted account identity");
    assertThat(
            jdbc.queryForObject(
                "SELECT provider_reference || '|' || state FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("legacy-ref|ACCEPTED");
    assertThat(
            jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'billing_dunning_attempt' AND column_name = 'provider_account_id')",
                Boolean.class,
                schema))
        .isTrue();
  }

  @Test
  void v37FailsClosedForStartedAttemptWithoutTrustworthyContractSnapshot() {
    String schema = isolatedSchemaName();
    Flyway v36 = isolatedFlyway(schema, MigrationVersion.fromVersion("36"));
    assertThat(v36.migrate().migrationsExecuted).isEqualTo(36);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID cycleId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, grace_ends_at, version) VALUES (?, ?, 'PAST_DUE', NULL, NULL, 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 2)",
        subscriptionId,
        tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at) VALUES (?, ?, ?, TIMESTAMPTZ '2027-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-08 00:00:00+00', 'OPEN', TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        cycleId,
        tenantId,
        subscriptionId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".billing_dunning_attempt(attempt_id, tenant_id, cycle_id, logical_sequence, scheduled_at, state, ever_started, external_idempotency_key, updated_at) VALUES (?, ?, ?, 0, TIMESTAMPTZ '2027-01-01 00:00:00+00', 'ACCEPTED', TRUE, ?, TIMESTAMPTZ '2027-01-01 00:00:00+00')",
        attemptId,
        tenantId,
        cycleId,
        attemptId);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .hasMessageContaining("started legacy dunning attempt has no immutable contract snapshot");
    assertThat(
            jdbc.queryForObject(
                "SELECT state || ':' || ever_started FROM "
                    + schema
                    + ".billing_dunning_attempt WHERE attempt_id = ?",
                String.class,
                attemptId))
        .isEqualTo("ACCEPTED:true");
    assertThat(
            jdbc.queryForObject(
                "SELECT NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'billing_dunning_attempt' AND column_name = 'contract_amount_minor_units')",
                Boolean.class,
                schema))
        .isTrue();
  }

  @Test
  void v36FailsClosedOnLegacyGraceThatWasSevenLocalDaysAndPreservesRows() {
    String schema = isolatedSchemaName();
    Flyway v35 = isolatedFlyway(schema, MigrationVersion.fromVersion("35"));
    assertThat(v35.migrate().migrationsExecuted).isEqualTo(35);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    insertSubscriptionTenant(schema, tenantId);
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription(subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', NULL, NULL, 'MONTHLY', TIMESTAMPTZ '2026-03-07 17:00:00+00', 1)",
        subscriptionId,
        tenantId);
    UUID cycleId = UUID.randomUUID();
    jdbc.execute(
        (org.springframework.jdbc.core.ConnectionCallback<Void>)
            connection -> {
              try (var statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'America/New_York'");
                statement.execute(
                    "UPDATE "
                        + schema
                        + ".subscription SET status = 'PAST_DUE', grace_ends_at = paid_through_at + INTERVAL '7 days', version = 2 WHERE subscription_id = '"
                        + subscriptionId
                        + "'");
                statement.execute(
                    "INSERT INTO "
                        + schema
                        + ".billing_dunning_cycle(cycle_id, tenant_id, subscription_id, due_at, grace_ends_at, state, created_at) SELECT '"
                        + cycleId
                        + "', tenant_id, subscription_id, paid_through_at, grace_ends_at, 'OPEN', paid_through_at FROM "
                        + schema
                        + ".subscription WHERE subscription_id = '"
                        + subscriptionId
                        + "'");
              }
              return null;
            });
    InstantRow legacy =
        jdbc.queryForObject(
            "SELECT grace_ends_at FROM " + schema + ".subscription WHERE subscription_id = ?",
            (row, index) -> new InstantRow(row.getTimestamp(1).toInstant()),
            subscriptionId);
    assertThat(legacy.value()).isEqualTo(java.time.Instant.parse("2026-03-14T16:00:00Z"));

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(org.flywaydb.core.api.FlywayException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT grace_ends_at = TIMESTAMPTZ '2026-03-14 16:00:00+00' FROM "
                    + schema
                    + ".subscription WHERE subscription_id = ?",
                Boolean.class,
                subscriptionId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".billing_dunning_cycle WHERE cycle_id = ? AND grace_ends_at = TIMESTAMPTZ '2026-03-14 16:00:00+00'",
                Integer.class,
                cycleId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'billing_dunning_attempt' AND column_name = 'ever_started'",
                Integer.class,
                schema))
        .isZero();
  }

  @Test
  void v34RequiresTrueForEverySubscriptionStateAndPreservesValidRowsAndGrants() {
    String schema = isolatedSchemaName();
    Flyway v33 = isolatedFlyway(schema, MigrationVersion.fromVersion("33"));
    assertThat(v33.migrate().migrationsExecuted).isEqualTo(33);
    UUID tenantId = UUID.randomUUID();
    UUID trialId = UUID.randomUUID();
    seedTrialSubscription(schema, tenantId, trialId);
    UUID paidTenantId = UUID.randomUUID();
    insertSubscriptionTenant(schema, paidTenantId);
    UUID paidId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', NULL, NULL, 'ANNUAL', TIMESTAMPTZ '2027-01-01 00:00:00+00', 2)",
        paidId,
        paidTenantId);
    UUID convertedTrialTenantId = UUID.randomUUID();
    insertSubscriptionTenant(schema, convertedTrialTenantId);
    UUID convertedTrialId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-15 00:00:00+00', 'MONTHLY', TIMESTAMPTZ '2027-01-01 00:00:00+00', 2)",
        convertedTrialId,
        convertedTrialTenantId);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(7);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) LIKE '%IS TRUE%' FROM pg_constraint WHERE conname = 'ck_subscription_state' AND connamespace = ?::regnamespace",
                Boolean.class, schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".subscription WHERE status IN ('TRIALING','ACTIVE')",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'plan_code', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'paid_through_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_started_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_ends_at', 'UPDATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription"))
        .isTrue();

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".subscription SET plan_code = NULL WHERE subscription_id = ?",
                    paidId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".subscription SET status = 'UNKNOWN' WHERE subscription_id = ?",
                    trialId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".subscription SET trial_ends_at = NULL WHERE subscription_id = ?",
                    convertedTrialId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE " + schema + ".subscription SET version = 0 WHERE subscription_id = ?",
                    paidId))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".subscription WHERE status IN ('TRIALING','ACTIVE')",
                Integer.class))
        .isEqualTo(3);
  }

  @Test
  void v34PreflightFailsClosedAndLeavesInvalidLegacySubscriptionUntouched() {
    String schema = isolatedSchemaName();
    Flyway v33 = isolatedFlyway(schema, MigrationVersion.fromVersion("33"));
    assertThat(v33.migrate().migrationsExecuted).isEqualTo(33);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Legacy invalid', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "legacy-" + suffix());
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription (subscription_id, tenant_id, status, plan_code, paid_through_at, version) VALUES (?, ?, 'ACTIVE', NULL, TIMESTAMPTZ '2027-01-01 00:00:00+00', 1)",
        subscriptionId,
        tenantId);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("V34 cannot apply: incompatible legacy subscription state");
    assertThat(
            jdbc.queryForObject(
                "SELECT status = 'ACTIVE' AND plan_code IS NULL AND paid_through_at IS NOT NULL AND version = 1 FROM "
                    + schema
                    + ".subscription WHERE subscription_id = ?",
                Boolean.class,
                subscriptionId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".flyway_schema_history WHERE version = '34' AND success",
                Integer.class))
        .isZero();
  }

  @Test
  void v32AcceptsTrialAndPaymentHistoryTransitionsAndPreservesRuntimeGrants() {
    String schema = isolatedSchemaName();
    Flyway v30 = isolatedFlyway(schema, MigrationVersion.fromVersion("30"));
    assertThat(v30.migrate().migrationsExecuted).isEqualTo(30);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    seedTrialSubscription(schema, tenantId, subscriptionId);
    UUID grantEventId = UUID.randomUUID();
    UUID expirationEventId = UUID.randomUUID();
    insertSubscriptionHistory(
        schema, grantEventId, tenantId, subscriptionId, "TRIAL_GRANTED", null, "TRIALING", 1);
    insertSubscriptionHistory(
        schema,
        expirationEventId,
        tenantId,
        subscriptionId,
        "TRIAL_EXPIRED",
        "TRIALING",
        "SUSPENDED",
        2);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(10);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".subscription_history", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) LIKE '%IS TRUE%' FROM pg_constraint WHERE conname = 'ck_subscription_history_transition' AND connamespace = ?::regnamespace",
                Boolean.class, schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'version', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'plan_code', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'paid_through_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_started_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_ends_at', 'UPDATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history"))
        .isTrue();

    assertThatThrownBy(
            () ->
                insertSubscriptionHistory(
                    schema,
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    "TRIAL_EXPIRED",
                    null,
                    "SUSPENDED",
                    2))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                insertSubscriptionHistory(
                    schema,
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    "TRIAL_GRANTED",
                    null,
                    "SUSPENDED",
                    1))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                insertSubscriptionHistory(
                    schema,
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    "TRIAL_EXPIRED",
                    "TRIALING",
                    "SUSPENDED",
                    3))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                insertSubscriptionHistory(
                    schema,
                    UUID.randomUUID(),
                    tenantId,
                    subscriptionId,
                    "TRIAL_EXPIRED",
                    "CONFIRMED",
                    "SUSPENDED",
                    2))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForList(
                "SELECT event_id FROM " + schema + ".subscription_history ORDER BY event_id",
                UUID.class))
        .containsExactlyInAnyOrder(grantEventId, expirationEventId);
  }

  @Test
  void v32UpgradeGrantsOnlyThePaymentConfirmationColumnsAndRerunsCleanly() {
    String schema = isolatedSchemaName();
    Flyway v32 = isolatedFlyway(schema, MigrationVersion.fromVersion("32"));
    assertThat(v32.migrate().migrationsExecuted).isEqualTo(32);
    assertThat(
            jdbc.queryForObject(
                "SELECT NOT has_column_privilege('agendamento_runtime', ?, 'plan_code', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'paid_through_at', 'UPDATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription"))
        .isTrue();

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(8);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'version', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'plan_code', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'paid_through_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_started_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_ends_at', 'UPDATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription"))
        .isTrue();
  }

  @Test
  void v31FailsClosedOnIncompatibleV30HistoryWithoutChangingLegacyData() {
    String schema = isolatedSchemaName();
    Flyway v30 = isolatedFlyway(schema, MigrationVersion.fromVersion("30"));
    assertThat(v30.migrate().migrationsExecuted).isEqualTo(30);
    UUID tenantId = UUID.randomUUID();
    UUID subscriptionId = UUID.randomUUID();
    UUID legacyEventId = UUID.randomUUID();
    seedTrialSubscription(schema, tenantId, subscriptionId);
    insertSubscriptionHistory(
        schema, legacyEventId, tenantId, subscriptionId, "TRIAL_EXPIRED", null, "SUSPENDED", 2);
    Integer oldChecksum =
        jdbc.queryForObject(
            "SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version = '30'",
            Integer.class);

    assertThatThrownBy(() -> isolatedFlyway(schema).migrate())
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("V31 cannot apply")
        .hasStackTraceContaining("incompatible transitions");
    assertThat(
            jdbc.queryForObject(
                "SELECT from_status IS NULL AND to_status = 'SUSPENDED' AND version = 2 FROM "
                    + schema
                    + ".subscription_history WHERE event_id = ?",
                Boolean.class,
                legacyEventId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM "
                    + schema
                    + ".flyway_schema_history WHERE version = '31' AND success",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT checksum FROM " + schema + ".flyway_schema_history WHERE version = '30'",
                Integer.class))
        .isEqualTo(oldChecksum);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".subscription_history WHERE event_id = ?",
                Integer.class,
                legacyEventId))
        .isEqualTo(1);
  }

  @Test
  void upgradesV22ToV27AndGrantsOnlyEvidenceCorrelationUpdate() {
    String schema = isolatedSchemaName();
    Flyway v22 = isolatedFlyway(schema, MigrationVersion.fromVersion("22"));
    assertThat(v22.migrate().migrationsExecuted).isEqualTo(22);
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(18);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesV23ToV27AndPreservesFlywayHistoryAndRuntimePermissions() {
    String schema = isolatedSchemaName();
    Flyway v23 = isolatedFlyway(schema, MigrationVersion.fromVersion("23"));
    assertThat(v23.migrate().migrationsExecuted).isEqualTo(23);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(17);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesV24ToV27AndRerunsWithoutChangingRuntimePermissions() {
    String schema = isolatedSchemaName();
    Flyway v24 = isolatedFlyway(schema, MigrationVersion.fromVersion("24"));
    assertThat(v24.migrate().migrationsExecuted).isEqualTo(24);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(16);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'reminder_eligible', 'SELECT') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'reminder_eligible', 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent",
                schema + ".notification_intent"))
        .isTrue();
  }

  @Test
  void upgradesV25ToV27WithTenantPolicyConstraintsAndRuntimeLeastPrivilege() {
    String schema = isolatedSchemaName();
    Flyway v25 = isolatedFlyway(schema, MigrationVersion.fromVersion("25"));
    assertThat(v25.migrate().migrationsExecuted).isEqualTo(25);
    UUID tenantId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Policy', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "policy-" + suffix());

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(15);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".client_no_show_policy",
                schema + ".client_no_show_policy",
                schema + ".client_no_show_policy",
                schema + ".client_no_show_policy",
                schema + ".client_no_show_policy"))
        .isTrue();
    jdbc.update(
        "INSERT INTO " + schema + ".client_no_show_policy (tenant_id, threshold) VALUES (?, 5)",
        tenantId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE "
                        + schema
                        + ".client_no_show_policy SET threshold = 6 WHERE tenant_id = ?",
                    tenantId))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT threshold FROM " + schema + ".client_no_show_policy WHERE tenant_id = ?",
                Integer.class,
                tenantId))
        .isEqualTo(5);
  }

  @Test
  void upgradesV1SchemaToV2AndKeepsMigrationHistoryValid() {
    String schema = isolatedSchemaName();
    Flyway v1 = isolatedFlyway(schema, MigrationVersion.fromVersion("1"));
    v1.migrate();

    assertThat(v1.info().current().getVersion().getVersion()).isEqualTo("1");
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(39);
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
            "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29",
            "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40");
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(38);
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
            "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29",
            "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40");
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(35);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".flyway_schema_history WHERE success",
                Integer.class))
        .isEqualTo(41);
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
  void upgradesPublishedV6SchemaToLatestAndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v6 = isolatedFlyway(schema, MigrationVersion.fromVersion("6"));
    assertThat(v6.migrate().migrationsExecuted).isEqualTo(6);
    assertThat(v6.info().current().getVersion().getVersion()).isEqualTo("6");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(34);
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
        .isEqualTo(41);
  }

  @Test
  void upgradesPublishedV7SchemaToLatestAndKeepsFlywayValid() {
    String schema = isolatedSchemaName();
    Flyway v7 = isolatedFlyway(schema, MigrationVersion.fromVersion("7"));
    assertThat(v7.migrate().migrationsExecuted).isEqualTo(7);
    assertThat(v7.info().current().getVersion().getVersion()).isEqualTo("7");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(33);
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
        .isEqualTo(41);
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(32);
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(32);
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
  void upgradesV9ToV26AndRerunsWithoutChangingHistory() {
    String schema = isolatedSchemaName();
    Flyway v9 = isolatedFlyway(schema, MigrationVersion.fromVersion("9"));
    assertThat(v9.migrate().migrationsExecuted).isEqualTo(9);
    assertThat(v9.info().current().getVersion().getVersion()).isEqualTo("9");
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(31);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT to_regclass(?||'.schedule_exception')", String.class, schema))
        .isEqualTo(schema + ".schedule_exception");
    assertThat(
            jdbc.queryForObject("SELECT to_regclass(?||'.blocked_period')", String.class, schema))
        .isEqualTo(schema + ".blocked_period");
  }

  @Test
  void upgradesV10SpecialPeriodsWithoutChangingLocalTimesAndRerunsLatest() {
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(30);
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
        .isEqualTo(41);
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(29);
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
  void upgradesPublishedV13ToV26WithoutRewritingLegacyDigestsAndKeepsRuntimeLeastPrivilege() {
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(27);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesPublishedV14SchemaToV26AndRerunsWithoutChangingHistoryOrPrivileges() {
    String schema = isolatedSchemaName();
    Flyway v14 = isolatedFlyway(schema, MigrationVersion.fromVersion("14"));
    assertThat(v14.migrate().migrationsExecuted).isEqualTo(14);
    assertThat(v14.info().current().getVersion().getVersion()).isEqualTo("14");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(26);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesPublishedV15ToV26AndGrantsOnlyOutcomeAndRescheduleColumns() {
    String schema = isolatedSchemaName();
    Flyway v15 = isolatedFlyway(schema, MigrationVersion.fromVersion("15"));
    assertThat(v15.migrate().migrationsExecuted).isEqualTo(15);
    assertThat(v15.info().current().getVersion().getVersion()).isEqualTo("15");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(25);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesV16ToV26WithValidHistoryAndKeepsRuntimePrivileges() {
    String schema = isolatedSchemaName();
    Flyway v16 = isolatedFlyway(schema, MigrationVersion.fromVersion("16"));
    assertThat(v16.migrate().migrationsExecuted).isEqualTo(16);
    Flyway upgrade = isolatedFlyway(schema);

    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(24);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesV17ToV26AndRerunsWithLeastPrivilegeOutcomeGrant() {
    String schema = isolatedSchemaName();
    Flyway v17 = isolatedFlyway(schema, MigrationVersion.fromVersion("17"));
    assertThat(v17.migrate().migrationsExecuted).isEqualTo(17);
    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(23);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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
  void upgradesV26ToV27WithCorrectionReferenceAndRuntimeLeastPrivilege() {
    String schema = isolatedSchemaName();
    Flyway v26 = isolatedFlyway(schema, MigrationVersion.fromVersion("26"));
    assertThat(v26.migrate().migrationsExecuted).isEqualTo(26);

    Flyway upgrade = isolatedFlyway(schema, MigrationVersion.fromVersion("27"));
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("27");
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = ? AND table_name = 'appointment_history' AND column_name = 'corrects_history_id') "
                    + "AND EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = ? AND indexname = 'uq_appointment_history_corrected_fact') "
                    + "AND EXISTS (SELECT 1 FROM pg_constraint WHERE connamespace = ?::regnamespace AND conname = 'fk_appointment_history_correction')",
                Boolean.class,
                schema,
                schema,
                schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history",
                schema + ".appointment_history"))
        .isTrue();
  }

  @Test
  void upgradesV27ToV31WithTenantRestrictionsTrialAndRuntimeLeastPrivilege() {
    String schema = isolatedSchemaName();
    Flyway v27 = isolatedFlyway(schema, MigrationVersion.fromVersion("27"));
    assertThat(v27.migrate().migrationsExecuted).isEqualTo(27);

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(13);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'client_booking_restriction') "
                    + "AND EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = 'client_booking_restriction_history') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema,
                schema,
                schema + ".client_booking_restriction",
                schema + ".client_booking_restriction",
                schema + ".client_booking_restriction",
                schema + ".client_booking_restriction",
                schema + ".client_booking_restriction",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history"))
        .isTrue();
  }

  @Test
  void upgradesV28ToV31WithReconciliationHistoryTrialAndAuditAllowlist() {
    String schema = isolatedSchemaName();
    Flyway v28 = isolatedFlyway(schema, MigrationVersion.fromVersion("28"));
    assertThat(v28.migrate().migrationsExecuted).isEqualTo(28);
    assertThat(v28.info().current().getVersion().getVersion()).isEqualTo("28");

    Flyway upgrade = isolatedFlyway(schema);
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(12);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) LIKE '%RECONCILED%' FROM pg_constraint WHERE conname = 'ck_client_restriction_history_event' AND connamespace = ?::regnamespace",
                Boolean.class, schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_subscription_history_tenant_subscription' AND contype = 'f' AND connamespace = ?::regnamespace)",
                Boolean.class,
                schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) LIKE '%CLIENT_SELF_BOOKING_RECONCILED%' FROM pg_constraint WHERE conname = 'ck_audit_action' AND connamespace = ?::regnamespace",
                Boolean.class, schema))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'UPDATE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".commercial_trial_grant",
                schema + ".commercial_trial_grant",
                schema + ".commercial_trial_grant",
                schema + ".commercial_trial_grant",
                schema + ".commercial_trial_grant",
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history",
                schema + ".subscription_history"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_column_privilege('agendamento_runtime', ?, 'status', 'UPDATE') "
                    + "AND has_column_privilege('agendamento_runtime', ?, 'version', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_started_at', 'UPDATE') "
                    + "AND NOT has_column_privilege('agendamento_runtime', ?, 'trial_ends_at', 'UPDATE')",
                Boolean.class,
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription",
                schema + ".subscription"))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT has_table_privilege('agendamento_runtime', ?, 'SELECT') "
                    + "AND has_table_privilege('agendamento_runtime', ?, 'INSERT') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'DELETE') "
                    + "AND NOT has_table_privilege('agendamento_runtime', ?, 'TRUNCATE')",
                Boolean.class,
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history",
                schema + ".client_booking_restriction_history"))
        .isTrue();
  }

  @Test
  void upgradesV19ClientSessionsWithLegacyRowsThroughV27WithoutRewritingRows() {
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
    assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(21);
    upgrade.validate();
    assertThat(upgrade.migrate().migrationsExecuted).isZero();
    assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("40");
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

  private void seedTrialSubscription(String schema, UUID tenantId, UUID subscriptionId) {
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'History fixture', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "history-" + suffix());
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription (subscription_id, tenant_id, status, trial_started_at, trial_ends_at, version) VALUES (?, ?, 'TRIALING', TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-15 00:00:00+00', 1)",
        subscriptionId,
        tenantId);
  }

  private void insertSubscriptionTenant(String schema, UUID tenantId) {
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Subscription fixture', ?, 'UTC', 'ACTIVE')",
        tenantId,
        "subscription-" + suffix());
  }

  private void insertSubscriptionHistory(
      String schema,
      UUID eventId,
      UUID tenantId,
      UUID subscriptionId,
      String eventType,
      String fromStatus,
      String toStatus,
      long version) {
    jdbc.update(
        "INSERT INTO "
            + schema
            + ".subscription_history (event_id, tenant_id, subscription_id, event_type, from_status, to_status, event_at, version, operation_id) VALUES (?, ?, ?, ?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', ?, ?)",
        eventId,
        tenantId,
        subscriptionId,
        eventType,
        fromStatus,
        toStatus,
        version,
        UUID.randomUUID());
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

  private record InstantRow(java.time.Instant value) {}

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
