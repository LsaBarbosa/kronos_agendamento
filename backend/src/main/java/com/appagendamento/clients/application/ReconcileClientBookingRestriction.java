package com.appagendamento.clients.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.clients.api.ClientNoShowFactsQuery;
import com.appagendamento.clients.application.ClientBookingRestrictionCommandRepository.NoShowCause;
import com.appagendamento.clients.domain.ClientNoShowPolicy;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReconcileClientBookingRestriction {

  private static final String SCOPE = "clients.booking-restriction.reconcile.v1";

  private final AdminAccessPolicy accessPolicy;
  private final ClientBookingRestrictionCommandRepository restrictions;
  private final ClientNoShowPolicyRepository policies;
  private final ClientNoShowFactsQuery facts;
  private final IdempotencyCoordinator idempotency;
  private final ClientRestrictionReconciliationReceiptCodec codec;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final OutboxEventRecorder outbox;
  private final Clock clock;

  public ReconcileClientBookingRestriction(
      AdminAccessPolicy accessPolicy,
      ClientBookingRestrictionCommandRepository restrictions,
      ClientNoShowPolicyRepository policies,
      ClientNoShowFactsQuery facts,
      IdempotencyCoordinator idempotency,
      ClientRestrictionReconciliationReceiptCodec codec,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      OutboxEventRecorder outbox,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.restrictions = restrictions;
    this.policies = policies;
    this.facts = facts;
    this.idempotency = idempotency;
    this.codec = codec;
    this.audit = audit;
    this.correlation = correlation;
    this.outbox = outbox;
    this.clock = clock;
  }

  public IdempotentCommandResult<ClientRestrictionReconciliationReceipt> execute(
      AuthenticatedAdmin actor, String key, UUID clientId, long expectedPolicyVersion) {
    requireCurrent(actor);
    if (clientId == null || expectedPolicyVersion < 1) {
      throw new IllegalArgumentException("Dados de reconciliação inválidos");
    }
    String digest = digest(actor.tenantId(), clientId, expectedPolicyVersion);
    return idempotency.execute(
        actor.tenantId(),
        SCOPE,
        key,
        digest,
        () -> requireCurrent(actor),
        operationId -> reconcile(actor, clientId, expectedPolicyVersion, operationId),
        codec);
  }

  private ClientRestrictionReconciliationReceipt reconcile(
      AuthenticatedAdmin actor, UUID clientId, long expectedPolicyVersion, UUID operationId) {
    restrictions.lockTenant(actor.tenantId());
    ClientNoShowPolicy policy =
        policies
            .findPolicy(actor.tenantId())
            .orElseThrow(ClientNoShowPolicyNotConfiguredException::new);
    if (policy.version() != expectedPolicyVersion) {
      throw new ClientNoShowPolicyVersionConflictException();
    }

    var asOf = clock.instant();
    var windowStart = asOf.atZone(ZoneOffset.UTC).minusMonths(12).toInstant();
    var page =
        facts
            .find(actor.tenantId(), clientId, asOf, windowStart, null, null, 1)
            .orElseThrow(ClientRestrictionNotFoundException::new);
    var active = restrictions.activeForClient(actor.tenantId(), clientId);
    String decision;
    UUID restrictionId = null;
    Long restrictionVersion = null;
    if (active.isPresent()) {
      decision = "ALREADY_RESTRICTED";
      restrictionId = active.get().restrictionId();
      restrictionVersion = active.get().version();
    } else if (page.count() < policy.threshold()) {
      decision = "BELOW_THRESHOLD";
    } else {
      NoShowCause cause =
          restrictions
              .unusedContributingCause(actor.tenantId(), clientId, asOf, windowStart)
              .orElse(null);
      if (cause == null) {
        decision = "NO_UNUSED_CAUSAL_FACT";
      } else {
        var created =
            restrictions.activateFromReconciliation(
                actor.tenantId(),
                clientId,
                cause,
                actor.adminUserId(),
                asOf,
                Math.toIntExact(page.count()),
                policy.threshold(),
                policy.version());
        if (created.isPresent()) {
          decision = "ACTIVATED";
          restrictionId = created.get();
          restrictionVersion = 1L;
        } else {
          var concurrent = restrictions.activeForClient(actor.tenantId(), clientId);
          if (concurrent.isPresent()) {
            decision = "ALREADY_RESTRICTED";
            restrictionId = concurrent.get().restrictionId();
            restrictionVersion = concurrent.get().version();
          } else {
            decision = "NO_UNUSED_CAUSAL_FACT";
          }
        }
      }
    }

    var receipt =
        new ClientRestrictionReconciliationReceipt(
            clientId,
            decision,
            asOf,
            windowStart,
            page.count(),
            policy.threshold(),
            policy.version(),
            restrictionId,
            restrictionVersion);
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "CLIENT_SELF_BOOKING_RECONCILED",
            "CLIENT",
            clientId,
            "COMMITTED",
            traceId,
            traceId,
            "{\"decision\":\""
                + decision
                + "\",\"count\":"
                + page.count()
                + ",\"threshold\":"
                + policy.threshold()
                + ",\"policy_version\":"
                + policy.version()
                + ",\"as_of\":\""
                + asOf
                + "\"}"));
    if ("ACTIVATED".equals(decision)) {
      outbox.record(
          new OutboxEvent(
              UUID.randomUUID(),
              actor.tenantId(),
              "CLIENT_RESTRICTION",
              restrictionId,
              1,
              "ClientSelfBookingBlocked",
              1,
              operationId,
              traceId,
              "{\"clientId\":\""
                  + clientId
                  + "\",\"restrictionId\":\""
                  + restrictionId
                  + "\",\"count\":"
                  + page.count()
                  + ",\"threshold\":"
                  + policy.threshold()
                  + ",\"policyVersion\":"
                  + policy.version()
                  + ",\"version\":1}"));
    }
    return receipt;
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientRestrictionAccessDeniedException();
    }
  }

  private static String digest(UUID tenantId, UUID clientId, long policyVersion) {
    String canonical =
        "clients.booking-restriction.reconcile.canonical.v1\n"
            + tenantId
            + "\n"
            + clientId
            + "\n"
            + policyVersion;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
