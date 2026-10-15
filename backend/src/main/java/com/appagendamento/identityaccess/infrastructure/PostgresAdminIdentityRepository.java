package com.appagendamento.identityaccess.infrastructure;

import com.appagendamento.identityaccess.application.AdminIdentityRepository;
import com.appagendamento.identityaccess.domain.AdminAccess;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresAdminIdentityRepository implements AdminIdentityRepository {

  private final JdbcTemplate jdbc;

  public PostgresAdminIdentityRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AdminAccess> findActive(String issuer, String subject) {
    return jdbc
        .query(
            """
            SELECT user_id, tenant_id, role
            FROM agendamento.admin_user
            JOIN agendamento.admin_tenant_membership USING (user_id)
            JOIN agendamento.tenant USING (tenant_id)
            WHERE identity_issuer = ? AND identity_subject = ?
              AND admin_user.status = 'ACTIVE'
              AND admin_tenant_membership.status = 'ACTIVE'
              AND tenant.status = 'ACTIVE'
            """,
            (row, index) ->
                new AdminAccess(
                    row.getObject("user_id", UUID.class),
                    row.getObject("tenant_id", UUID.class),
                    row.getString("role")),
            issuer,
            subject)
        .stream()
        .findFirst();
  }

  @Override
  public boolean isActive(
      String issuer, String subject, UUID adminUserId, UUID tenantId, String role) {
    List<UUID> matching =
        jdbc.query(
            """
            SELECT admin_user.user_id
            FROM agendamento.admin_user
            JOIN agendamento.admin_tenant_membership USING (user_id)
            JOIN agendamento.tenant USING (tenant_id)
            WHERE identity_issuer = ? AND identity_subject = ?
              AND admin_user.user_id = ? AND admin_tenant_membership.tenant_id = ?
              AND admin_tenant_membership.role = ?
              AND admin_user.status = 'ACTIVE'
              AND admin_tenant_membership.status = 'ACTIVE'
              AND tenant.status = 'ACTIVE'
            """,
            (row, index) -> row.getObject("user_id", UUID.class),
            issuer,
            subject,
            adminUserId,
            tenantId,
            role);
    return matching.size() == 1;
  }
}
