package com.appagendamento.notifications.application;

import com.appagendamento.notifications.domain.NotificationType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {

  void createIntent(
      UUID tenantId,
      UUID appointmentId,
      long appointmentVersion,
      UUID recipientClientId,
      UUID sourceEventId,
      NotificationType type);

  List<NotificationClaim> claimDue(int batchSize, Duration leaseDuration);

  boolean markAccepted(NotificationClaim claim, String providerCode, String providerReference);

  boolean markRetryable(
      NotificationClaim claim, Duration backoff, int maxAttempts, String errorCode);

  boolean markFailed(NotificationClaim claim, String errorCode);

  boolean markUnknown(NotificationClaim claim, String errorCode);

  boolean markObsolete(NotificationClaim claim);

  List<NotificationSummary> summarize(UUID tenantId);

  List<NotificationOverview> list(UUID tenantId, int limit);

  Optional<NotificationDetail> detail(UUID tenantId, UUID notificationId);

  boolean recordEvidence(VerifiedDeliveryEvidence evidence);

  record NotificationClaim(
      UUID notificationId,
      UUID tenantId,
      UUID appointmentId,
      long appointmentVersion,
      UUID recipientClientId,
      NotificationType type,
      String externalIdempotencyKey,
      int attemptNumber,
      UUID claimToken) {}

  record NotificationSummary(String state, long count, Long oldestPendingSeconds) {}

  record NotificationOverview(
      UUID notificationId,
      UUID appointmentId,
      long appointmentVersion,
      String type,
      String state,
      int attemptCount,
      Instant nextAttemptAt,
      Instant createdAt,
      String lastErrorCode) {}

  record NotificationDetail(NotificationOverview overview, List<AttemptOverview> attempts) {}

  record AttemptOverview(
      int attemptNumber, String state, String errorCode, Instant startedAt, Instant completedAt) {}

  record VerifiedDeliveryEvidence(
      String providerCode,
      String providerEventId,
      String providerMessageReference,
      long providerSequence,
      String deliveryState,
      Instant occurredAt) {
    public VerifiedDeliveryEvidence {
      if (providerCode == null
          || !providerCode.matches("[a-z0-9][a-z0-9._-]{0,63}")
          || providerEventId == null
          || providerEventId.isBlank()
          || providerEventId.length() > 160
          || providerMessageReference == null
          || providerMessageReference.isBlank()
          || providerMessageReference.length() > 160
          || providerSequence < 0
          || !List.of("DELIVERED", "FAILED").contains(deliveryState)
          || occurredAt == null) {
        throw new IllegalArgumentException("Evidência normalizada inválida");
      }
    }
  }
}
