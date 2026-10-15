package com.appagendamento.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.catalog.application.CreateService;
import com.appagendamento.catalog.application.GetService;
import com.appagendamento.catalog.application.ListServices;
import com.appagendamento.catalog.application.SetServiceStatus;
import com.appagendamento.catalog.application.UpdateService;
import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceNameAlreadyExistsException;
import com.appagendamento.catalog.domain.ServiceNotFoundException;
import com.appagendamento.catalog.domain.ServiceStatus;
import com.appagendamento.catalog.domain.ServiceVersionConflictException;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.idempotency.application.IdempotencyKeyReusedException;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class ServiceCatalogIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private CreateService createService;
  @Autowired private UpdateService updateService;
  @Autowired private SetServiceStatus setServiceStatus;
  @Autowired private GetService getService;
  @Autowired private ListServices listServices;
  @Autowired private MockMvc mvc;
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
  void createsReplaysUpdatesActivatesDeactivatesAndAuditsInSameTransaction() {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);
    String key = "create-" + suffix();
    var created =
        createService.execute(
            actor, key, "  Massagem  ", "Relaxante", 60, 0, ServiceStatus.INACTIVE);
    Service original = created.value();

    assertThat(original.name()).isEqualTo("Massagem");
    assertThat(original.status()).isEqualTo(ServiceStatus.INACTIVE);
    assertThat(created.replayed()).isFalse();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.service WHERE tenant_id = ?",
                Integer.class,
                tenant))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'SERVICE_CREATED'",
                Integer.class,
                tenant))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.outbox_event WHERE tenant_id = ? AND event_type = 'ServiceCreated'",
                Integer.class,
                tenant))
        .isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT details::text FROM agendamento.audit_event WHERE target_id = ? AND action = 'SERVICE_CREATED'",
                String.class,
                original.serviceId()))
        .doesNotContain("Massagem", "Relaxante");
    assertThat(
            appJdbc.queryForObject(
                "SELECT payload::text FROM agendamento.outbox_event WHERE aggregate_id = ?",
                String.class,
                original.serviceId()))
        .doesNotContain("Massagem", "Relaxante");

    var replay =
        createService.execute(actor, key, "Massagem", "Relaxante", 60, 0, ServiceStatus.INACTIVE);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.value().serviceId()).isEqualTo(original.serviceId());
    assertThat(count("service", tenant)).isEqualTo(1);
    assertThat(count("audit_event", tenant)).isEqualTo(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void sameKeyAndPayloadReplaysAndDifferentPayloadConflicts() {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);
    String key = "catalog-key-" + suffix();
    var first = createService.execute(actor, key, "Corte", null, 30, 0, ServiceStatus.ACTIVE);
    var replay = createService.execute(actor, key, " Corte ", "", 30, 0, ServiceStatus.ACTIVE);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.operationId()).isEqualTo(first.operationId());
    assertThat(replay.value().serviceId()).isEqualTo(first.value().serviceId());
    assertThatThrownBy(
            () ->
                createService.execute(
                    actor, key, "Corte Premium", null, 30, 0, ServiceStatus.ACTIVE))
        .isExactlyInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(count("service", tenant)).isEqualTo(1);
    assertThat(count("audit_event", tenant)).isEqualTo(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void editsUseCasAndActivationReleasesAndReservesActiveNameAtomically() {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);
    Service first =
        createService
            .execute(actor, "first-" + suffix(), "Massagem", null, 30, 0, ServiceStatus.ACTIVE)
            .value();
    Service second =
        createService
            .execute(actor, "second-" + suffix(), "Corte", null, 45, 15, ServiceStatus.ACTIVE)
            .value();

    Service edited =
        updateService
            .execute(
                actor,
                second.serviceId(),
                "edit-" + suffix(),
                second.version(),
                "Corte Especial",
                "  nova descrição  ",
                60,
                null)
            .value();
    assertThat(edited.version()).isEqualTo(1);
    assertThat(edited.description()).isEqualTo("nova descrição");
    assertThatThrownBy(
            () ->
                updateService.execute(
                    actor,
                    second.serviceId(),
                    "stale-" + suffix(),
                    0,
                    "Outro nome",
                    null,
                    null,
                    null))
        .isExactlyInstanceOf(ServiceVersionConflictException.class);
    assertThatThrownBy(
            () ->
                updateService.execute(
                    actor,
                    second.serviceId(),
                    "dupe-" + suffix(),
                    1,
                    first.name(),
                    null,
                    null,
                    null))
        .isExactlyInstanceOf(ServiceNameAlreadyExistsException.class);

    Service inactive =
        setServiceStatus
            .execute(actor, first.serviceId(), "deactivate-" + suffix(), 0, ServiceStatus.INACTIVE)
            .value();
    Service rename =
        updateService
            .execute(
                actor, second.serviceId(), "rename-" + suffix(), 1, first.name(), null, null, null)
            .value();
    assertThat(inactive.status()).isEqualTo(ServiceStatus.INACTIVE);
    assertThat(rename.version()).isEqualTo(2);
    assertThatThrownBy(
            () ->
                setServiceStatus.execute(
                    actor,
                    first.serviceId(),
                    "activate-conflict-" + suffix(),
                    1,
                    ServiceStatus.ACTIVE))
        .isExactlyInstanceOf(ServiceNameAlreadyExistsException.class);
    assertThat(getService.execute(actor, second.serviceId()).name()).isEqualTo("Massagem");
  }

  @Test
  void tenantScopedApplicationQueriesConcealForeignIdentifiers() {
    UUID ownTenant = tenant();
    UUID otherTenant = tenant();
    AuthenticatedAdmin ownAdmin = actor(ownTenant);
    String sharedKey = "tenant-isolated-key-" + suffix();
    Service own =
        createService
            .execute(ownAdmin, sharedKey, "Serviço", null, 30, 0, ServiceStatus.ACTIVE)
            .value();
    Service foreign =
        createService
            .execute(actor(otherTenant), sharedKey, "Serviço", null, 30, 0, ServiceStatus.ACTIVE)
            .value();

    assertThat(listServices.execute(ownAdmin))
        .extracting(Service::serviceId)
        .containsExactly(own.serviceId());
    assertThat(own.serviceId()).isNotEqualTo(foreign.serviceId());
    assertThatThrownBy(() -> getService.execute(ownAdmin, foreign.serviceId()))
        .isExactlyInstanceOf(ServiceNotFoundException.class);
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.service WHERE tenant_id = ?",
                Integer.class,
                otherTenant))
        .isEqualTo(1);
  }

  @Test
  void auditAndOutboxFailuresRollbackServiceAndIdempotencyTogether() {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);

    migrationJdbc.execute("REVOKE INSERT ON agendamento.audit_event FROM agendamento_runtime");
    try {
      assertThatThrownBy(
              () ->
                  createService.execute(
                      actor,
                      "audit-failure-" + suffix(),
                      "Sem auditoria",
                      null,
                      30,
                      0,
                      ServiceStatus.ACTIVE))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      migrationJdbc.execute("GRANT INSERT ON agendamento.audit_event TO agendamento_runtime");
    }
    assertCatalogWritesAbsent(tenant);

    migrationJdbc.execute("REVOKE INSERT ON agendamento.outbox_event FROM agendamento_runtime");
    try {
      assertThatThrownBy(
              () ->
                  createService.execute(
                      actor,
                      "outbox-failure-" + suffix(),
                      "Sem outbox",
                      null,
                      30,
                      0,
                      ServiceStatus.ACTIVE))
          .isInstanceOf(org.springframework.dao.DataAccessException.class);
    } finally {
      migrationJdbc.execute("GRANT INSERT ON agendamento.outbox_event TO agendamento_runtime");
    }
    assertCatalogWritesAbsent(tenant);
  }

  @Test
  void concurrentActiveNamesHaveOneWinnerInPostgres() throws Exception {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);
    String name = "Race " + suffix();
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<Boolean> first = writers.submit(() -> createAfterBarrier(start, actor, name));
      Future<Boolean> second = writers.submit(() -> createAfterBarrier(start, actor, name));
      assertThat(first.get()).isNotEqualTo(second.get());
    }
    assertThat(count("service", tenant)).isEqualTo(1);
    assertThat(count("audit_event", tenant)).isEqualTo(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void concurrentSameKeyExecutesOneLogicalCatalogCommand() throws Exception {
    UUID tenant = tenant();
    AuthenticatedAdmin actor = actor(tenant);
    String key = "same-key-race-" + suffix();
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService writers = Executors.newFixedThreadPool(2)) {
      Future<IdempotentCommandResult<Service>> first =
          writers.submit(() -> createWithSameKey(start, actor, key));
      Future<IdempotentCommandResult<Service>> second =
          writers.submit(() -> createWithSameKey(start, actor, key));
      var firstResult = first.get();
      var secondResult = second.get();
      assertThat(List.of(firstResult.replayed(), secondResult.replayed()))
          .containsExactlyInAnyOrder(false, true);
      assertThat(firstResult.operationId()).isEqualTo(secondResult.operationId());
      assertThat(firstResult.value().serviceId()).isEqualTo(secondResult.value().serviceId());
    }
    assertThat(count("service", tenant)).isEqualTo(1);
    assertThat(count("audit_event", tenant)).isEqualTo(1);
    assertThat(count("outbox_event", tenant)).isEqualTo(1);
  }

  @Test
  void protectedHttpReadsAndMutationsAreTenantScopedAndRequireCsrf() throws Exception {
    UUID ownTenant = tenant();
    UUID otherTenant = tenant();
    AuthenticatedAdmin ownAdmin = actor(ownTenant);
    Service own =
        createService
            .execute(
                ownAdmin,
                "http-own-" + suffix(),
                "Serviço próprio",
                null,
                30,
                0,
                ServiceStatus.ACTIVE)
            .value();
    Service foreign =
        createService
            .execute(
                actor(otherTenant),
                "http-foreign-" + suffix(),
                "Serviço privado",
                null,
                30,
                0,
                ServiceStatus.ACTIVE)
            .value();
    var authentication =
        new UsernamePasswordAuthenticationToken(
            ownAdmin, "test", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));

    mvc.perform(get("/api/v1/admin/services")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/services").with(authentication(authentication)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].serviceId").value(own.serviceId().toString()));
    mvc.perform(
            get("/api/v1/admin/services/{id}", foreign.serviceId())
                .with(authentication(authentication)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SERVICE_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/admin/services/{id}", UUID.randomUUID())
                .with(authentication(authentication)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SERVICE_NOT_FOUND"));

    long auditBefore = count("audit_event", ownTenant);
    long outboxBefore = count("outbox_event", ownTenant);
    mvc.perform(
            patch("/api/v1/admin/services/{id}", foreign.serviceId())
                .with(authentication(authentication))
                .with(csrf())
                .header("Idempotency-Key", "cross-" + suffix())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Tentativa\",\"expectedVersion\":0}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("SERVICE_NOT_FOUND"));
    mvc.perform(
            post("/api/v1/admin/services")
                .with(authentication(authentication))
                .header("Idempotency-Key", "no-csrf-" + suffix())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Sem CSRF\",\"durationMinutes\":30,\"bufferAfterMinutes\":0,\"status\":\"ACTIVE\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/services")
                .with(authentication(authentication))
                .with(csrf())
                .header("Idempotency-Key", "http-create-" + suffix())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"Novo serviço\",\"durationMinutes\":30,\"bufferAfterMinutes\":0,\"status\":\"ACTIVE\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Novo serviço"));
    assertThat(count("audit_event", ownTenant)).isEqualTo(auditBefore + 1);
    assertThat(count("outbox_event", ownTenant)).isEqualTo(outboxBefore + 1);
    assertThat(count("service", ownTenant)).isEqualTo(2);
    assertThat(
            appJdbc.queryForObject(
                "SELECT has_table_privilege(current_user, 'agendamento.service', 'SELECT') "
                    + "AND has_table_privilege(current_user, 'agendamento.service', 'INSERT') "
                    + "AND has_table_privilege(current_user, 'agendamento.service', 'UPDATE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.service', 'DELETE') "
                    + "AND NOT has_table_privilege(current_user, 'agendamento.service', 'TRUNCATE')",
                Boolean.class))
        .isTrue();
  }

  @Test
  void revokedMembershipBlocksCatalogHttpEvenWithAuthenticatedPrincipal() throws Exception {
    UUID tenant = tenant();
    AuthenticatedAdmin admin = actor(tenant);
    var auth =
        new UsernamePasswordAuthenticationToken(
            admin, "test", List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN")));
    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE tenant_id = ?",
        tenant);

    mvc.perform(get("/api/v1/admin/services").with(authentication(auth)))
        .andExpect(status().isForbidden());
  }

  private boolean createAfterBarrier(CyclicBarrier barrier, AuthenticatedAdmin actor, String name)
      throws Exception {
    barrier.await();
    try {
      createService.execute(
          actor, "race-" + UUID.randomUUID(), name, null, 30, 0, ServiceStatus.ACTIVE);
      return true;
    } catch (ServiceNameAlreadyExistsException duplicate) {
      return false;
    }
  }

  private IdempotentCommandResult<Service> createWithSameKey(
      CyclicBarrier barrier, AuthenticatedAdmin actor, String key) throws Exception {
    barrier.await();
    return createService.execute(
        actor, key, "Corrida de replay", null, 30, 0, ServiceStatus.ACTIVE);
  }

  private long count(String table, UUID tenantId) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?", Long.class, tenantId);
  }

  private void assertCatalogWritesAbsent(UUID tenant) {
    assertThat(count("service", tenant)).isZero();
    assertThat(count("audit_event", tenant)).isZero();
    assertThat(count("outbox_event", tenant)).isZero();
    assertThat(count("idempotency_record", tenant)).isZero();
  }

  private UUID tenant() {
    UUID tenantId = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "Tenant teste",
        "catalog-" + suffix());
    return tenantId;
  }

  private AuthenticatedAdmin actor(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    return new TestAdmin(adminId, subject, tenantId);
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

  private static String suffix() {
    return UUID.randomUUID().toString().substring(0, 8);
  }
}
