package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.appointments.api.PublicBookingGate;
import com.appagendamento.publicpage.application.GetPublicBusiness;
import com.appagendamento.publicpage.application.PublicationNotFoundException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicBookingGateAdapter implements PublicBookingGate {

  private final GetPublicBusiness businesses;
  private final JdbcTemplate jdbc;

  public PublicBookingGateAdapter(GetPublicBusiness businesses, JdbcTemplate jdbc) {
    this.businesses = businesses;
    this.jdbc = jdbc;
  }

  @Override
  public UUID resolveVisibleTenant(String slug) {
    return businesses.execute(slug).tenantId();
  }

  @Override
  public void requireVisibleTenant(String slug, UUID expectedTenantId) {
    if (!businesses.execute(slug).tenantId().equals(expectedTenantId)) {
      throw new PublicationNotFoundException();
    }
  }

  @Override
  public void protectPublicationUntilCommit(String slug, UUID expectedTenantId) {
    List<UUID> visible =
        jdbc.query(
            """
            SELECT p.tenant_id
            FROM agendamento.tenant_publication p
            JOIN agendamento.tenant t ON t.tenant_id = p.tenant_id
            WHERE t.slug = ? AND t.status = 'ACTIVE' AND p.published
            FOR SHARE OF p
            """,
            (row, index) -> row.getObject("tenant_id", UUID.class),
            slug);
    if (visible.isEmpty() || !visible.getFirst().equals(expectedTenantId)) {
      throw new PublicationNotFoundException();
    }
  }
}
