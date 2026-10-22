package com.appagendamento.subscriptions.application;

import com.appagendamento.subscriptions.domain.DunningAttemptView;
import com.appagendamento.subscriptions.domain.Subscription;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SubscriptionDunningRepository {

  List<Subscription> findDueActive(Instant asOf, int limit);

  List<Subscription> findGraceElapsed(Instant asOf, int limit);

  boolean markPastDue(Subscription expected, Instant asOf);

  boolean suspendPastDue(Subscription expected, Instant asOf);

  void closeCyclesForPayment(UUID tenantId, UUID subscriptionId, Instant paidAt);

  List<DunningAttemptView> listAttempts(UUID tenantId);
}
