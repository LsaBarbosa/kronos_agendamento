package com.appagendamento.subscriptions.application;

import com.appagendamento.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository {

  boolean grantTrial(
      UUID tenantId,
      UUID subscriptionId,
      String commercialIdentityDigest,
      Instant startsAt,
      Instant endsAt,
      UUID operationId);

  Optional<Subscription> findCurrent(UUID tenantId);

  boolean expireTrial(
      UUID tenantId,
      UUID subscriptionId,
      long expectedVersion,
      Instant expiredAt,
      UUID operationId);

  List<Subscription> findExpiredTrials(Instant asOf, int limit);
}
