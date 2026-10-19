package com.appagendamento.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.appagendamento.clients.application.ClientNoShowPolicyVersionConflictException;
import com.appagendamento.clients.application.ReplaceClientNoShowPolicy;
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
class ClientNoShowPolicyIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private static final String PATH = "/api/v1/admin/no-show-policy";

  @Autowired private JdbcTemplate appJdbc;
  @Autowired private ReplaceClientNoShowPolicy replacePolicy;
  @Autowired private MockMvc mvc;
  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private AuthenticatedAdmin actor;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void createTenantAndAdmin() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        tenantId,
        "No-show policy test",
        "no-show-" + tenantId.toString().substring(0, 8),
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
    actor = new TestAdmin(adminId, subject, tenantId);
  }

  @Test
  void policyIsExplicitlyUnconfiguredAndTenantScoped() throws Exception {
    mvc.perform(get(PATH).with(authentication(token(actor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.threshold").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.restrictionStatus").value("NOT_TRACKED"));

    UUID otherTenant = UUID.randomUUID();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        otherTenant,
        "Other tenant",
        "other-" + otherTenant.toString().substring(0, 8),
        "America/Sao_Paulo");
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.client_no_show_policy WHERE tenant_id = ?",
                Integer.class,
                otherTenant))
        .isZero();
  }

  @Test
  void policyBelongsOnlyToTheAuthenticatedTenantAndRevokedMembershipIsRejected() throws Exception {
    replacePolicy.execute(actor, "tenant-policy-" + UUID.randomUUID(), 0, 4);
    UUID otherTenant = UUID.randomUUID();
    UUID otherAdmin = UUID.randomUUID();
    String otherSubject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        otherTenant,
        "Different business",
        "different-" + otherTenant.toString().substring(0, 8),
        "America/Sao_Paulo");
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        otherAdmin,
        ISSUER,
        otherSubject);
    migrationJdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        otherAdmin,
        otherTenant);
    AuthenticatedAdmin otherActor = new TestAdmin(otherAdmin, otherSubject, otherTenant);
    mvc.perform(get(PATH).with(authentication(token(otherActor))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(false));

    migrationJdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ?",
        adminId());
    mvc.perform(get(PATH).with(authentication(token(actor)))).andExpect(status().isForbidden());
  }

  @Test
  void savesAllowedThresholdAndReplaysWithoutDuplicateAudit() throws Exception {
    String key = "no-show-policy-" + UUID.randomUUID();
    String body = "{\"expectedVersion\":0,\"threshold\":3}";
    mvc.perform(
            put(PATH)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.configured").value(true))
        .andExpect(jsonPath("$.threshold").value(3))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.restrictionStatus").value("NOT_TRACKED"));

    mvc.perform(
            put(PATH)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(1));

    assertThat(count("client_no_show_policy")).isEqualTo(1);
    assertThat(count("audit_event")).isEqualTo(1);
  }

  @Test
  void rejectsOutOfRangeThresholdAndStaleVersionWithoutSideEffects() throws Exception {
    mvc.perform(
            put(PATH)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "wrong-initial-version-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"threshold\":4}"))
        .andExpect(status().isConflict());

    mvc.perform(
            put(PATH)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "invalid-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"threshold\":6}"))
        .andExpect(status().isBadRequest());

    replacePolicy.execute(actor, "seed-" + UUID.randomUUID(), 0, 2);
    mvc.perform(
            put(PATH)
                .with(authentication(token(actor)))
                .with(csrf())
                .header("Idempotency-Key", "stale-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"threshold\":4}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));

    assertThat(count("client_no_show_policy")).isEqualTo(1);
    assertThat(count("audit_event")).isEqualTo(1);
  }

  @Test
  void concurrentInitialWritesUseCompareAndSet() throws Exception {
    CyclicBarrier start = new CyclicBarrier(2);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> updateAfterBarrier(start, "concurrent-a-" + UUID.randomUUID(), 1));
      Future<Boolean> second =
          executor.submit(() -> updateAfterBarrier(start, "concurrent-b-" + UUID.randomUUID(), 5));
      assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
    } finally {
      executor.shutdownNow();
    }
    assertThat(count("client_no_show_policy")).isEqualTo(1);
    assertThat(count("audit_event")).isEqualTo(1);
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.client_no_show_policy WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isEqualTo(1L);
  }

  private boolean updateAfterBarrier(CyclicBarrier barrier, String key, int threshold)
      throws Exception {
    barrier.await();
    try {
      replacePolicy.execute(actor, key, 0, threshold);
      return true;
    } catch (ClientNoShowPolicyVersionConflictException conflict) {
      return false;
    }
  }

  private int count(String table) {
    return appJdbc.queryForObject(
        "SELECT count(*) FROM agendamento." + table + " WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private UUID adminId() {
    return ((TestAdmin) actor).adminUserId();
  }

  private static UsernamePasswordAuthenticationToken token(AuthenticatedAdmin admin) {
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
