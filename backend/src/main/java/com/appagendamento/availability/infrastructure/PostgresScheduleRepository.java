package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.ScheduleRepository;
import com.appagendamento.availability.domain.AvailabilityPolicy;
import com.appagendamento.availability.domain.BlockedPeriod;
import com.appagendamento.availability.domain.BlockedPeriodStatus;
import com.appagendamento.availability.domain.ScheduleException;
import com.appagendamento.availability.domain.ScheduleExceptionKind;
import com.appagendamento.availability.domain.SpecialPeriod;
import com.appagendamento.availability.domain.WeeklyPeriod;
import com.appagendamento.availability.domain.WeeklySchedule;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
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
  public String findTenantTimeZone(UUID tenantId) {
    return jdbc.queryForObject(
        "SELECT timezone FROM agendamento.tenant WHERE tenant_id = ?", String.class, tenantId);
  }

  @Override
  public long nextScheduleRevision(UUID tenantId) {
    return jdbc.queryForObject(
        """
        INSERT INTO agendamento.schedule_revision(tenant_id, revision)
        VALUES (?, 1)
        ON CONFLICT (tenant_id) DO UPDATE
        SET revision = agendamento.schedule_revision.revision + 1
        RETURNING revision
        """,
        Long.class,
        tenantId);
  }

  @Override
  public long currentScheduleRevision(UUID tenantId) {
    return jdbc
        .query(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ?",
            (row, index) -> row.getLong(1),
            tenantId)
        .stream()
        .findFirst()
        .orElse(0L);
  }

  @Override
  public boolean lockScheduleRevision(UUID tenantId, long expectedRevision) {
    return !jdbc.query(
            "SELECT revision FROM agendamento.schedule_revision WHERE tenant_id = ? AND revision = ? FOR SHARE",
            (row, index) -> row.getLong(1),
            tenantId,
            expectedRevision)
        .isEmpty();
  }

  @Override
  public List<ScheduleException> findScheduleExceptions(
      UUID tenantId, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
        SELECT e.exception_id, e.tenant_id, e.local_date, e.kind, e.reason, e.version, t.timezone
        FROM agendamento.schedule_exception e
        JOIN agendamento.tenant t USING (tenant_id)
        WHERE e.tenant_id = ? AND e.local_date BETWEEN ? AND ?
        ORDER BY e.local_date
        """,
        (rs, row) -> mapException(rs, tenantId),
        tenantId,
        from,
        to);
  }

  @Override
  public Optional<ScheduleException> findScheduleException(UUID tenantId, UUID exceptionId) {
    return jdbc
        .query(
            """
        SELECT e.exception_id, e.tenant_id, e.local_date, e.kind, e.reason, e.version, t.timezone
        FROM agendamento.schedule_exception e
        JOIN agendamento.tenant t USING (tenant_id)
        WHERE e.tenant_id = ? AND e.exception_id = ?
        """,
            (rs, row) -> mapException(rs, tenantId),
            tenantId,
            exceptionId)
        .stream()
        .findFirst();
  }

  @Override
  public boolean insertScheduleException(ScheduleException value) {
    int changed;
    try {
      changed =
          jdbc.update(
              """
          INSERT INTO agendamento.schedule_exception(exception_id, tenant_id, local_date, kind, reason)
          VALUES (?, ?, ?, ?, ?)
          """,
              value.exceptionId(),
              value.tenantId(),
              value.localDate(),
              value.kind().name(),
              value.reason());
    } catch (DuplicateKeyException exception) {
      throw new com.appagendamento.availability.domain.ScheduleExceptionAlreadyExistsException();
    }
    replaceSpecialPeriods(value);
    return changed == 1;
  }

  @Override
  public boolean lockScheduleExceptionVersion(
      UUID tenantId, UUID exceptionId, long expectedVersion) {
    return jdbc
        .query(
            """
        SELECT version FROM agendamento.schedule_exception
        WHERE tenant_id = ? AND exception_id = ? FOR UPDATE
        """,
            (rs, row) -> rs.getLong(1),
            tenantId,
            exceptionId)
        .stream()
        .anyMatch(v -> v == expectedVersion);
  }

  @Override
  public boolean updateScheduleException(ScheduleException value, long expectedVersion) {
    int changed;
    try {
      changed =
          jdbc.update(
              """
          UPDATE agendamento.schedule_exception SET local_date = ?, kind = ?, reason = ?,
            version = version + 1, updated_at = clock_timestamp()
          WHERE tenant_id = ? AND exception_id = ? AND version = ?
          """,
              value.localDate(),
              value.kind().name(),
              value.reason(),
              value.tenantId(),
              value.exceptionId(),
              expectedVersion);
    } catch (DuplicateKeyException exception) {
      throw new com.appagendamento.availability.domain.ScheduleExceptionAlreadyExistsException();
    }
    if (changed == 1) replaceSpecialPeriods(value);
    return changed == 1;
  }

  @Override
  public boolean deleteScheduleException(UUID tenantId, UUID exceptionId, long expectedVersion) {
    return jdbc.update(
            "DELETE FROM agendamento.schedule_exception WHERE tenant_id = ? AND exception_id = ? AND version = ?",
            tenantId,
            exceptionId,
            expectedVersion)
        == 1;
  }

  @Override
  public List<BlockedPeriod> findBlockedPeriods(UUID tenantId, Instant from, Instant to) {
    return jdbc.query(
        """
        SELECT block_id, tenant_id, starts_at, ends_at, time_zone, reason, status, version,
               cancelled_at, cancellation_reason
        FROM agendamento.blocked_period
        WHERE tenant_id = ? AND starts_at < ? AND ends_at > ?
        ORDER BY starts_at, block_id
        """,
        PostgresScheduleRepository::mapBlockedPeriod,
        tenantId,
        Timestamp.from(to),
        Timestamp.from(from));
  }

  @Override
  public Optional<BlockedPeriod> findBlockedPeriod(UUID tenantId, UUID blockId) {
    return jdbc
        .query(
            """
        SELECT block_id, tenant_id, starts_at, ends_at, time_zone, reason, status, version,
               cancelled_at, cancellation_reason
        FROM agendamento.blocked_period WHERE tenant_id = ? AND block_id = ?
        """,
            PostgresScheduleRepository::mapBlockedPeriod,
            tenantId,
            blockId)
        .stream()
        .findFirst();
  }

  @Override
  public BlockedPeriod insertBlockedPeriod(BlockedPeriod value) {
    jdbc.update(
        """
        INSERT INTO agendamento.blocked_period(block_id, tenant_id, starts_at, ends_at, time_zone, reason)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        value.blockId(),
        value.tenantId(),
        Timestamp.from(value.startsAt()),
        Timestamp.from(value.endsAt()),
        value.timeZone(),
        value.reason());
    return value;
  }

  @Override
  public boolean cancelBlockedPeriod(BlockedPeriod value, long expectedVersion) {
    return jdbc.update(
            """
        UPDATE agendamento.blocked_period
        SET status = 'CANCELLED', version = version + 1, cancelled_at = ?, cancellation_reason = ?,
            updated_at = clock_timestamp()
        WHERE tenant_id = ? AND block_id = ? AND version = ? AND status = 'ACTIVE'
        """,
            Timestamp.from(value.cancelledAt()),
            value.cancellationReason(),
            value.tenantId(),
            value.blockId(),
            expectedVersion)
        == 1;
  }

  private void replaceSpecialPeriods(ScheduleException value) {
    jdbc.update(
        "DELETE FROM agendamento.schedule_exception_period WHERE tenant_id = ? AND exception_id = ?",
        value.tenantId(),
        value.exceptionId());
    jdbc.batchUpdate(
        """
        INSERT INTO agendamento.schedule_exception_period
          (tenant_id, exception_id, local_start, local_end,
           local_start_offset_seconds, local_end_offset_seconds)
        VALUES (?, ?, ?, ?, ?, ?)
        """,
        value.periods(),
        value.periods().size(),
        (statement, period) -> {
          statement.setObject(1, value.tenantId());
          statement.setObject(2, value.exceptionId());
          statement.setTime(3, Time.valueOf(period.localStart()));
          statement.setTime(4, Time.valueOf(period.localEnd()));
          statement.setObject(
              5,
              period.localStartOffset() == null
                  ? null
                  : period.localStartOffset().getTotalSeconds());
          statement.setObject(
              6,
              period.localEndOffset() == null ? null : period.localEndOffset().getTotalSeconds());
        });
  }

  private ScheduleException mapException(ResultSet rs, UUID tenantId) throws SQLException {
    UUID exceptionId = rs.getObject("exception_id", UUID.class);
    List<SpecialPeriod> periods =
        jdbc.query(
            """
        SELECT local_start, local_end, local_start_offset_seconds, local_end_offset_seconds
        FROM agendamento.schedule_exception_period
        WHERE tenant_id = ? AND exception_id = ? ORDER BY local_start
        """,
            (periodRs, row) -> {
              Integer startOffset = periodRs.getObject(3, Integer.class);
              Integer endOffset = periodRs.getObject(4, Integer.class);
              return new SpecialPeriod(
                  periodRs.getTime(1).toLocalTime(),
                  periodRs.getTime(2).toLocalTime(),
                  startOffset == null ? null : java.time.ZoneOffset.ofTotalSeconds(startOffset),
                  endOffset == null ? null : java.time.ZoneOffset.ofTotalSeconds(endOffset));
            },
            tenantId,
            exceptionId);
    return new ScheduleException(
        exceptionId,
        tenantId,
        rs.getObject("local_date", LocalDate.class),
        ScheduleExceptionKind.valueOf(rs.getString("kind")),
        periods,
        rs.getString("reason"),
        rs.getLong("version"),
        rs.getString("timezone"));
  }

  private static BlockedPeriod mapBlockedPeriod(ResultSet rs, int row) throws SQLException {
    return new BlockedPeriod(
        rs.getObject("block_id", UUID.class),
        rs.getObject("tenant_id", UUID.class),
        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
        rs.getObject("ends_at", OffsetDateTime.class).toInstant(),
        rs.getString("time_zone"),
        rs.getString("reason"),
        BlockedPeriodStatus.valueOf(rs.getString("status")),
        rs.getLong("version"),
        rs.getObject("cancelled_at", OffsetDateTime.class) == null
            ? null
            : rs.getObject("cancelled_at", OffsetDateTime.class).toInstant(),
        rs.getString("cancellation_reason"));
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
