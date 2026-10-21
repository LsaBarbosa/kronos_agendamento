package com.appagendamento.subscriptions.application;

import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.subscriptions.api.CommercialEligibilityIdentitySource;
import com.appagendamento.subscriptions.api.VerifiedCommercialIdentity;
import com.appagendamento.subscriptions.domain.TrialGrantReceipt;
import com.appagendamento.tenant.api.TenantTrialProvisioner;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProvisionInitialTrial implements TenantTrialProvisioner {

  private static final String SCOPE = "subscriptions.trial.grant.v1";
  private static final Duration TRIAL_DURATION = Duration.ofDays(14);

  private final CommercialEligibilityIdentitySource identities;
  private final SubscriptionRepository subscriptions;
  private final IdempotencyCoordinator idempotency;
  private final TrialGrantReceiptCodec codec;
  private final Clock clock;

  public ProvisionInitialTrial(
      CommercialEligibilityIdentitySource identities,
      SubscriptionRepository subscriptions,
      IdempotencyCoordinator idempotency,
      TrialGrantReceiptCodec codec,
      Clock clock) {
    this.identities = identities;
    this.subscriptions = subscriptions;
    this.idempotency = idempotency;
    this.codec = codec;
    this.clock = clock;
  }

  @Override
  public void provisionIfEligible(UUID tenantId) {
    identities.findVerifiedIdentity(tenantId).ifPresent(identity -> grant(tenantId, identity));
  }

  private void grant(UUID tenantId, VerifiedCommercialIdentity identity) {
    String identityDigest = digest(identity.opaqueStableValue());
    String key = "trial-grant-v1-" + tenantId;
    idempotency.execute(
        tenantId,
        SCOPE,
        key,
        identityDigest,
        () -> {},
        operationId -> {
          var startedAt = clock.instant();
          var endsAt = startedAt.plus(TRIAL_DURATION);
          UUID subscriptionId = operationId;
          boolean granted =
              subscriptions.grantTrial(
                  tenantId, subscriptionId, identityDigest, startedAt, endsAt, operationId);
          return new TrialGrantReceipt(
              granted,
              granted ? subscriptionId : null,
              granted ? startedAt : null,
              granted ? endsAt : null);
        },
        codec);
  }

  private static String digest(String opaqueIdentity) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(opaqueIdentity.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
