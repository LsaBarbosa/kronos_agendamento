package com.appagendamento.publicpage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.publicpage.application.ChangePublicationStatus;
import com.appagendamento.publicpage.application.GetPublicationStatus;
import com.appagendamento.publicpage.application.PublicationNotReadyException;
import com.appagendamento.support.PostgresTestEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
class ProductionPublicationReadinessIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";

  @Autowired private GetPublicationStatus getPublicationStatus;
  @Autowired private ChangePublicationStatus changePublicationStatus;
  @Autowired private JdbcTemplate appJdbc;
  private JdbcTemplate migrationJdbc;
  private UUID tenantId;
  private AuthenticatedAdmin actor;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void createUnpublishedProductionTenant() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    tenantId = UUID.randomUUID();
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    migrationJdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, 'Production gate test', ?, 'America/Sao_Paulo', 'ACTIVE')",
        tenantId,
        "prod-gate-" + UUID.randomUUID().toString().substring(0, 8));
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
  void productionEvidenceKeepsMissingLegalAndCommercialGatesClosed() {
    var status = getPublicationStatus.execute(actor);
    assertThat(status.state().published()).isFalse();
    assertThat(status.readiness().blockers())
        .extracting(blocker -> blocker.code())
        .contains("TERMS_NOT_APPROVED", "CONSENT_NOT_CAPTURED", "SUBSCRIPTION_GATE_UNAVAILABLE");

    assertThatThrownBy(
            () ->
                changePublicationStatus.execute(
                    actor, "production-publish-" + UUID.randomUUID(), true, 0))
        .isInstanceOf(PublicationNotReadyException.class);
    assertThat(
            appJdbc.queryForObject(
                "SELECT published FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Boolean.class,
                tenantId))
        .isFalse();
    assertThat(
            appJdbc.queryForObject(
                "SELECT version FROM agendamento.tenant_publication WHERE tenant_id = ?",
                Long.class,
                tenantId))
        .isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.audit_event WHERE tenant_id = ? AND action = 'PUBLICATION_PUBLISHED'",
                Integer.class,
                tenantId))
        .isZero();
    assertThat(
            appJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.idempotency_record WHERE tenant_id = ? AND command_scope = 'tenant.publication.status.v1'",
                Integer.class,
                tenantId))
        .isZero();
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
