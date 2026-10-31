package com.appagendamento.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.appagendamento.appointments.api.AppointmentRetentionFactsQuery;
import com.appagendamento.clients.api.ClientRetentionFactsQuery;
import com.appagendamento.exports.api.ExportRetentionFactsQuery;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.notifications.api.NotificationRetentionFactsQuery;
import com.appagendamento.outbox.api.OutboxRetentionFactsQuery;
import com.appagendamento.retention.application.GetRetentionAssessment;
import com.appagendamento.subscriptions.api.BillingRetentionFactsQuery;
import com.appagendamento.tenant.api.TenantRetentionFactsQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class GetRetentionAssessmentTest {

  @Test
  void distinguishesUnavailableSourceFromZeroAndKeepsUnqueriedCategoryDistinct() {
    var fixture = new Fixture();
    when(fixture.clients.query(fixture.tenantId))
        .thenThrow(new DataAccessResourceFailureException("private db error"));

    var result = fixture.service.execute(fixture.actor);

    var clients = category(result, "CLIENT_RECORDS");
    assertThat(clients.evaluationStatus()).isEqualTo("SOURCE_UNAVAILABLE");
    assertThat(clients.sourceStatus()).isEqualTo("UNAVAILABLE");
    assertThat(clients.counts()).isEmpty();
    assertThat(clients.reasons()).containsExactly("SOURCE_UNAVAILABLE");

    var audit = category(result, "AUDIT_EVENTS");
    assertThat(audit.evaluationStatus()).isEqualTo("NOT_EVALUATED");
    assertThat(audit.sourceStatus()).isEqualTo("NOT_QUERIED");
    assertThat(audit.counts()).isEmpty();
    assertThat(result.blockers()).contains("CLIENT_RECORDS_SOURCE_UNAVAILABLE");
  }

  @Test
  void marksCappedCountAsLowerBoundRatherThanExact() {
    var fixture = new Fixture();
    when(fixture.appointments.query(eq(fixture.tenantId), eq(fixture.referenceAt)))
        .thenReturn(
            new AppointmentRetentionFactsQuery.Facts(
                new AppointmentRetentionFactsQuery.Count(10_000, true),
                new AppointmentRetentionFactsQuery.Count(0, false)));

    var result = fixture.service.execute(fixture.actor);

    assertThat(category(result, "APPOINTMENTS").counts().get("records"))
        .isEqualTo(
            new com.appagendamento.retention.application.RetentionAssessment.Count(
                10_000, "AT_LEAST_LIMIT", 10_000));
    assertThat(category(result, "APPOINTMENTS").reasons()).contains("COUNT_LIMIT_REACHED");
  }

  private static com.appagendamento.retention.application.RetentionAssessment.Category category(
      com.appagendamento.retention.application.RetentionAssessment assessment, String code) {
    return assessment.categories().stream()
        .filter(item -> item.code().equals(code))
        .findFirst()
        .orElseThrow();
  }

  private static final class Fixture {
    private final UUID tenantId = UUID.randomUUID();
    private final Instant referenceAt = Instant.parse("2026-10-26T12:00:00Z");
    private final AuthenticatedAdmin actor = mock(AuthenticatedAdmin.class);
    private final AdminAccessPolicy access = mock(AdminAccessPolicy.class);
    private final TenantRetentionFactsQuery tenant = mock(TenantRetentionFactsQuery.class);
    private final ClientRetentionFactsQuery clients = mock(ClientRetentionFactsQuery.class);
    private final AppointmentRetentionFactsQuery appointments =
        mock(AppointmentRetentionFactsQuery.class);
    private final NotificationRetentionFactsQuery notifications =
        mock(NotificationRetentionFactsQuery.class);
    private final OutboxRetentionFactsQuery outbox = mock(OutboxRetentionFactsQuery.class);
    private final ExportRetentionFactsQuery exports = mock(ExportRetentionFactsQuery.class);
    private final BillingRetentionFactsQuery billing = mock(BillingRetentionFactsQuery.class);
    private final GetRetentionAssessment service;

    private Fixture() {
      when(actor.tenantId()).thenReturn(tenantId);
      when(access.isCurrent(actor)).thenReturn(true);
      when(tenant.query(tenantId)).thenReturn(new TenantRetentionFactsQuery.Facts("ACTIVE"));
      when(clients.query(tenantId))
          .thenReturn(
              new ClientRetentionFactsQuery.Facts(new ClientRetentionFactsQuery.Count(0, false)));
      when(appointments.query(eq(tenantId), eq(referenceAt)))
          .thenReturn(
              new AppointmentRetentionFactsQuery.Facts(
                  new AppointmentRetentionFactsQuery.Count(0, false),
                  new AppointmentRetentionFactsQuery.Count(0, false)));
      when(notifications.query(tenantId))
          .thenReturn(
              new NotificationRetentionFactsQuery.Facts(
                  new NotificationRetentionFactsQuery.Count(0, false),
                  new NotificationRetentionFactsQuery.Count(0, false),
                  new NotificationRetentionFactsQuery.Count(0, false),
                  new NotificationRetentionFactsQuery.Count(0, false)));
      when(outbox.query(tenantId))
          .thenReturn(
              new OutboxRetentionFactsQuery.Facts(
                  new OutboxRetentionFactsQuery.Count(0, false),
                  new OutboxRetentionFactsQuery.Count(0, false),
                  new OutboxRetentionFactsQuery.Count(0, false)));
      when(exports.query(tenantId, referenceAt))
          .thenReturn(
              new ExportRetentionFactsQuery.Facts(
                  new ExportRetentionFactsQuery.Count(0, false),
                  new ExportRetentionFactsQuery.Count(0, false),
                  new ExportRetentionFactsQuery.Count(0, false),
                  new ExportRetentionFactsQuery.Count(0, false)));
      when(billing.query(tenantId))
          .thenReturn(
              new BillingRetentionFactsQuery.Facts(
                  null,
                  new BillingRetentionFactsQuery.Count(0, false),
                  new BillingRetentionFactsQuery.Count(0, false),
                  new BillingRetentionFactsQuery.Count(0, false),
                  new BillingRetentionFactsQuery.Count(0, false),
                  new BillingRetentionFactsQuery.Count(0, false)));
      service =
          new GetRetentionAssessment(
              access,
              tenant,
              clients,
              appointments,
              notifications,
              outbox,
              exports,
              billing,
              Clock.fixed(referenceAt, ZoneOffset.UTC));
    }
  }
}
