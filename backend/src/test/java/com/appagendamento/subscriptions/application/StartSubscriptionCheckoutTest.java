package com.appagendamento.subscriptions.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.appagendamento.appointments.api.TenantAgendaLock;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.subscriptions.api.BillingOfferCatalog;
import com.appagendamento.subscriptions.api.HostedCheckoutProvider;
import com.appagendamento.subscriptions.domain.PlanCode;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StartSubscriptionCheckoutTest {

  @Mock private AdminAccessPolicy access;
  @Mock private SubscriptionRepository subscriptions;
  @Mock private SubscriptionDunningRepository dunning;
  @Mock private BillingRepository billing;
  @Mock private BillingOfferCatalog offers;
  @Mock private HostedCheckoutProvider provider;
  @Mock private IdempotencyCoordinator idempotency;
  @Mock private CheckoutReceiptCodec codec;
  @Mock private TenantAgendaLock tenantLock;
  @Mock private Clock clock;

  @Test
  void tenantContextFromAIsRejectedBeforeCheckoutEffectsUnderTenantBSession() {
    UUID authenticatedTenant = UUID.randomUUID();
    UUID intentTenant = UUID.randomUUID();
    AuthenticatedAdmin admin = admin(authenticatedTenant);
    when(access.isCurrent(admin)).thenReturn(true);
    StartSubscriptionCheckout subject =
        new StartSubscriptionCheckout(
            access,
            subscriptions,
            dunning,
            billing,
            offers,
            provider,
            idempotency,
            codec,
            tenantLock,
            clock);

    assertThatThrownBy(
            () ->
                subject.execute(
                    admin, intentTenant, PlanCode.MONTHLY, 0, "intent-original-tenant-a"))
        .isInstanceOf(SubscriptionCheckoutConflictException.class)
        .hasMessage("TENANT_CONTEXT_CHANGED");

    verify(access).isCurrent(admin);
    verifyNoInteractions(
        subscriptions, dunning, billing, offers, provider, idempotency, tenantLock, clock);
  }

  private static AuthenticatedAdmin admin(UUID tenantId) {
    UUID adminId = UUID.randomUUID();
    String subject = UUID.randomUUID().toString();
    return new AuthenticatedAdmin() {
      @Override
      public UUID adminUserId() {
        return adminId;
      }

      @Override
      public String issuer() {
        return "https://identity.test/realms/agendamento";
      }

      @Override
      public String subject() {
        return subject;
      }

      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public String role() {
        return "TENANT_ADMIN";
      }
    };
  }
}
