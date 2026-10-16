package com.appagendamento.publicpage.infrastructure;

import com.appagendamento.publicpage.application.PublicationRepository;
import com.appagendamento.publicpage.domain.PublicBusiness;
import com.appagendamento.publicpage.domain.PublicService;
import com.appagendamento.publicpage.domain.PublicationState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresPublicationRepository implements PublicationRepository {

  private static final RowMapper<PublicBusiness> BUSINESS_MAPPER =
      PostgresPublicationRepository::mapBusiness;
  private static final RowMapper<PublicationState> STATE_MAPPER =
      PostgresPublicationRepository::mapState;
  private static final RowMapper<PublicService> SERVICE_MAPPER =
      PostgresPublicationRepository::mapService;

  private final JdbcTemplate jdbc;

  public PostgresPublicationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<PublicBusiness> findBusinessByTenantId(UUID tenantId) {
    return jdbc.query(BUSINESS_SQL + " WHERE t.tenant_id = ?", BUSINESS_MAPPER, tenantId).stream()
        .findFirst();
  }

  @Override
  public Optional<PublicBusiness> findBusinessBySlug(String slug) {
    return jdbc.query(BUSINESS_SQL + " WHERE t.slug = ?", BUSINESS_MAPPER, slug).stream()
        .findFirst();
  }

  @Override
  public Optional<PublicationState> findState(UUID tenantId) {
    return jdbc
        .query(
            """
            SELECT tenant_id, contact_phone, published, version
            FROM agendamento.tenant_publication WHERE tenant_id = ?
            """,
            STATE_MAPPER,
            tenantId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean updatePublished(UUID tenantId, boolean published, long expectedVersion) {
    return jdbc.update(
            """
            UPDATE agendamento.tenant_publication
            SET published = ?, version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = ? AND version = ?
            """,
            published,
            tenantId,
            expectedVersion)
        == 1;
  }

  @Override
  public boolean updateContactPhone(UUID tenantId, String contactPhone, long expectedVersion) {
    return jdbc.update(
            """
            UPDATE agendamento.tenant_publication
            SET contact_phone = ?, version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = ? AND version = ?
            """,
            contactPhone,
            tenantId,
            expectedVersion)
        == 1;
  }

  @Override
  public List<PublicService> findActiveServices(UUID tenantId) {
    return jdbc.query(
        """
        SELECT service_id, name, description, duration_minutes
        FROM agendamento.service
        WHERE tenant_id = ? AND status = 'ACTIVE'
        ORDER BY created_at, service_id
        """,
        SERVICE_MAPPER,
        tenantId);
  }

  @Override
  public Optional<PublicService> findActiveService(UUID tenantId, UUID serviceId) {
    return jdbc
        .query(
            """
            SELECT service_id, name, description, duration_minutes
            FROM agendamento.service
            WHERE tenant_id = ? AND service_id = ? AND status = 'ACTIVE'
            """,
            SERVICE_MAPPER,
            tenantId,
            serviceId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean hasActiveService(UUID tenantId) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM agendamento.service WHERE tenant_id = ? AND status = 'ACTIVE')",
        tenantId);
  }

  @Override
  public boolean hasWeeklyPeriod(UUID tenantId) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM agendamento.weekly_period WHERE tenant_id = ?)", tenantId);
  }

  @Override
  public boolean hasAvailabilityPolicy(UUID tenantId) {
    return exists(
        "SELECT EXISTS(SELECT 1 FROM agendamento.availability_policy WHERE tenant_id = ?)",
        tenantId);
  }

  private boolean exists(String sql, UUID tenantId) {
    return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, tenantId));
  }

  private static final String BUSINESS_SQL =
      """
      SELECT t.tenant_id, t.name, t.slug, t.timezone, t.status,
             p.contact_phone, p.published, p.version AS publication_version
      FROM agendamento.tenant t
      JOIN agendamento.tenant_publication p ON p.tenant_id = t.tenant_id
      """;

  private static PublicBusiness mapBusiness(ResultSet result, int row) throws SQLException {
    return new PublicBusiness(
        result.getObject("tenant_id", UUID.class),
        result.getString("name"),
        result.getString("slug"),
        result.getString("timezone"),
        result.getString("status"),
        result.getString("contact_phone"),
        result.getBoolean("published"),
        result.getLong("publication_version"));
  }

  private static PublicationState mapState(ResultSet result, int row) throws SQLException {
    return new PublicationState(
        result.getObject("tenant_id", UUID.class),
        result.getString("contact_phone"),
        result.getBoolean("published"),
        result.getLong("version"));
  }

  private static PublicService mapService(ResultSet result, int row) throws SQLException {
    return new PublicService(
        result.getObject("service_id", UUID.class),
        result.getString("name"),
        result.getString("description"),
        result.getInt("duration_minutes"));
  }
}
