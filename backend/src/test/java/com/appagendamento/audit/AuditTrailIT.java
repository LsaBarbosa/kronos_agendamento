package com.appagendamento.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.audit.application.ListTenantAuditEvents;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.application.RenameTenant;
import com.appagendamento.tenant.domain.Tenant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SpringBootTest
class AuditTrailIT {

  @Autowired private JdbcTemplate runtimeJdbc;
  @Autowired private CreateTenant createTenant;
  @Autowired private RenameTenant renameTenant;
  @Autowired private ListTenantAuditEvents listEvents;
  @Autowired private PlatformTransactionManager transactionManager;
  private JdbcTemplate migrationJdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void privilegedFixtures() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
  }

  @Test
  void commitsAllowlistedEventsAndRestrictsQueryToAuthenticatedTenant() {
    Tenant own = createTenant.execute("Nome sensível não auditado", "audit-a-" + suffix(), "UTC");
    Tenant other = createTenant.execute("Outro tenant", "audit-b-" + suffix(), "UTC");
    AuthenticatedAdmin actor = actor(own.tenantId());

    UUID traceId = UUID.randomUUID();
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationIdFilter.ATTRIBUTE, traceId.toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      renameTenant.execute(
          actor, own.tenantId(), "rename-key-" + UUID.randomUUID(), "Novo nome secreto", 0);
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }

    var ownEvents = listEvents.execute(actor);
    assertThat(ownEvents).hasSize(2);
    assertThat(ownEvents).extracting("tenantId").containsOnly(own.tenantId());
    assertThat(ownEvents).extracting("action").contains("TENANT_CREATED", "TENANT_RENAMED");
    assertThat(ownEvents).extracting("traceId").isNotNull();
    assertThat(ownEvents).extracting("correlationId").isNotNull();
    assertThat(ownEvents)
        .filteredOn(event -> event.action().equals("TENANT_RENAMED"))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.traceId()).isEqualTo(traceId);
              assertThat(event.correlationId()).isEqualTo(traceId);
            });
    assertThat(ownEvents)
        .extracting("detailsJson")
        .allSatisfy(
            details -> {
              assertThat(details.toString()).doesNotContain("Nome sensível", "Novo nome secreto");
            });
    assertThat(listEvents.execute(actor(other.tenantId()))).hasSize(1);

    assertThat(runtimeJdbc.queryForObject("SELECT current_user", String.class))
        .isEqualTo("agendamento_app");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user",
                Boolean.class))
        .isFalse();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT pg_get_userbyid(relowner) FROM pg_class WHERE oid = 'agendamento.audit_event'::regclass",
                String.class))
        .isEqualTo("agendamento_audit_owner");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT pg_has_role(current_user, 'agendamento_audit_owner', 'MEMBER')",
                Boolean.class))
        .isFalse();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM pg_auth_members WHERE member = 'agendamento_app'::regrole "
                    + "AND roleid = 'agendamento_runtime'::regrole",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM pg_auth_members WHERE member = 'agendamento_app'::regrole",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'agendamento.audit_event', 'SELECT') "
                    + "AND has_table_privilege(current_user, 'agendamento.audit_event', 'INSERT')",
                Boolean.class))
        .isTrue();
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'agendamento.audit_event', 'UPDATE') "
                    + "OR has_table_privilege(current_user, 'agendamento.audit_event', 'DELETE') "
                    + "OR has_table_privilege(current_user, 'agendamento.audit_event', 'TRUNCATE')",
                Boolean.class))
        .isFalse();
    assertThatThrownBy(
            () ->
                runtimeJdbc.update(
                    "UPDATE agendamento.audit_event SET action = 'TAMPERED' WHERE tenant_id = ?",
                    own.tenantId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                runtimeJdbc.update(
                    "DELETE FROM agendamento.audit_event WHERE tenant_id = ?", own.tenantId()))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(() -> runtimeJdbc.execute("TRUNCATE agendamento.audit_event"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void transactionRollbackRemovesMutationAndItsSuccessEvent() {
    AtomicReference<Tenant> createdTenant = new AtomicReference<>();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);

    assertThatThrownBy(
            () ->
                transaction.executeWithoutResult(
                    status -> {
                      createdTenant.set(
                          createTenant.execute("Will roll back", "rollback-" + suffix(), "UTC"));
                      throw new IllegalStateException("force rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.tenant WHERE tenant_id = ?",
                Integer.class,
                createdTenant.get().tenantId()))
        .isZero();
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE action = 'TENANT_CREATED' AND tenant_id = ?",
                Integer.class,
                createdTenant.get().tenantId()))
        .isZero();
  }

  @Test
  void auditInsertFailureRollsBackCriticalTenantMutation() {
    String slug = "audit-fail-" + suffix();
    Tenant unrelatedTenantBeforeTrigger =
        createTenant.execute("Unrelated tenant", "audit-unrelated-" + suffix(), "UTC");
    UUID operationCorrelationId = UUID.randomUUID();
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_audit_insert() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.correlation_id = '%s'::uuid OR NEW.trace_id = '%s'::uuid THEN
            RAISE EXCEPTION 'audit storage unavailable';
          END IF;
          RETURN NEW;
        END
        $$
        """
            .formatted(operationCorrelationId, operationCorrelationId));
    migrationJdbc.execute(
        """
        CREATE TRIGGER reject_audit_insert BEFORE INSERT ON agendamento.audit_event
        FOR EACH ROW EXECUTE FUNCTION agendamento.reject_audit_insert()
        """);
    try {
      Tenant unrelatedTenantDuringTrigger =
          createTenant.execute(
              "Another unrelated tenant", "audit-during-trigger-" + suffix(), "UTC");

      assertThatThrownBy(
              () ->
                  withCorrelationId(
                      operationCorrelationId,
                      () -> createTenant.execute("Atomic tenant", slug, "UTC")))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              migrationJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.tenant WHERE slug = ?", Integer.class, slug))
          .isZero();
      assertNoAuditEventForCorrelation(operationCorrelationId);
      assertTenantCreatedEventExists(unrelatedTenantBeforeTrigger);
      assertTenantCreatedEventExists(unrelatedTenantDuringTrigger);
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_audit_insert ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_audit_insert()");
    }
  }

  @Test
  void auditRollbackAssertionDetectsAnEventWithTheOperationsCorrelationId() {
    UUID correlationId = UUID.randomUUID();

    withCorrelationId(
        correlationId,
        () -> createTenant.execute("Committed tenant", "audit-positive-" + suffix(), "UTC"));

    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE correlation_id = ? OR trace_id = ?",
                Integer.class,
                correlationId,
                correlationId))
        .isEqualTo(1);
    assertThatThrownBy(() -> assertNoAuditEventForCorrelation(correlationId))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void auditInsertFailureRollsBackTenantRenameAndKeepsPriorEventOnly() {
    Tenant tenant = createTenant.execute("Before rename", "rename-fail-" + suffix(), "UTC");
    int previousEventCount =
        migrationJdbc.queryForObject(
            "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ?",
            Integer.class,
            tenant.tenantId());
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_rename_audit_insert() RETURNS trigger
        LANGUAGE plpgsql AS $$
        BEGIN
          IF NEW.action = 'TENANT_RENAMED' THEN
            RAISE EXCEPTION 'audit storage unavailable';
          END IF;
          RETURN NEW;
        END
        $$
        """);
    migrationJdbc.execute(
        """
        CREATE TRIGGER reject_rename_audit_insert BEFORE INSERT ON agendamento.audit_event
        FOR EACH ROW EXECUTE FUNCTION agendamento.reject_rename_audit_insert()
        """);
    try {
      assertThatThrownBy(
              () ->
                  renameTenant.execute(
                      actor(tenant.tenantId()),
                      tenant.tenantId(),
                      "rename-key-" + UUID.randomUUID(),
                      "Uncommitted rename",
                      0))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              migrationJdbc.queryForObject(
                  "SELECT name FROM agendamento.tenant WHERE tenant_id = ?",
                  String.class,
                  tenant.tenantId()))
          .isEqualTo("Before rename");
      assertThat(
              migrationJdbc.queryForObject(
                  "SELECT version FROM agendamento.tenant WHERE tenant_id = ?",
                  Long.class,
                  tenant.tenantId()))
          .isZero();
      assertThat(
              migrationJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ?",
                  Integer.class,
                  tenant.tenantId()))
          .isEqualTo(previousEventCount);
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_rename_audit_insert ON agendamento.audit_event");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_rename_audit_insert()");
    }
  }

  private AuthenticatedAdmin actor(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        "https://identity.test/realms/agendamento",
        subject);
    migrationJdbc.update(
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

  private void assertNoAuditEventForCorrelation(UUID correlationId) {
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE correlation_id = ? OR trace_id = ?",
                Integer.class,
                correlationId,
                correlationId))
        .as("no audit event from the operation under test is committed")
        .isZero();
  }

  private void assertTenantCreatedEventExists(Tenant tenant) {
    assertThat(
            migrationJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'TENANT_CREATED'",
                Integer.class,
                tenant.tenantId()))
        .as("the unrelated tenant's legitimate creation event remains persisted")
        .isEqualTo(1);
  }

  private static void withCorrelationId(UUID correlationId, Runnable action) {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setAttribute(CorrelationIdFilter.ATTRIBUTE, correlationId.toString());
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    try {
      action.run();
    } finally {
      RequestContextHolder.resetRequestAttributes();
    }
  }

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
