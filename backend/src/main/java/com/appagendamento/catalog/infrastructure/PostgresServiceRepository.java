package com.appagendamento.catalog.infrastructure;

import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import com.appagendamento.catalog.api.ServiceAvailabilityView;
import com.appagendamento.catalog.application.ServiceRepository;
import com.appagendamento.catalog.domain.Service;
import com.appagendamento.catalog.domain.ServiceNameAlreadyExistsException;
import com.appagendamento.catalog.domain.ServiceStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresServiceRepository implements ServiceRepository, ServiceAvailabilityQuery {

  private static final RowMapper<Service> SERVICE_ROW_MAPPER =
      PostgresServiceRepository::mapService;

  private final JdbcTemplate jdbc;

  public PostgresServiceRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Service insert(Service service) {
    List<Service> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.service
              (service_id, tenant_id, name, normalized_name, description, duration_minutes,
               buffer_after_minutes, status, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, normalized_name) WHERE status = 'ACTIVE' DO NOTHING
            RETURNING service_id, tenant_id, name, normalized_name, description, duration_minutes,
                      buffer_after_minutes, status, created_at, updated_at, version
            """,
            SERVICE_ROW_MAPPER,
            service.serviceId(),
            service.tenantId(),
            service.name(),
            service.normalizedName(),
            service.description(),
            service.durationMinutes(),
            service.bufferAfterMinutes(),
            service.status().name(),
            Timestamp.from(service.createdAt()),
            Timestamp.from(service.updatedAt()),
            service.version());
    if (inserted.isEmpty()) {
      throw new ServiceNameAlreadyExistsException();
    }
    return inserted.getFirst();
  }

  @Override
  public Optional<Service> findByTenantAndId(UUID tenantId, UUID serviceId) {
    return jdbc
        .query(
            """
            SELECT service_id, tenant_id, name, normalized_name, description, duration_minutes,
                   buffer_after_minutes, status, created_at, updated_at, version
            FROM agendamento.service
            WHERE tenant_id = ? AND service_id = ?
            """,
            SERVICE_ROW_MAPPER,
            tenantId,
            serviceId)
        .stream()
        .findFirst();
  }

  @Override
  public Optional<ServiceAvailabilityView> findForAvailability(UUID tenantId, UUID serviceId) {
    return jdbc
        .query(
            """
            SELECT service_id, tenant_id, status, duration_minutes, buffer_after_minutes
            FROM agendamento.service
            WHERE tenant_id = ? AND service_id = ?
            """,
            (result, row) ->
                new ServiceAvailabilityView(
                    result.getObject("service_id", UUID.class),
                    result.getObject("tenant_id", UUID.class),
                    ServiceStatus.valueOf(result.getString("status")) == ServiceStatus.ACTIVE,
                    result.getInt("duration_minutes"),
                    result.getInt("buffer_after_minutes")),
            tenantId,
            serviceId)
        .stream()
        .findFirst();
  }

  @Override
  public List<Service> findAllByTenant(UUID tenantId) {
    return jdbc.query(
        """
        SELECT service_id, tenant_id, name, normalized_name, description, duration_minutes,
               buffer_after_minutes, status, created_at, updated_at, version
        FROM agendamento.service
        WHERE tenant_id = ?
        ORDER BY created_at, service_id
        """,
        SERVICE_ROW_MAPPER,
        tenantId);
  }

  @Override
  public boolean update(Service service, long expectedVersion) {
    try {
      return jdbc.update(
              """
              UPDATE agendamento.service
              SET name = ?, normalized_name = ?, description = ?, duration_minutes = ?,
                  buffer_after_minutes = ?, status = ?, updated_at = ?, version = version + 1
              WHERE tenant_id = ? AND service_id = ? AND version = ?
              """,
              service.name(),
              service.normalizedName(),
              service.description(),
              service.durationMinutes(),
              service.bufferAfterMinutes(),
              service.status().name(),
              Timestamp.from(service.updatedAt()),
              service.tenantId(),
              service.serviceId(),
              expectedVersion)
          == 1;
    } catch (DataIntegrityViolationException exception) {
      if (hasUniqueViolation(exception)) {
        throw new ServiceNameAlreadyExistsException();
      }
      throw exception;
    }
  }

  private static boolean hasUniqueViolation(Throwable failure) {
    Throwable current = failure;
    while (current != null) {
      if (current instanceof SQLException sqlException
          && "23505".equals(sqlException.getSQLState())) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static Service mapService(ResultSet result, int row) throws SQLException {
    return new Service(
        result.getObject("service_id", UUID.class),
        result.getObject("tenant_id", UUID.class),
        result.getString("name"),
        result.getString("normalized_name"),
        result.getString("description"),
        result.getInt("duration_minutes"),
        result.getInt("buffer_after_minutes"),
        ServiceStatus.valueOf(result.getString("status")),
        result.getTimestamp("created_at").toInstant(),
        result.getTimestamp("updated_at").toInstant(),
        result.getLong("version"));
  }
}
