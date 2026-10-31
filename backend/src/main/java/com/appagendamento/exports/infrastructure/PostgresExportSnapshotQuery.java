package com.appagendamento.exports.infrastructure;

import com.appagendamento.exports.application.ExportConfiguration;
import com.appagendamento.exports.application.ExportSnapshotQuery;
import com.appagendamento.exports.domain.ExportFilters;
import com.appagendamento.exports.domain.ExportSnapshot;
import com.appagendamento.exports.domain.InvalidExportRequestException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresExportSnapshotQuery implements ExportSnapshotQuery {

  private final JdbcTemplate jdbc;
  private final ExportConfiguration configuration;

  public PostgresExportSnapshotQuery(JdbcTemplate jdbc, ExportConfiguration configuration) {
    this.jdbc = jdbc;
    this.configuration = configuration;
  }

  @Override
  public ExportSnapshot capture(UUID tenantId, ExportFilters filters) {
    String statusClause =
        filters.statuses().isEmpty()
            ? ""
            : " AND a.status IN ("
                + String.join(",", java.util.Collections.nCopies(filters.statuses().size(), "?"))
                + ")";
    String sql =
        """
        SELECT t.timezone, a.appointment_id, a.service_id, a.service_name_snapshot,
               a.starts_at, a.service_ends_at, a.occupied_until, a.time_zone,
               a.service_duration_minutes, a.service_buffer_after_minutes,
               a.status, a.created_at
        FROM agendamento.tenant t
        LEFT JOIN agendamento.appointment a
          ON a.tenant_id = t.tenant_id
         AND a.starts_at < ((CAST(? AS date) + 1)::timestamp AT TIME ZONE t.timezone)
         AND a.occupied_until > (CAST(? AS date)::timestamp AT TIME ZONE t.timezone)
         AND (CAST(? AS uuid) IS NULL OR a.service_id = ?)
        """
            + statusClause
            + " WHERE t.tenant_id = ? ORDER BY a.starts_at NULLS LAST, a.appointment_id NULLS LAST LIMIT ?";
    List<Object> args = new ArrayList<>();
    args.add(filters.to());
    args.add(filters.from());
    args.add(filters.serviceId());
    args.add(filters.serviceId());
    filters.statuses().stream().map(Enum::name).sorted().forEach(args::add);
    args.add(tenantId);
    args.add((long) configuration.maximumRows() + 1L);
    List<SnapshotRow> rows =
        jdbc.query(
            sql,
            (result, index) -> {
              UUID appointmentId = result.getObject("appointment_id", UUID.class);
              if (appointmentId == null) {
                return new SnapshotRow(result.getString("timezone"), null);
              }
              String timeZone = result.getString("time_zone");
              ZoneId zone = ZoneId.of(timeZone);
              ExportSnapshot.Row row =
                  new ExportSnapshot.Row(
                      appointmentId,
                      result.getObject("service_id", UUID.class),
                      result.getString("service_name_snapshot"),
                      atZone(result.getTimestamp("starts_at").toInstant(), zone),
                      atZone(result.getTimestamp("service_ends_at").toInstant(), zone),
                      atZone(result.getTimestamp("occupied_until").toInstant(), zone),
                      timeZone,
                      result.getInt("service_duration_minutes"),
                      result.getInt("service_buffer_after_minutes"),
                      result.getString("status"),
                      atZone(result.getTimestamp("created_at").toInstant(), zone));
              return new SnapshotRow(result.getString("timezone"), row);
            },
            args.toArray());
    if (rows.isEmpty()) throw new InvalidExportRequestException();
    String timeZone = rows.getFirst().timeZone();
    List<ExportSnapshot.Row> content =
        rows.stream().map(SnapshotRow::row).filter(java.util.Objects::nonNull).toList();
    if (content.size() > configuration.maximumRows()) throw new InvalidExportRequestException();
    return new ExportSnapshot(timeZone, content);
  }

  private static java.time.OffsetDateTime atZone(Instant instant, ZoneId zone) {
    return instant.atZone(zone).toOffsetDateTime();
  }

  private record SnapshotRow(String timeZone, ExportSnapshot.Row row) {}
}
