package com.appagendamento.notifications.infrastructure;

import com.appagendamento.notifications.application.NotificationRepository;
import com.appagendamento.notifications.application.NotificationRepository.AttemptOverview;
import com.appagendamento.notifications.application.NotificationRepository.EvidenceAdmission;
import com.appagendamento.notifications.application.NotificationRepository.NotificationClaim;
import com.appagendamento.notifications.application.NotificationRepository.NotificationDetail;
import com.appagendamento.notifications.application.NotificationRepository.NotificationOverview;
import com.appagendamento.notifications.application.NotificationRepository.NotificationSummary;
import com.appagendamento.notifications.application.NotificationRepository.VerifiedDeliveryEvidence;
import com.appagendamento.notifications.domain.NotificationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PostgresNotificationRepository implements NotificationRepository {

  private final JdbcTemplate jdbc;

  public PostgresNotificationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void createIntent(
      UUID tenantId,
      UUID appointmentId,
      long appointmentVersion,
      UUID recipientClientId,
      UUID sourceEventId,
      NotificationType type) {
    UUID notificationId = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO agendamento.notification_intent
          (notification_id, tenant_id, appointment_id, appointment_version, message_type,
           recipient_client_id, source_event_id, external_idempotency_key)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT DO NOTHING
        """,
        notificationId,
        tenantId,
        appointmentId,
        appointmentVersion,
        type.name(),
        recipientClientId,
        sourceEventId,
        notificationId.toString());
  }

  @Override
  @Transactional
  public List<NotificationClaim> claimDue(int batchSize, Duration leaseDuration) {
    if (batchSize < 1 || batchSize > 100 || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("Parâmetros de claim inválidos");
    }
    jdbc.update(
        """
        UPDATE agendamento.notification_attempt a
        SET state = 'OUTCOME_UNKNOWN', error_code = 'LEASE_EXPIRED_OUTCOME_UNKNOWN',
            completed_at = clock_timestamp()
        FROM agendamento.notification_intent n
        WHERE a.claim_token = n.claim_token AND n.state = 'CLAIMED'
          AND n.lease_until <= clock_timestamp() AND a.state = 'STARTED'
        """);
    jdbc.update(
        """
        UPDATE agendamento.notification_intent
        SET state = 'OUTCOME_UNKNOWN', claim_token = NULL, lease_until = NULL,
            last_error_code = 'LEASE_EXPIRED_OUTCOME_UNKNOWN'
        WHERE state = 'CLAIMED' AND lease_until <= clock_timestamp()
        """);
    List<UUID> ids =
        jdbc.query(
            """
            SELECT notification_id FROM agendamento.notification_intent
            WHERE state IN ('PENDING', 'RETRY') AND next_attempt_at <= clock_timestamp()
            ORDER BY next_attempt_at, created_at, notification_id
            LIMIT ? FOR UPDATE SKIP LOCKED
            """,
            (row, index) -> row.getObject("notification_id", UUID.class),
            batchSize);
    List<NotificationClaim> claims = new ArrayList<>();
    for (UUID id : ids) {
      UUID claimToken = UUID.randomUUID();
      List<NotificationClaim> claimed =
          jdbc.query(
              """
              UPDATE agendamento.notification_intent
              SET state = 'CLAIMED', attempt_count = attempt_count + 1, claim_token = ?,
                  lease_until = clock_timestamp() + (? * interval '1 millisecond')
              WHERE notification_id = ? AND state IN ('PENDING', 'RETRY')
                AND next_attempt_at <= clock_timestamp()
              RETURNING notification_id, tenant_id, appointment_id, appointment_version,
                        recipient_client_id, message_type, external_idempotency_key, attempt_count
              """,
              (row, index) ->
                  new NotificationClaim(
                      row.getObject("notification_id", UUID.class),
                      row.getObject("tenant_id", UUID.class),
                      row.getObject("appointment_id", UUID.class),
                      row.getLong("appointment_version"),
                      row.getObject("recipient_client_id", UUID.class),
                      NotificationType.valueOf(row.getString("message_type")),
                      row.getString("external_idempotency_key"),
                      row.getInt("attempt_count"),
                      claimToken),
              claimToken,
              leaseDuration.toMillis(),
              id);
      if (!claimed.isEmpty()) {
        NotificationClaim claim = claimed.getFirst();
        jdbc.update(
            """
            INSERT INTO agendamento.notification_attempt
              (attempt_id, notification_id, attempt_number, claim_token, state)
            VALUES (?, ?, ?, ?, 'STARTED')
            """,
            UUID.randomUUID(),
            claim.notificationId(),
            claim.attemptNumber(),
            claim.claimToken());
        claims.add(claim);
      }
    }
    return List.copyOf(claims);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean authorizeSend(NotificationClaim claim) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM agendamento.notification_intent
              WHERE notification_id = ? AND state = 'CLAIMED' AND claim_token = ?
                AND lease_until > clock_timestamp()
            )
            """,
            Boolean.class,
            claim.notificationId(),
            claim.claimToken()));
  }

  @Override
  @Transactional
  public boolean markAccepted(
      NotificationClaim claim, String providerCode, String providerReference) {
    if (providerCode == null
        || !providerCode.matches("[a-z0-9][a-z0-9._-]{0,63}")
        || providerReference == null
        || providerReference.isBlank()
        || providerReference.length() > 160) {
      throw new IllegalArgumentException("Referência normalizada do transporte inválida");
    }
    lockProviderReference(providerCode, providerReference);
    int updated =
        jdbc.update(
            """
            UPDATE agendamento.notification_intent
            SET state = 'ACCEPTED', accepted_at = clock_timestamp(), provider_code = ?,
                provider_message_reference = ?, claim_token = NULL, lease_until = NULL,
                last_error_code = NULL
            WHERE notification_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            providerCode,
            providerReference,
            claim.notificationId(),
            claim.claimToken());
    if (updated == 1) {
      finishAttempt(claim, "ACCEPTED", null);
      reconcilePendingEvidence(providerCode, providerReference);
      return true;
    }
    markUnknown(claim, "TRANSPORT_OUTCOME_UNKNOWN");
    return false;
  }

  @Override
  @Transactional
  public boolean markRetryable(
      NotificationClaim claim, Duration backoff, int maxAttempts, String errorCode) {
    if (backoff.isNegative() || maxAttempts < 1 || !safeError(errorCode)) {
      throw new IllegalArgumentException("Retry de notificação inválido");
    }
    String nextState = claim.attemptNumber() >= maxAttempts ? "FAILED_FINAL" : "RETRY";
    int updated =
        jdbc.update(
            """
            UPDATE agendamento.notification_intent
            SET state = ?, next_attempt_at = clock_timestamp() + (? * interval '1 millisecond'),
                claim_token = NULL, lease_until = NULL, last_error_code = ?
            WHERE notification_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            nextState,
            backoff.toMillis(),
            errorCode,
            claim.notificationId(),
            claim.claimToken());
    if (updated == 1) {
      finishAttempt(claim, "RETRYABLE_NO_EFFECT", errorCode);
      return true;
    }
    markUnknown(claim, "TRANSPORT_OUTCOME_UNKNOWN");
    return false;
  }

  @Override
  @Transactional
  public boolean markFailed(NotificationClaim claim, String errorCode) {
    return finishIntent(claim, "FAILED_FINAL", "FAILED_FINAL", errorCode);
  }

  @Override
  @Transactional
  public boolean markUnknown(NotificationClaim claim, String errorCode) {
    if (!safeError(errorCode)) {
      throw new IllegalArgumentException("Código de falha não permitido");
    }
    int updated =
        jdbc.update(
            """
            UPDATE agendamento.notification_intent
            SET state = 'OUTCOME_UNKNOWN', claim_token = NULL, lease_until = NULL,
                last_error_code = ?
            WHERE notification_id = ? AND state = 'CLAIMED' AND claim_token = ?
            """,
            errorCode,
            claim.notificationId(),
            claim.claimToken());
    if (updated == 1) {
      finishAttempt(claim, "OUTCOME_UNKNOWN", errorCode);
      return true;
    }
    return false;
  }

  @Override
  @Transactional
  public boolean markObsolete(NotificationClaim claim) {
    return finishIntent(claim, "OBSOLETE", "OBSOLETE", null);
  }

  private boolean finishIntent(
      NotificationClaim claim, String state, String attemptState, String errorCode) {
    if (errorCode != null && !safeError(errorCode)) {
      throw new IllegalArgumentException("Código de falha não permitido");
    }
    int updated =
        jdbc.update(
            """
            UPDATE agendamento.notification_intent
            SET state = ?, claim_token = NULL, lease_until = NULL, last_error_code = ?
            WHERE notification_id = ? AND state = 'CLAIMED' AND claim_token = ?
              AND lease_until > clock_timestamp()
            """,
            state,
            errorCode,
            claim.notificationId(),
            claim.claimToken());
    if (updated == 1) {
      finishAttempt(claim, attemptState, errorCode);
      return true;
    }
    markUnknown(claim, "TRANSPORT_OUTCOME_UNKNOWN");
    return false;
  }

  private void finishAttempt(NotificationClaim claim, String state, String errorCode) {
    jdbc.update(
        """
        UPDATE agendamento.notification_attempt
        SET state = ?, error_code = ?, completed_at = clock_timestamp()
        WHERE notification_id = ? AND claim_token = ? AND state = 'STARTED'
        """,
        state,
        errorCode,
        claim.notificationId(),
        claim.claimToken());
  }

  @Override
  public List<NotificationSummary> summarize(UUID tenantId) {
    return jdbc.query(
        """
        SELECT state, count(*) AS item_count,
               CASE WHEN state IN ('PENDING', 'RETRY', 'CLAIMED')
                    THEN greatest(0, extract(epoch FROM clock_timestamp() - min(created_at)))::bigint
                    ELSE NULL END AS oldest_seconds
        FROM agendamento.notification_intent
        WHERE tenant_id = ?
        GROUP BY state
        ORDER BY state
        """,
        (row, index) ->
            new NotificationSummary(
                row.getString("state"),
                row.getLong("item_count"),
                (Long) row.getObject("oldest_seconds")),
        tenantId);
  }

  @Override
  public List<NotificationOverview> list(UUID tenantId, int limit) {
    if (limit < 1 || limit > 100) {
      throw new IllegalArgumentException("Limite de consulta inválido");
    }
    return jdbc.query(
        """
        SELECT notification_id, appointment_id, appointment_version, message_type, state,
               attempt_count, next_attempt_at, created_at, last_error_code
        FROM agendamento.notification_intent
        WHERE tenant_id = ?
        ORDER BY created_at DESC, notification_id DESC
        LIMIT ?
        """,
        (row, index) -> overview(row),
        tenantId,
        limit);
  }

  @Override
  public Optional<NotificationDetail> detail(UUID tenantId, UUID notificationId) {
    List<NotificationOverview> items =
        jdbc.query(
            """
            SELECT notification_id, appointment_id, appointment_version, message_type, state,
                   attempt_count, next_attempt_at, created_at, last_error_code
            FROM agendamento.notification_intent
            WHERE tenant_id = ? AND notification_id = ?
            """,
            (row, index) -> overview(row),
            tenantId,
            notificationId);
    if (items.isEmpty()) {
      return Optional.empty();
    }
    List<AttemptOverview> attempts =
        jdbc.query(
            """
            SELECT attempt_number, state, error_code, started_at, completed_at
            FROM agendamento.notification_attempt
            WHERE notification_id = ?
            ORDER BY attempt_number
            """,
            (row, index) ->
                new AttemptOverview(
                    row.getInt("attempt_number"),
                    row.getString("state"),
                    row.getString("error_code"),
                    row.getTimestamp("started_at").toInstant(),
                    row.getTimestamp("completed_at") == null
                        ? null
                        : row.getTimestamp("completed_at").toInstant()),
            notificationId);
    return Optional.of(new NotificationDetail(items.getFirst(), attempts));
  }

  @Override
  @Transactional
  public EvidenceAdmission recordEvidence(VerifiedDeliveryEvidence evidence) {
    lockProviderReference(evidence.providerCode(), evidence.providerMessageReference());
    List<String> inserted =
        jdbc.query(
            """
            INSERT INTO agendamento.notification_evidence_inbox
              (provider_code, provider_event_id, provider_message_reference,
               provider_sequence, delivery_state, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (provider_code, provider_event_id) DO NOTHING
            RETURNING provider_event_id
            """,
            (row, index) -> row.getString("provider_event_id"),
            evidence.providerCode(),
            evidence.providerEventId(),
            evidence.providerMessageReference(),
            evidence.providerSequence(),
            evidence.deliveryState(),
            java.sql.Timestamp.from(evidence.occurredAt()));
    if (inserted.isEmpty()) {
      reconcilePendingEvidence(evidence.providerCode(), evidence.providerMessageReference());
      return EvidenceAdmission.DUPLICATE;
    }
    reconcilePendingEvidence(evidence.providerCode(), evidence.providerMessageReference());
    List<UUID> linked =
        jdbc.query(
            """
            SELECT notification_id FROM agendamento.notification_evidence_inbox
            WHERE provider_code = ? AND provider_event_id = ? AND notification_id IS NOT NULL
            """,
            (row, index) -> row.getObject("notification_id", UUID.class),
            evidence.providerCode(),
            evidence.providerEventId());
    if (linked.isEmpty()) {
      return EvidenceAdmission.ADMITTED_PENDING_CORRELATION;
    }
    return EvidenceAdmission.ADMITTED;
  }

  private void lockProviderReference(String providerCode, String providerReference) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtext(?), hashtext(?))",
        (org.springframework.jdbc.core.ResultSetExtractor<Void>)
            resultSet -> {
              resultSet.next();
              return null;
            },
        providerCode,
        providerReference);
  }

  private void reconcilePendingEvidence(String providerCode, String providerReference) {
    List<PendingEvidence> pending =
        jdbc.query(
            """
            UPDATE agendamento.notification_evidence_inbox e
            SET notification_id = n.notification_id
            FROM agendamento.notification_intent n
            WHERE e.provider_code = ? AND e.provider_message_reference = ?
              AND e.notification_id IS NULL AND n.provider_code = e.provider_code
              AND n.provider_message_reference = e.provider_message_reference
            RETURNING e.notification_id, e.provider_event_id, e.provider_sequence,
                      e.delivery_state, e.occurred_at
            """,
            (row, index) ->
                new PendingEvidence(
                    row.getObject("notification_id", UUID.class),
                    row.getString("provider_event_id"),
                    row.getLong("provider_sequence"),
                    row.getString("delivery_state"),
                    row.getTimestamp("occurred_at").toInstant()),
            providerCode,
            providerReference);
    pending.stream()
        .sorted(
            java.util.Comparator.comparingLong(PendingEvidence::providerSequence)
                .thenComparing(PendingEvidence::occurredAt)
                .thenComparing(PendingEvidence::providerEventId))
        .forEach(
            evidence ->
                persistAndApplyEvidence(
                    new VerifiedDeliveryEvidence(
                        providerCode,
                        evidence.providerEventId(),
                        providerReference,
                        evidence.providerSequence(),
                        evidence.deliveryState(),
                        evidence.occurredAt()),
                    evidence.notificationId()));
  }

  private void persistAndApplyEvidence(VerifiedDeliveryEvidence evidence, UUID notificationId) {
    jdbc.update(
        """
        INSERT INTO agendamento.notification_delivery_evidence
          (provider_code, provider_event_id, notification_id, provider_sequence,
           delivery_state, occurred_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT (provider_code, provider_event_id) DO NOTHING
        """,
        evidence.providerCode(),
        evidence.providerEventId(),
        notificationId,
        evidence.providerSequence(),
        evidence.deliveryState(),
        java.sql.Timestamp.from(evidence.occurredAt()));
    String state = evidence.deliveryState().equals("DELIVERED") ? "DELIVERED" : "DELIVERY_FAILED";
    jdbc.update(
        """
        UPDATE agendamento.notification_intent
        SET state = ?, accepted_at = coalesce(accepted_at, ?),
            delivered_at = CASE WHEN ? THEN ?::timestamptz ELSE NULL END,
            last_evidence_sequence = ?
        WHERE notification_id = ? AND last_evidence_sequence < ?
          AND state IN ('ACCEPTED', 'OUTCOME_UNKNOWN', 'DELIVERED', 'DELIVERY_FAILED')
        """,
        state,
        java.sql.Timestamp.from(evidence.occurredAt()),
        state.equals("DELIVERED"),
        java.sql.Timestamp.from(evidence.occurredAt()),
        evidence.providerSequence(),
        notificationId,
        evidence.providerSequence());
  }

  private record PendingEvidence(
      UUID notificationId,
      String providerEventId,
      long providerSequence,
      String deliveryState,
      java.time.Instant occurredAt) {}

  private static NotificationOverview overview(java.sql.ResultSet row)
      throws java.sql.SQLException {
    return new NotificationOverview(
        row.getObject("notification_id", UUID.class),
        row.getObject("appointment_id", UUID.class),
        row.getLong("appointment_version"),
        row.getString("message_type"),
        row.getString("state"),
        row.getInt("attempt_count"),
        row.getTimestamp("next_attempt_at").toInstant(),
        row.getTimestamp("created_at").toInstant(),
        row.getString("last_error_code"));
  }

  private static boolean safeError(String code) {
    return code != null
        && List.of(
                "RECIPIENT_UNAVAILABLE",
                "TRANSPORT_RETRYABLE",
                "TRANSPORT_TERMINAL",
                "TRANSPORT_OUTCOME_UNKNOWN",
                "LEASE_EXPIRED_OUTCOME_UNKNOWN")
            .contains(code);
  }
}
