package com.appagendamento.identityaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.support.PostgresTestEnvironment;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class AdminAuthorizationIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";

  @Autowired private AdminAccessPolicy policy;
  private JdbcTemplate jdbc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void privilegedFixtures() {
    jdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
  }

  @Test
  void requiresActiveIdentityMembershipRoleAndTenantOnEveryAuthorizationCheck() {
    UUID adminId = UUID.randomUUID();
    UUID tenantId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    jdbc.update(
        "INSERT INTO agendamento.tenant (tenant_id, name, slug, timezone, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
        tenantId,
        "Tenant de teste",
        "admin-test-" + tenantId.toString().substring(0, 8),
        "America/Sao_Paulo");
    jdbc.update(
        "INSERT INTO agendamento.admin_user (user_id, identity_issuer, identity_subject) VALUES (?, ?, ?)",
        adminId,
        ISSUER,
        subject);
    jdbc.update(
        "INSERT INTO agendamento.admin_tenant_membership (user_id, tenant_id, role) VALUES (?, ?, 'TENANT_ADMIN')",
        adminId,
        tenantId);
    AuthenticatedAdmin principal = principal(adminId, tenantId, subject, "TENANT_ADMIN");

    assertThat(policy.isCurrent(principal)).isTrue();
    assertThat(policy.isCurrent(principal(adminId, UUID.randomUUID(), subject, "TENANT_ADMIN")))
        .isFalse();
    assertThat(policy.isCurrent(principal(adminId, tenantId, subject, "PLATFORM_SUPPORT")))
        .isFalse();

    jdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'DISABLED' WHERE user_id = ?",
        adminId);
    assertThat(policy.isCurrent(principal)).isFalse();
    jdbc.update(
        "UPDATE agendamento.admin_tenant_membership SET status = 'ACTIVE' WHERE user_id = ?",
        adminId);
    jdbc.update("UPDATE agendamento.admin_user SET status = 'DISABLED' WHERE user_id = ?", adminId);
    assertThat(policy.isCurrent(principal)).isFalse();
  }

  private static AuthenticatedAdmin principal(
      UUID adminId, UUID tenantId, String subject, String role) {
    return new AuthenticatedAdmin() {
      @Override
      public UUID adminUserId() {
        return adminId;
      }

      @Override
      public String issuer() {
        return ISSUER;
      }

      @Override
      public String subject() {
        return subject;
      }

      @Override
      public String role() {
        return role;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }
    };
  }
}
