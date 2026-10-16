package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.ScheduleRepository;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PostgresScheduleRepository implements ScheduleRepository {

  private final JdbcTemplate jdbc;

  public PostgresScheduleRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public WeeklySchedule findWeeklySchedule(UUID tenantId) {
    List<WeeklyRow> rows =
        jdbc.query(
            """
            SELECT t.timezone, s.version
            FROM agendamento.tenant t
            LEFT JOIN agendamento.weekly_schedule s ON s.tenant_id = t.tenant_id
            WHERE t.tenant_id = ?
            """,
            (result, row) ->
                new WeeklyRow(result.getString("timezone"), (Long) result.getObject("version")),
            tenantId);
    if (rows.isEmpty()) {
      throw new IllegalArgumentException("Tenant não encontrado");
    }
    WeeklyRow weekly = rows.getFirst();
    List<WeeklyPeriod> periods =
        weekly.version() == null
            ? List.of()
            : jdbc.query(
                """
                SELECT weekday, local_start, local_end
                FROM agendamento.weekly_period
                WHERE tenant_id = ?
                ORDER BY weekday, local_start, local_end
                """,
                PostgresScheduleRepository::mapWeeklyPeriod,
                tenantId);
    return new WeeklySchedule(
        tenantId,
        weekly.timeZone(),
        weekly.version() != null,
        periods,
        weekly.version() == null ? 0 : weekly.version());
  }

  @Override
  public Optional<AvailabilityPolicy> findAvailabilityPolicy(UUID tenantId) {
    return jdbc
        .query(
            """
            SELECT tenant_id, slot_step_minutes, minimum_notice_hours, booking_horizon_days,
                   cancellation_cutoff_hours, reminder_offset_hours, version
            FROM agendamento.availability_policy
            WHERE tenant_id = ?
            """,
            PostgresScheduleRepository::mapAvailabilityPolicy,
            tenantId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean lockWeeklyScheduleVersion(UUID tenantId, long expectedVersion) {
    return jdbc
        .query(
            "SELECT version FROM agendamento.weekly_schedule WHERE tenant_id = ? FOR UPDATE",
            (result, row) -> result.getLong("version"),
            tenantId)
        .stream()
        .anyMatch(version -> version == expectedVersion);
  }

  @Override
  public boolean lockAvailabilityPolicyVersion(UUID tenantId, long expectedVersion) {
    return jdbc
        .query(
            "SELECT version FROM agendamento.availability_policy WHERE tenant_id = ? FOR UPDATE",
            (result, row) -> result.getLong("version"),
            tenantId)
        .stream()
        .anyMatch(version -> version == expectedVersion);
  }

  @Override
  public boolean replaceWeeklySchedule(
      UUID tenantId, long expectedVersion, List<WeeklyPeriod> periods) {
    boolean claimed;
    if (expectedVersion == 0) {
      claimed =
          !jdbc.query(
                  """
                  INSERT INTO agendamento.weekly_schedule (tenant_id, version)
                  VALUES (?, 1)
                  ON CONFLICT (tenant_id) DO NOTHING
                  RETURNING tenant_id
                  """,
                  (result, row) -> result.getObject("tenant_id", UUID.class),
                  tenantId)
              .isEmpty();
    } else {
      claimed =
          jdbc.update(
                  """
                  UPDATE agendamento.weekly_schedule
                  SET version = version + 1, updated_at = clock_timestamp()
                  WHERE tenant_id = ? AND version = ?
                  """,
                  tenantId,
                  expectedVersion)
              == 1;
    }
    if (!claimed) {
      return false;
    }
    jdbc.update("DELETE FROM agendamento.weekly_period WHERE tenant_id = ?", tenantId);
    jdbc.batchUpdate(
        """
        INSERT INTO agendamento.weekly_period (tenant_id, weekday, local_start, local_end)
        VALUES (?, ?, ?, ?)
        """,
        periods,
        periods.size(),
        (statement, period) -> {
          statement.setObject(1, tenantId);
          statement.setString(2, period.weekday().name());
          statement.setTime(3, Time.valueOf(period.localStart()));
          statement.setTime(4, Time.valueOf(period.localEnd()));
        });
    return true;
  }

  @Override
  public boolean replaceAvailabilityPolicy(AvailabilityPolicy policy, long expectedVersion) {
    if (expectedVersion == 0) {
      return !jdbc.query(
              """
              INSERT INTO agendamento.availability_policy
                (tenant_id, slot_step_minutes, minimum_notice_hours, booking_horizon_days,
                 cancellation_cutoff_hours, reminder_offset_hours, version)
              VALUES (?, ?, ?, ?, ?, ?, 1)
              ON CONFLICT (tenant_id) DO NOTHING
              RETURNING tenant_id
              """,
              (result, row) -> result.getObject("tenant_id", UUID.class),
              policy.tenantId(),
              policy.slotStepMinutes(),
              policy.minimumNoticeHours(),
              policy.bookingHorizonDays(),
              policy.cancellationCutoffHours(),
              policy.reminderOffsetHours())
          .isEmpty();
    }
    return jdbc.update(
            """
            UPDATE agendamento.availability_policy
            SET slot_step_minutes = ?, minimum_notice_hours = ?, booking_horizon_days = ?,
                cancellation_cutoff_hours = ?, reminder_offset_hours = ?,
                version = version + 1, updated_at = clock_timestamp()
            WHERE tenant_id = ? AND version = ?
            """,
            policy.slotStepMinutes(),
            policy.minimumNoticeHours(),
            policy.bookingHorizonDays(),
            policy.cancellationCutoffHours(),
            policy.reminderOffsetHours(),
            policy.tenantId(),
            expectedVersion)
        == 1;
  }

  private static WeeklyPeriod mapWeeklyPeriod(ResultSet result, int row) throws SQLException {
    return new WeeklyPeriod(
        DayOfWeek.valueOf(result.getString("weekday")),
        result.getTime("local_start").toLocalTime(),
        result.getTime("local_end").toLocalTime());
  }

  private static AvailabilityPolicy mapAvailabilityPolicy(ResultSet result, int row)
      throws SQLException {
    return new AvailabilityPolicy(
        result.getObject("tenant_id", UUID.class),
        result.getInt("slot_step_minutes"),
        result.getInt("minimum_notice_hours"),
        result.getInt("booking_horizon_days"),
        result.getInt("cancellation_cutoff_hours"),
        result.getInt("reminder_offset_hours"),
        result.getLong("version"));
  }

  private record WeeklyRow(String timeZone, Long version) {}
}
