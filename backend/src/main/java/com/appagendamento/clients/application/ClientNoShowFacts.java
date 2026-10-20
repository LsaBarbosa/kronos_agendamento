package com.appagendamento.clients.application;

import com.appagendamento.clients.api.ClientBookingRestrictionQuery;
import com.appagendamento.clients.api.ClientNoShowFactsQuery;
import com.appagendamento.clients.domain.ClientNoShowPolicy;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClientNoShowFacts {

  private static final int DEFAULT_LIMIT = 50;
  private static final int MAX_LIMIT = 100;

  private final ClientNoShowFactsQuery facts;
  private final ClientNoShowPolicyRepository policies;
  private final AdminAccessPolicy accessPolicy;
  private final Clock clock;
  private final ClientBookingRestrictionQuery restrictions;

  public ClientNoShowFacts(
      ClientNoShowFactsQuery facts,
      ClientNoShowPolicyRepository policies,
      AdminAccessPolicy accessPolicy,
      Clock clock,
      ClientBookingRestrictionQuery restrictions) {
    this.facts = facts;
    this.policies = policies;
    this.accessPolicy = accessPolicy;
    this.clock = clock;
    this.restrictions = restrictions;
  }

  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public Result execute(
      AuthenticatedAdmin actor, UUID clientId, String cursor, Integer requestedLimit) {
    requireCurrent(actor);
    if (clientId == null
        || (requestedLimit != null && (requestedLimit < 1 || requestedLimit > MAX_LIMIT))) {
      throw new InvalidClientNoShowQueryException();
    }
    int limit = requestedLimit == null ? DEFAULT_LIMIT : requestedLimit;
    Position position = decode(cursor);
    Instant asOf = position == null ? clock.instant() : position.asOf();
    Instant windowStart = asOf.atZone(ZoneId.of("UTC")).minusMonths(12).toInstant();
    Optional<ClientNoShowFactsQuery.ClientNoShowFactsPage> page =
        facts.find(
            actor.tenantId(),
            clientId,
            asOf,
            windowStart,
            position == null ? null : position.recordedAt(),
            position == null ? null : position.historyId(),
            limit + 1);
    var data = page.orElseThrow(ClientNoShowFactsNotFoundException::new);
    boolean hasMore = data.facts().size() > limit;
    List<ClientNoShowFactsQuery.Fact> items =
        List.copyOf(data.facts().subList(0, Math.min(limit, data.facts().size())));
    String nextCursor = hasMore && !items.isEmpty() ? encode(asOf, items.getLast()) : null;
    Optional<ClientNoShowPolicy> policy = policies.findPolicy(actor.tenantId());
    return new Result(
        clientId,
        asOf,
        windowStart,
        data.timeZone(),
        data.count(),
        data.totalFacts(),
        policy.map(ClientNoShowPolicy::threshold).orElse(null),
        policy.map(ClientNoShowPolicy::version).orElse(0L),
        policy.isPresent() ? "CONFIGURED" : "NOT_CONFIGURED",
        restrictions.status(actor.tenantId(), clientId).name(),
        restrictions.active(actor.tenantId(), clientId).orElse(null),
        restrictions.history(actor.tenantId(), clientId),
        items,
        nextCursor,
        hasMore);
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new ClientNoShowFactsAccessDeniedException();
    }
  }

  private static String encode(Instant asOf, ClientNoShowFactsQuery.Fact last) {
    String value = asOf + "\n" + last.recordedAt() + "\n" + last.historyId();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  private static Position decode(String cursor) {
    if (cursor == null || cursor.isBlank()) return null;
    try {
      String[] values =
          new String(Base64.getUrlDecoder().decode(cursor), java.nio.charset.StandardCharsets.UTF_8)
              .split("\\n", -1);
      if (values.length != 3) throw new IllegalArgumentException();
      return new Position(
          Instant.parse(values[0]), Instant.parse(values[1]), UUID.fromString(values[2]));
    } catch (RuntimeException exception) {
      throw new InvalidClientNoShowQueryException();
    }
  }

  public record Result(
      UUID clientId,
      Instant asOf,
      Instant windowStart,
      String timeZone,
      long count,
      long totalFacts,
      Integer threshold,
      long policyVersion,
      String policyStatus,
      String restrictionStatus,
      ClientBookingRestrictionQuery.ClientBookingRestriction activeRestriction,
      List<ClientBookingRestrictionQuery.ClientBookingRestrictionHistory> restrictionHistory,
      List<ClientNoShowFactsQuery.Fact> facts,
      String nextCursor,
      boolean hasMore) {}

  private record Position(Instant asOf, Instant recordedAt, UUID historyId) {}
}
