package com.appagendamento.subscriptions.application;

import com.appagendamento.subscriptions.domain.AnnualRenewalNoticeView;
import com.appagendamento.subscriptions.domain.DunningAttemptClaim;
import com.appagendamento.subscriptions.domain.DunningAttemptView;
import com.appagendamento.subscriptions.domain.Subscription;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubscriptionDunningRepository {

  List<Subscription> findDueActive(Instant asOf, int limit);

  List<Subscription> findGraceElapsed(Instant asOf, int limit);

  boolean markPastDue(Subscription expected, Instant asOf);

  boolean suspendPastDue(Subscription expected, Instant asOf);

  void closeCyclesForPayment(UUID tenantId, UUID subscriptionId, Instant paidAt);

  boolean hasUnresolvedPayment(UUID tenantId, UUID subscriptionId);

  List<DunningAttemptView> listAttempts(UUID tenantId);

  List<AnnualRenewalNoticeView> listAnnualRenewalNotices(UUID tenantId);

  int materializeAnnualRenewalNotices(Instant asOf, int limit);

  List<ProviderReference> pendingPaymentEvidence(int limit);

  int reconcilePaymentEvidence(String providerCode, String accountId, String reference);

  DunningMetricsSnapshot metricsSnapshot(Instant asOf);

  Optional<DunningAttemptClaim> claimNextAttempt(Duration lease, boolean reconcilerAvailable);

  boolean authorizeAttemptSend(DunningAttemptClaim claim);

  boolean blockAttemptForConfiguration(DunningAttemptClaim claim, String reasonCode);

  boolean recordTransportResult(
      DunningAttemptClaim claim,
      com.appagendamento.subscriptions.application.DunningChargeTransport.TransportResult result,
      Duration retryDelay,
      int maxTechnicalAttempts);

  boolean recordReconciliationResult(
      DunningAttemptClaim claim,
      com.appagendamento.subscriptions.application.DunningChargeTransport.TransportResult result,
      Duration retryDelay,
      int maxTechnicalAttempts);

  record ProviderReference(String providerCode, String accountId, String reference) {}
}
