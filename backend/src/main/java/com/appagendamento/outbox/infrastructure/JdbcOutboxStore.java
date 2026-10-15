package com.appagendamento.outbox.infrastructure;

import com.appagendamento.outbox.api.OutboxClaim;
import com.appagendamento.outbox.api.OutboxStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxStore implements OutboxStore {

  private final JdbcTemplate jdbc;

  public JdbcOutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public List<OutboxClaim> claim(int batchSize, Duration leaseDuration) {
    if (batchSize < 1 || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("Parâmetros do claim inválidos");
    }
    List<UUID> ids =
        jdbc.query(
            """
            SELECT event_id FROM agendamento.outbox_event
            WHERE (state IN ('PENDING', 'RETRY') AND next_attempt_at <= clock_timestamp())
               OR (state = 'CLAIMED' AND lease_until <= clock_timestamp())
            ORDER BY next_attempt_at, occurred_at, event_id
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """,
            (row, index) -> row.getObject("event_id", UUID.class),
            batchSize);
    return ids.stream().map(id -> claimOne(id, leaseDuration)).toList();
  }

  @Override
  @Transactional
  public boolean markDelivered(OutboxClaim claim) {
    return updateClaim(
            """
            UPDATE agendamento.outbox_event
            SET state = 'DELIVERED', delivered_at = clock_timestamp(), claim_token = NULL,
                lease_until = NULL, last_error_code = NULL
            WHERE event_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            claim)
        == 1;
  }

  @Override
  @Transactional
  public boolean markRetry(OutboxClaim claim, Duration backoff, int maxAttempts, String errorCode) {
    String state = claim.attemptCount() >= maxAttempts ? "DEAD_LETTER" : "RETRY";
    return updateClaim(
            """
            UPDATE agendamento.outbox_event
            SET state = ?, next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                claim_token = NULL, lease_until = NULL, last_error_code = ?
            WHERE event_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            state,
            backoff.toMillis(),
            errorCode,
            claim.eventId(),
            claim.claimToken())
        == 1;
  }

  @Override
  @Transactional
  public boolean markTerminal(OutboxClaim claim, String errorCode) {
    return updateClaim(
            """
            UPDATE agendamento.outbox_event
            SET state = 'DEAD_LETTER', claim_token = NULL, lease_until = NULL, last_error_code = ?
            WHERE event_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            errorCode,
            claim.eventId(),
            claim.claimToken())
        == 1;
  }

  @Override
  @Transactional
  public boolean markOutcomeUnknown(OutboxClaim claim, String errorCode) {
    return updateClaim(
            """
            UPDATE agendamento.outbox_event
            SET state = 'RECONCILIATION_REQUIRED', claim_token = NULL, lease_until = NULL,
                last_error_code = ?
            WHERE event_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            errorCode,
            claim.eventId(),
            claim.claimToken())
        == 1;
  }

  @Override
  @Transactional
  public boolean reprocess(UUID eventId) {
    return jdbc.update(
            """
            UPDATE agendamento.outbox_event
            SET state = 'RETRY', attempt_count = 0, reprocess_count = reprocess_count + 1,
                next_attempt_at = clock_timestamp(), last_error_code = NULL
            WHERE event_id = ? AND state = 'DEAD_LETTER'
            """,
            eventId)
        == 1;
  }

  private OutboxClaim claimOne(UUID eventId, Duration leaseDuration) {
    UUID claimToken = UUID.randomUUID();
    List<OutboxClaim> claims =
        jdbc.query(
            """
            UPDATE agendamento.outbox_event
            SET state = 'CLAIMED', attempt_count = attempt_count + 1, claim_token = ?,
                lease_until = clock_timestamp() + (? * interval '1 millisecond')
            WHERE event_id = ?
            RETURNING event_id, tenant_id, aggregate_type, aggregate_id, aggregate_version,
                      event_type, schema_version, operation_id, correlation_id, payload::text,
                      attempt_count, claim_token
            """,
            (row, index) ->
                new OutboxClaim(
                    row.getObject("event_id", UUID.class),
                    row.getObject("tenant_id", UUID.class),
                    row.getString("aggregate_type"),
                    row.getObject("aggregate_id", UUID.class),
                    row.getLong("aggregate_version"),
                    row.getString("event_type"),
                    row.getInt("schema_version"),
                    row.getObject("operation_id", UUID.class),
                    row.getObject("correlation_id", UUID.class),
                    row.getString("payload"),
                    row.getInt("attempt_count"),
                    row.getObject("claim_token", UUID.class)),
            claimToken,
            leaseDuration.toMillis(),
            eventId);
    return claims.getFirst();
  }

  private int updateClaim(String sql, OutboxClaim claim) {
    return jdbc.update(sql, claim.eventId(), claim.claimToken());
  }

  private int updateClaim(String sql, Object... arguments) {
    return jdbc.update(sql, arguments);
  }
}
