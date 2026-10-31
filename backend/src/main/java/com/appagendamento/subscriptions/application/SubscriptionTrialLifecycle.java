package com.appagendamento.subscriptions.application;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.subscriptions.api.PublicSubscriptionEligibility;
import com.appagendamento.subscriptions.domain.Subscription;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubscriptionTrialLifecycle implements PublicSubscriptionEligibility {

  private final SubscriptionRepository subscriptions;
  private final SubscriptionDunningRepository dunning;
  private final TenantAgendaLock agendaLock;
  private final Clock clock;

  public SubscriptionTrialLifecycle(
      SubscriptionRepository subscriptions,
      SubscriptionDunningRepository dunning,
      TenantAgendaLock agendaLock,
      Clock clock) {
    this.subscriptions = subscriptions;
    this.dunning = dunning;
    this.agendaLock = agendaLock;
    this.clock = clock;
  }

  @Override
  @Transactional
  public boolean allowsNewPublicAppointments(UUID tenantId) {
    agendaLock.acquire(tenantId);
    var asOf = clock.instant();
    Optional<Subscription> current = subscriptions.findCurrent(tenantId);
    if (current.isEmpty()) return false;
    Subscription subscription = current.get();
    if (subscription.status().equals("TRIALING") && !asOf.isBefore(subscription.trialEndsAt())) {
      UUID operationId =
          UUID.nameUUIDFromBytes(
              (subscription.subscriptionId() + ":trial-expired:v" + subscription.version())
                  .getBytes(StandardCharsets.UTF_8));
      subscriptions.expireTrial(
          tenantId, subscription.subscriptionId(), subscription.version(), asOf, operationId);
      current = subscriptions.findCurrent(tenantId);
    }
    if (current
        .filter(value -> value.status().equals("ACTIVE"))
        .filter(value -> value.paidThroughAt() != null && !asOf.isBefore(value.paidThroughAt()))
        .isPresent()) {
      dunning.markPastDue(current.orElseThrow(), asOf);
      current = subscriptions.findCurrent(tenantId);
    }
    if (current
        .filter(value -> value.status().equals("PAST_DUE"))
        .filter(value -> value.graceEndsAt() != null && !asOf.isBefore(value.graceEndsAt()))
        .isPresent()) {
      dunning.suspendPastDue(current.orElseThrow(), asOf);
      current = subscriptions.findCurrent(tenantId);
    }
    return current
        .filter(
            value -> {
              if (value.status().equals("TRIALING")) return asOf.isBefore(value.trialEndsAt());
              if (value.status().equals("ACTIVE"))
                return value.paidThroughAt() != null && asOf.isBefore(value.paidThroughAt());
              return value.status().equals("PAST_DUE")
                  && value.graceEndsAt() != null
                  && asOf.isBefore(value.graceEndsAt());
            })
        .isPresent();
  }

  @Transactional
  public int materializeExpiredBatch(int limit) {
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limite inválido");
    List<Subscription> due = subscriptions.findExpiredTrials(clock.instant(), limit);
    int expired = 0;
    for (Subscription item : due) {
      if (materializeExpired(item.tenantId())) expired++;
    }
    expired += materializeBillingBatch(limit);
    return expired;
  }

  @Transactional
  public int materializeBillingBatch(int limit) {
    if (limit < 1 || limit > 500) throw new IllegalArgumentException("Limite inválido");
    var asOf = clock.instant();
    int changed = 0;
    for (Subscription item : dunning.findDueActive(asOf, limit)) {
      if (materializePastDue(item.tenantId(), asOf)) changed++;
    }
    for (Subscription item : dunning.findGraceElapsed(asOf, limit)) {
      if (materializeSuspension(item.tenantId(), asOf)) changed++;
    }
    return changed;
  }

  private boolean materializePastDue(UUID tenantId, java.time.Instant asOf) {
    agendaLock.acquire(tenantId);
    return subscriptions
        .findCurrent(tenantId)
        .filter(value -> value.status().equals("ACTIVE"))
        .filter(value -> value.paidThroughAt() != null && !asOf.isBefore(value.paidThroughAt()))
        .map(value -> dunning.markPastDue(value, asOf))
        .orElse(false);
  }

  private boolean materializeSuspension(UUID tenantId, java.time.Instant asOf) {
    agendaLock.acquire(tenantId);
    return subscriptions
        .findCurrent(tenantId)
        .filter(value -> value.status().equals("PAST_DUE"))
        .filter(value -> value.graceEndsAt() != null && !asOf.isBefore(value.graceEndsAt()))
        .map(value -> dunning.suspendPastDue(value, asOf))
        .orElse(false);
  }

  @Transactional
  public boolean materializeExpired(UUID tenantId) {
    agendaLock.acquire(tenantId);
    var asOf = clock.instant();
    return subscriptions
        .findCurrent(tenantId)
        .filter(value -> value.status().equals("TRIALING"))
        .filter(value -> !asOf.isBefore(value.trialEndsAt()))
        .map(
            value -> {
              UUID operationId =
                  UUID.nameUUIDFromBytes(
                      (value.subscriptionId() + ":trial-expired:v" + value.version())
                          .getBytes(StandardCharsets.UTF_8));
              return subscriptions.expireTrial(
                  tenantId, value.subscriptionId(), value.version(), asOf, operationId);
            })
        .orElse(false);
  }
}
