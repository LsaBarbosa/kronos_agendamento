package com.appagendamento.clients.application;

import com.appagendamento.audit.api.AuditCorrelationId;
import com.appagendamento.audit.api.AuditEvent;
import com.appagendamento.audit.api.AuditEventRecorder;
import com.appagendamento.clients.domain.ClientNoShowPolicy;
import com.appagendamento.idempotency.api.CommandResultCodec;
import com.appagendamento.idempotency.api.IdempotencyCoordinator;
import com.appagendamento.idempotency.api.IdempotentCommandResult;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReplaceClientNoShowPolicy {

  private final ClientNoShowPolicyRepository policies;
  private final AdminAccessPolicy accessPolicy;
  private final IdempotencyCoordinator idempotency;
  private final AuditEventRecorder audit;
  private final AuditCorrelationId correlation;
  private final CommandResultCodec<ClientNoShowPolicy> codec;

  public ReplaceClientNoShowPolicy(
      ClientNoShowPolicyRepository policies,
      AdminAccessPolicy accessPolicy,
      IdempotencyCoordinator idempotency,
      AuditEventRecorder audit,
      AuditCorrelationId correlation,
      CommandResultCodec<ClientNoShowPolicy> codec) {
    this.policies = policies;
    this.accessPolicy = accessPolicy;
    this.idempotency = idempotency;
    this.audit = audit;
    this.correlation = correlation;
    this.codec = codec;
  }

  public IdempotentCommandResult<ClientNoShowPolicy> execute(
      AuthenticatedAdmin actor, String key, long expectedVersion, int threshold) {
    requireCurrent(actor);
    if (expectedVersion < 0 || threshold < 1 || threshold > 5) {
      throw new IllegalArgumentException("Política de faltas fora dos limites permitidos");
    }
    String digest = digest(actor, expectedVersion, threshold);
    return idempotency.execute(
        actor.tenantId(),
        "clients.no-show-policy.replace.v1",
        key,
        digest,
        () -> requireCurrent(actor),
        operationId -> replace(actor, expectedVersion, threshold),
        codec);
  }

  private ClientNoShowPolicy replace(
      AuthenticatedAdmin actor, long expectedVersion, int threshold) {
    policies.lockTenant(actor.tenantId());
    Optional<ClientNoShowPolicy> updated =
        policies.replacePolicy(actor.tenantId(), threshold, expectedVersion);
    ClientNoShowPolicy policy =
        updated.orElseThrow(ClientNoShowPolicyVersionConflictException::new);
    UUID traceId = correlation.currentTraceId();
    audit.record(
        new AuditEvent(
            UUID.randomUUID(),
            actor.tenantId(),
            actor.adminUserId(),
            "ADMIN",
            "TENANT_ADMIN",
            "CLIENT_NO_SHOW_POLICY_UPDATED",
            "TENANT",
            actor.tenantId(),
            "COMMITTED",
            traceId,
            traceId,
            "{\"changed_fields\":[\"no_show_threshold\"],\"version_before\":"
                + expectedVersion
                + ",\"version_after\":"
                + policy.version()
                + "}"));
    return policy;
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientNoShowPolicyAccessDeniedException();
    }
  }

  private static String digest(AuthenticatedAdmin actor, long expectedVersion, int threshold) {
    String canonical =
        "clients.no-show-policy.replace.canonical.v1\n"
            + actor.tenantId()
            + "\n"
            + expectedVersion
            + "\n"
            + threshold;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 indisponível", exception);
    }
  }
}
