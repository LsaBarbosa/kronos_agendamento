package com.appagendamento.clients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.appagendamento.clients.api.ClientNoShowFactsQuery;
import com.appagendamento.clients.application.ClientNoShowFacts;
import com.appagendamento.clients.application.ClientNoShowPolicyRepository;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientNoShowFactsTest {

  @Test
  void computesTwelveCalendarMonthsInUtcAndCapturesClockOnce() {
    Instant asOf = Instant.parse("2024-02-29T17:42:31.123Z");
    CountingClock clock = new CountingClock(asOf);
    UUID tenantId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    AuthenticatedAdmin actor = mock(AuthenticatedAdmin.class);
    when(actor.tenantId()).thenReturn(tenantId);
    AdminAccessPolicy access = mock(AdminAccessPolicy.class);
    when(access.isCurrent(actor)).thenReturn(true);
    ClientNoShowFactsQuery query = mock(ClientNoShowFactsQuery.class);
    when(query.find(
            eq(tenantId),
            eq(clientId),
            eq(asOf),
            eq(Instant.parse("2023-02-28T17:42:31.123Z")),
            eq(null),
            eq(null),
            eq(51)))
        .thenReturn(
            Optional.of(
                new ClientNoShowFactsQuery.ClientNoShowFactsPage(
                    "Pacific/Kiritimati", 0, 0, List.of())));
    ClientNoShowPolicyRepository policies = mock(ClientNoShowPolicyRepository.class);
    when(policies.findPolicy(tenantId)).thenReturn(Optional.empty());

    ClientNoShowFacts.Result result =
        new ClientNoShowFacts(query, policies, access, clock).execute(actor, clientId, null, null);

    assertThat(clock.calls).isEqualTo(1);
    assertThat(result.windowStart()).isEqualTo(Instant.parse("2023-02-28T17:42:31.123Z"));
    assertThat(result.asOf()).isEqualTo(asOf);
    assertThat(result.timeZone()).isEqualTo("Pacific/Kiritimati");
    assertThat(result.policyStatus()).isEqualTo("NOT_CONFIGURED");
    assertThat(result.threshold()).isNull();
    assertThat(result.restrictionStatus()).isEqualTo("NOT_TRACKED");
  }

  private static final class CountingClock extends Clock {
    private final Instant instant;
    private int calls;

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls++;
      return instant;
    }
  }
}
