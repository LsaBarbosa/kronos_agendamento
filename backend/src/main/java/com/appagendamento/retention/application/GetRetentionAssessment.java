package com.appagendamento.retention.application;

import com.appagendamento.appointments.api.AppointmentRetentionFactsQuery;
import com.appagendamento.clients.api.ClientRetentionFactsQuery;
import com.appagendamento.exports.api.ExportRetentionFactsQuery;
import com.appagendamento.identityaccess.api.AdminAccessPolicy;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.notifications.api.NotificationRetentionFactsQuery;
import com.appagendamento.outbox.api.OutboxRetentionFactsQuery;
import com.appagendamento.subscriptions.api.BillingRetentionFactsQuery;
import com.appagendamento.tenant.api.TenantRetentionFactsQuery;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
public class GetRetentionAssessment {

  public static final int COUNT_LIMIT = 10_000;
  public static final int SOURCE_QUERY_TIMEOUT_SECONDS = 2;
  private final AdminAccessPolicy accessPolicy;
  private final TenantRetentionFactsQuery tenantFacts;
  private final ClientRetentionFactsQuery clientFacts;
  private final AppointmentRetentionFactsQuery appointmentFacts;
  private final NotificationRetentionFactsQuery notificationFacts;
  private final OutboxRetentionFactsQuery outboxFacts;
  private final ExportRetentionFactsQuery exportFacts;
  private final BillingRetentionFactsQuery billingFacts;
  private final Clock clock;

  public GetRetentionAssessment(
      AdminAccessPolicy accessPolicy,
      TenantRetentionFactsQuery tenantFacts,
      ClientRetentionFactsQuery clientFacts,
      AppointmentRetentionFactsQuery appointmentFacts,
      NotificationRetentionFactsQuery notificationFacts,
      OutboxRetentionFactsQuery outboxFacts,
      ExportRetentionFactsQuery exportFacts,
      BillingRetentionFactsQuery billingFacts,
      Clock clock) {
    this.accessPolicy = accessPolicy;
    this.tenantFacts = tenantFacts;
    this.clientFacts = clientFacts;
    this.appointmentFacts = appointmentFacts;
    this.notificationFacts = notificationFacts;
    this.outboxFacts = outboxFacts;
    this.exportFacts = exportFacts;
    this.billingFacts = billingFacts;
    this.clock = clock;
  }

  public RetentionAssessment execute(AuthenticatedAdmin actor) {
    requireCurrent(actor);
    Instant startedAt = clock.instant();
    Instant referenceAt = startedAt;
    List<RetentionAssessment.Category> categories = new ArrayList<>();
    List<String> blockers = new ArrayList<>();

    var tenant = inspect(() -> tenantFacts.query(actor.tenantId()));
    if (tenant.available()) {
      categories.add(
          assessed(
              "TENANT_LIFECYCLE",
              tenant.observedAt(),
              Map.of("tenants", exact(1)),
              Map.of("status", tenant.value().lifecycleStatus()),
              List.of()));
    } else {
      categories.add(
          unavailable("TENANT_LIFECYCLE", tenant.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("TENANT_LIFECYCLE_SOURCE_UNAVAILABLE");
    }

    var clients = inspect(() -> clientFacts.query(actor.tenantId()));
    if (clients.available()) {
      categories.add(
          assessed(
              "CLIENT_RECORDS",
              clients.observedAt(),
              Map.of("records", convert(clients.value().clients())),
              Map.of(),
              capReasons(clients.value().clients())));
    } else {
      categories.add(
          unavailable("CLIENT_RECORDS", clients.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("CLIENT_RECORDS_SOURCE_UNAVAILABLE");
    }

    var appointments = inspect(() -> appointmentFacts.query(actor.tenantId(), referenceAt));
    if (appointments.available()) {
      var facts = appointments.value();
      categories.add(
          assessed(
              "APPOINTMENTS",
              appointments.observedAt(),
              Map.of(
                  "records", convert(facts.appointments()),
                  "futureConfirmed", convert(facts.futureConfirmedAppointments())),
              Map.of(),
              capReasons(facts.appointments(), facts.futureConfirmedAppointments())));
      if (facts.futureConfirmedAppointments().value() > 0)
        blockers.add("FUTURE_APPOINTMENTS_PRESENT");
    } else {
      categories.add(
          unavailable("APPOINTMENTS", appointments.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("APPOINTMENTS_SOURCE_UNAVAILABLE");
    }

    var notifications = inspect(() -> notificationFacts.query(actor.tenantId()));
    if (notifications.available()) {
      var facts = notifications.value();
      categories.add(
          assessed(
              "NOTIFICATION_INTENTS",
              notifications.observedAt(),
              Map.of(
                  "records", convert(facts.intents()),
                  "pendingOrClaimed", convert(facts.pendingOrClaimed()),
                  "outcomeUnknown", convert(facts.outcomeUnknown()),
                  "acceptedTransport", convert(facts.accepted())),
              Map.of(),
              capReasons(
                  facts.intents(),
                  facts.pendingOrClaimed(),
                  facts.outcomeUnknown(),
                  facts.accepted())));
      if (facts.pendingOrClaimed().value() > 0) blockers.add("NOTIFICATION_EFFECTS_PENDING");
      if (facts.outcomeUnknown().value() > 0) blockers.add("NOTIFICATION_OUTCOME_UNKNOWN");
    } else {
      categories.add(
          unavailable(
              "NOTIFICATION_INTENTS", notifications.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("NOTIFICATION_SOURCE_UNAVAILABLE");
    }

    var outbox = inspect(() -> outboxFacts.query(actor.tenantId()));
    if (outbox.available()) {
      var facts = outbox.value();
      categories.add(
          assessed(
              "OUTBOX_EVENTS",
              outbox.observedAt(),
              Map.of(
                  "records", convert(facts.events()),
                  "pendingOrClaimed", convert(facts.pendingOrClaimed()),
                  "reconciliationRequired", convert(facts.reconciliationRequired())),
              Map.of(),
              capReasons(
                  facts.events(), facts.pendingOrClaimed(), facts.reconciliationRequired())));
      if (facts.pendingOrClaimed().value() > 0) blockers.add("OUTBOX_EFFECTS_PENDING");
      if (facts.reconciliationRequired().value() > 0)
        blockers.add("OUTBOX_RECONCILIATION_REQUIRED");
    } else {
      categories.add(
          unavailable("OUTBOX_EVENTS", outbox.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("OUTBOX_SOURCE_UNAVAILABLE");
    }

    var exports = inspect(() -> exportFacts.query(actor.tenantId(), referenceAt));
    if (exports.available()) {
      var facts = exports.value();
      categories.add(
          assessed(
              "EXPORT_JOBS",
              exports.observedAt(),
              Map.of(
                  "records", convert(facts.jobs()),
                  "activeJobs", convert(facts.activeJobs()),
                  "availableArtifacts", convert(facts.availableArtifacts()),
                  "cleanupPending", convert(facts.cleanupPending())),
              Map.of("objectStorageVerified", "NOT_EVALUATED"),
              capReasons(
                  facts.jobs(),
                  facts.activeJobs(),
                  facts.availableArtifacts(),
                  facts.cleanupPending())));
      if (facts.activeJobs().value() > 0) blockers.add("EXPORT_JOBS_ACTIVE");
      if (facts.availableArtifacts().value() > 0 || facts.cleanupPending().value() > 0) {
        blockers.add("EXPORT_OBJECTS_NOT_VERIFIED");
      }
    } else {
      categories.add(
          unavailable("EXPORT_JOBS", exports.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("EXPORT_SOURCE_UNAVAILABLE");
    }

    var billing = inspect(() -> billingFacts.query(actor.tenantId()));
    if (billing.available()) {
      var facts = billing.value();
      categories.add(
          assessed(
              "BILLING_OPERATIONS",
              billing.observedAt(),
              Map.of(
                  "subscriptions", convert(facts.subscriptions()),
                  "openCheckouts", convert(facts.openCheckouts()),
                  "checkoutOutcomeUnknown", convert(facts.checkoutOutcomeUnknown()),
                  "dunningPending", convert(facts.dunningPending()),
                  "dunningOutcomeUnknown", convert(facts.dunningOutcomeUnknown())),
              Map.of(
                  "subscriptionStatus",
                  facts.subscriptionStatus() == null ? "ABSENT" : facts.subscriptionStatus()),
              capReasons(
                  facts.subscriptions(),
                  facts.openCheckouts(),
                  facts.checkoutOutcomeUnknown(),
                  facts.dunningPending(),
                  facts.dunningOutcomeUnknown())));
      if (facts.openCheckouts().value() > 0 || facts.dunningPending().value() > 0) {
        blockers.add("BILLING_OPERATIONS_PENDING");
      }
      if (facts.checkoutOutcomeUnknown().value() > 0 || facts.dunningOutcomeUnknown().value() > 0) {
        blockers.add("BILLING_OUTCOME_UNKNOWN");
      }
    } else {
      categories.add(
          unavailable("BILLING_OPERATIONS", billing.observedAt(), List.of("SOURCE_UNAVAILABLE")));
      blockers.add("BILLING_SOURCE_UNAVAILABLE");
    }

    for (String category : NOT_EVALUATED_CATEGORIES) {
      categories.add(
          new RetentionAssessment.Category(
              category,
              "NOT_EVALUATED",
              "NOT_QUERIED",
              null,
              Map.of(),
              Map.of(),
              List.of("CATEGORY_OUTSIDE_INITIAL_SCOPE")));
    }
    blockers.addAll(
        List.of(
            "RETENTION_START_MARKER_UNRESOLVED",
            "CATEGORY_POLICY_NOT_CONFIGURED",
            "LEGAL_BASE_NOT_CONFIGURED",
            "HOLD_AUTHORITY_UNDEFINED",
            "HOLD_INVENTORY_NOT_EVALUATED",
            "ASSESSMENT_ONLY_NO_DISCARD_AUTHORIZATION"));
    requireCurrent(actor);
    return new RetentionAssessment(
        referenceAt,
        startedAt,
        clock.instant(),
        COUNT_LIMIT,
        SOURCE_QUERY_TIMEOUT_SECONDS,
        "NON_ATOMIC_READ_COMMITTED",
        true,
        tenant.available() && "SUSPENDED".equals(tenant.value().lifecycleStatus())
            ? tenant.value().lifecycleStatus()
            : tenant.available() ? tenant.value().lifecycleStatus() : "UNKNOWN",
        new RetentionAssessment.PolicyStatus(
            "REQUIREMENT_DEFINED",
            12,
            "UNRESOLVED",
            "NOT_CONFIGURED",
            "NOT_CONFIGURED",
            "UNDEFINED",
            "NOT_EVALUATED"),
        List.copyOf(categories),
        List.copyOf(blockers.stream().distinct().toList()));
  }

  private void requireCurrent(AuthenticatedAdmin actor) {
    if (actor == null || !accessPolicy.isCurrent(actor)) {
      throw new RetentionAuthorizationDeniedException();
    }
  }

  private <T> SourceResult<T> inspect(java.util.function.Supplier<T> supplier) {
    try {
      return new SourceResult<>(supplier.get(), true, clock.instant());
    } catch (DataAccessException exception) {
      return new SourceResult<>(null, false, clock.instant());
    }
  }

  private static RetentionAssessment.Category assessed(
      String code,
      Instant observedAt,
      Map<String, RetentionAssessment.Count> counts,
      Map<String, String> facts,
      List<String> reasons) {
    return new RetentionAssessment.Category(
        code, "ASSESSED", "AVAILABLE", observedAt, counts, facts, reasons);
  }

  private static RetentionAssessment.Category unavailable(
      String code, Instant observedAt, List<String> reasons) {
    return new RetentionAssessment.Category(
        code, "SOURCE_UNAVAILABLE", "UNAVAILABLE", observedAt, Map.of(), Map.of(), reasons);
  }

  private static RetentionAssessment.Count exact(int value) {
    return new RetentionAssessment.Count(value, "EXACT", COUNT_LIMIT);
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.appointments.api.AppointmentRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.clients.api.ClientRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.notifications.api.NotificationRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.outbox.api.OutboxRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.exports.api.ExportRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(
      com.appagendamento.subscriptions.api.BillingRetentionFactsQuery.Count count) {
    return convert(count.value(), count.atLeastLimit());
  }

  private static RetentionAssessment.Count convert(int value, boolean atLeastLimit) {
    return new RetentionAssessment.Count(
        value, atLeastLimit ? "AT_LEAST_LIMIT" : "EXACT", COUNT_LIMIT);
  }

  private static List<String> capReasons(
      com.appagendamento.appointments.api.AppointmentRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(
      com.appagendamento.clients.api.ClientRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(
      com.appagendamento.notifications.api.NotificationRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(
      com.appagendamento.outbox.api.OutboxRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(
      com.appagendamento.exports.api.ExportRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(
      com.appagendamento.subscriptions.api.BillingRetentionFactsQuery.Count... counts) {
    return capReasons((Object[]) counts);
  }

  private static List<String> capReasons(Object... counts) {
    for (Object item : counts) {
      boolean capped =
          switch (item) {
            case com.appagendamento.appointments.api.AppointmentRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            case com.appagendamento.clients.api.ClientRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            case com.appagendamento.notifications.api.NotificationRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            case com.appagendamento.outbox.api.OutboxRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            case com.appagendamento.exports.api.ExportRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            case com.appagendamento.subscriptions.api.BillingRetentionFactsQuery.Count count ->
                count.atLeastLimit();
            default -> false;
          };
      if (capped) return List.of("COUNT_LIMIT_REACHED");
    }
    return List.of();
  }

  private static final List<String> NOT_EVALUATED_CATEGORIES =
      List.of(
          "TENANT_AND_BUSINESS_CONFIGURATION",
          "ADMIN_IDENTITIES_AND_MEMBERSHIPS",
          "BUSINESS_SNAPSHOTS",
          "CHALLENGES_AND_SESSIONS",
          "IDEMPOTENCY_AND_RECEIPTS",
          "NOTIFICATION_EVIDENCE_AND_INBOXES",
          "PAYMENT_EVIDENCE",
          "EXPORT_OBJECT_STORAGE",
          "AUDIT_EVENTS",
          "TRIAL_LEDGER",
          "BACKUPS_AND_RESTORES",
          "LEGAL_HOLDS");

  private record SourceResult<T>(T value, boolean available, Instant observedAt) {}
}
