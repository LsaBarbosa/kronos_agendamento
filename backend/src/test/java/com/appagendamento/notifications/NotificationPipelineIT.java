package com.appagendamento.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.appagendamento.appointments.api.AppointmentNotificationQuery;
import com.appagendamento.clients.api.ClientNotificationContactQuery;
import com.appagendamento.clients.application.ClientRepository;
import com.appagendamento.clients.domain.ClientName;
import com.appagendamento.clients.domain.E164Phone;
import com.appagendamento.identityaccess.api.AuthenticatedAdmin;
import com.appagendamento.notifications.api.ProviderWebhookVerifier;
import com.appagendamento.notifications.application.GetNotificationOperations;
import com.appagendamento.notifications.application.NotificationEventConsumer;
import com.appagendamento.notifications.application.NotificationReminderEventConsumer;
import com.appagendamento.notifications.application.NotificationRepository;
import com.appagendamento.notifications.application.NotificationTransport;
import com.appagendamento.notifications.application.NotificationWebhookAdmission;
import com.appagendamento.notifications.application.NotificationWorker;
import com.appagendamento.outbox.api.OutboxEvent;
import com.appagendamento.outbox.api.OutboxEventRecorder;
import com.appagendamento.support.PostgresTestEnvironment;
import com.appagendamento.tenant.application.CreateTenant;
import com.appagendamento.tenant.domain.Tenant;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class NotificationPipelineIT {

  private static final String ISSUER = "https://identity.test/realms/agendamento";
  private JdbcTemplate migrationJdbc;
  @Autowired private JdbcTemplate runtimeJdbc;
  @Autowired private CreateTenant createTenant;
  @Autowired private ClientRepository clients;
  @Autowired private OutboxEventRecorder outbox;
  @Autowired private NotificationEventConsumer eventConsumer;
  @Autowired private NotificationReminderEventConsumer reminderEventConsumer;
  @Autowired private NotificationRepository notifications;
  @Autowired private AppointmentNotificationQuery appointments;
  @Autowired private ClientNotificationContactQuery contacts;
  @Autowired private org.springframework.context.ApplicationContext context;
  @Autowired private PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
    registry.add("app.notifications.consumer.enabled", () -> false);
  }

  @BeforeEach
  void privilegedFixtures() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
    eventConsumer.processBatch();
  }

  @AfterEach
  void clearNotificationFixtures() {
    eventConsumer.processBatch();
    reminderEventConsumer.processBatch();
    migrationJdbc.execute(
        "TRUNCATE agendamento.notification_evidence_inbox, "
            + "agendamento.notification_delivery_evidence, "
            + "agendamento.notification_attempt, agendamento.notification_intent");
  }

  @Test
  void consumesConfirmedRescheduledAndBothCancellationOriginsOnceAndSkipsStaleEvents() {
    Fixture fixture = fixture("consumer");
    record(fixture, 1, "AppointmentConfirmed");

    assertThat(eventConsumer.processBatch()).isEqualTo(1);
    assertThat(eventConsumer.processBatch()).isZero();
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(1);

    updateAppointment(fixture, 2, "CONFIRMED", Instant.parse("2027-01-10T11:00:00Z"));
    record(fixture, 2, "AppointmentRescheduled");
    assertThat(eventConsumer.processBatch()).isEqualTo(1);
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(2);

    updateAppointment(fixture, 3, "CANCELLED_BY_CLIENT", Instant.parse("2027-01-10T11:00:00Z"));
    record(fixture, 1, "AppointmentConfirmed");
    record(fixture, 3, "AppointmentCancelled");
    assertThat(eventConsumer.processBatch()).isEqualTo(2);
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(3);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.processed_event p "
                    + "JOIN agendamento.outbox_event e USING (event_id) "
                    + "WHERE p.consumer_name = ? AND e.tenant_id = ?",
                Integer.class,
                NotificationEventConsumer.CONSUMER_NAME,
                fixture.tenant().tenantId()))
        .isEqualTo(4);

    Fixture providerCancelled = fixture("provider-cancel");
    updateAppointment(
        providerCancelled, 2, "CANCELLED_BY_PROVIDER", Instant.parse("2027-01-12T11:00:00Z"));
    record(providerCancelled, 2, "AppointmentCancelled");
    eventConsumer.processBatch();
    assertThat(intentCount(providerCancelled.tenant().tenantId())).isEqualTo(1);
  }

  @Test
  void reminderEventsScheduleByConfiguredOffsetAndSupersedePriorVersions() {
    Fixture fixture = fixture("reminder-reschedule");
    insertReminderPolicy(fixture, 6);
    record(fixture, 1, "AppointmentConfirmed");
    assertThat(eventConsumer.processBatch()).isEqualTo(1);

    assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
    UUID originalReminder = reminderId(fixture.appointmentId(), 1);
    Instant startsAt = Instant.parse("2027-01-10T10:00:00Z");
    assertThat(reminderDueAt(originalReminder)).isEqualTo(startsAt.minus(Duration.ofHours(6)));
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(2);
    assertThat(reminderEventConsumer.processBatch()).isZero();

    migrationJdbc.update(
        "UPDATE agendamento.availability_policy SET reminder_offset_hours = 12, version = version + 1 WHERE tenant_id = ?",
        fixture.tenant().tenantId());
    Instant rescheduledStarts = Instant.parse("2027-01-10T11:00:00Z");
    updateAppointment(fixture, 2, "CONFIRMED", rescheduledStarts);
    record(fixture, 2, "AppointmentRescheduled");

    assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
    UUID rescheduledReminder = reminderId(fixture.appointmentId(), 2);
    assertThat(reminderState(originalReminder)).isEqualTo("OBSOLETE");
    assertThat(reminderDueAt(rescheduledReminder))
        .isEqualTo(rescheduledStarts.minus(Duration.ofHours(12)));
    assertThat(reminderCount(fixture.appointmentId())).isEqualTo(2);
  }

  @Test
  void delayedOutOfOrderConfirmationCannotScheduleHistoricalVersion() {
    Fixture fixture = fixture("reminder-out-of-order");
    insertReminderPolicy(fixture, 6);
    updateAppointment(fixture, 2, "CONFIRMED", Instant.parse("2027-01-10T11:00:00Z"));
    record(fixture, 1, "AppointmentConfirmed");
    record(fixture, 2, "AppointmentRescheduled");

    assertThat(reminderEventConsumer.processBatch()).isEqualTo(2);

    assertThat(reminderCount(fixture.appointmentId())).isEqualTo(1);
    UUID reminder = reminderId(fixture.appointmentId(), 2);
    assertThat(reminderState(reminder)).isEqualTo("PENDING");
    assertThat(reminderDueAt(reminder)).isEqualTo(Instant.parse("2027-01-10T05:00:00Z"));
    assertThat(reminderEventConsumer.processBatch()).isZero();
  }

  @Test
  void absentPolicyDoesNotCreateReminderAndTerminalAppointmentEventsObsoleteIt() {
    Fixture absentPolicy = fixture("reminder-policy-absent");
    record(absentPolicy, 1, "AppointmentConfirmed");
    assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
    assertThat(reminderCount(absentPolicy.appointmentId())).isZero();

    for (String terminalEvent :
        List.of("AppointmentCancelled", "AppointmentCompleted", "ClientNoShowRecorded")) {
      Fixture fixture = fixture("reminder-terminal-" + terminalEvent.toLowerCase());
      insertReminderPolicy(fixture, 2);
      record(fixture, 1, "AppointmentConfirmed");
      assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
      UUID reminder = reminderId(fixture.appointmentId(), 1);
      String terminalStatus =
          switch (terminalEvent) {
            case "AppointmentCancelled" -> "CANCELLED_BY_PROVIDER";
            case "AppointmentCompleted" -> "COMPLETED";
            default -> "NO_SHOW";
          };
      updateAppointment(fixture, 2, terminalStatus, Instant.parse("2027-01-10T10:00:00Z"));
      record(fixture, 2, terminalEvent);

      assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
      assertThat(reminderState(reminder)).isEqualTo("OBSOLETE");
      assertThat(reminderEventConsumer.processBatch()).isZero();
    }
  }

  @Test
  void reminderIntentAndEventReceiptRollbackTogether() {
    Fixture fixture = fixture("reminder-rollback");
    insertReminderPolicy(fixture, 6);
    record(fixture, 1, "AppointmentConfirmed");
    migrationJdbc.execute(
        "CREATE OR REPLACE FUNCTION agendamento.reject_reminder_intent() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.message_type = 'APPOINTMENT_REMINDER' THEN "
            + "RAISE EXCEPTION 'injected reminder intent failure'; END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_reminder_intent BEFORE INSERT ON "
            + "agendamento.notification_intent FOR EACH ROW "
            + "EXECUTE FUNCTION agendamento.reject_reminder_intent()");
    try {
      assertThatThrownBy(reminderEventConsumer::processBatch).isInstanceOf(RuntimeException.class);
      assertThat(reminderCount(fixture.appointmentId())).isZero();
      assertThat(
              runtimeJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.processed_event "
                      + "WHERE consumer_name = ? AND event_id = ?",
                  Integer.class,
                  NotificationReminderEventConsumer.CONSUMER_NAME,
                  fixture.eventId()))
          .isZero();
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_reminder_intent ON agendamento.notification_intent");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_reminder_intent()");
    }

    assertThat(reminderEventConsumer.processBatch()).isEqualTo(1);
    assertThat(reminderCount(fixture.appointmentId())).isEqualTo(1);
  }

  @Test
  void overdueReminderMayProceedOnlyWhileAppointmentIsStillFutureUnderCurrentProposal() {
    Fixture fixture = fixture("reminder-overdue-before-start");
    insertReminderPolicy(fixture, 2);
    record(fixture, 1, "AppointmentConfirmed");
    reminderEventConsumer.processBatch();
    UUID reminder = reminderId(fixture.appointmentId(), 1);
    Instant startsAt = Instant.parse("2027-01-10T10:00:00Z");
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET due_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        reminder);
    AtomicInteger sends = new AtomicInteger();

    assertThat(
            worker(Clock.fixed(startsAt.minusSeconds(1), java.time.ZoneOffset.UTC))
                .processBatch(
                    message -> {
                      sends.incrementAndGet();
                      return NotificationTransport.TransportResult.accepted(
                          "test-adapter", "late-but-before-start");
                    }))
        .isEqualTo(1);

    assertThat(sends).hasValue(1);
    assertThat(reminderState(reminder)).isEqualTo("ACCEPTED");
  }

  @Test
  void reminderWorkerHonorsDueAtAndRejectsAppointmentStartBoundary() {
    Fixture fixture = fixture("reminder-worker");
    insertReminderPolicy(fixture, 2);
    record(fixture, 1, "AppointmentConfirmed");
    reminderEventConsumer.processBatch();
    UUID reminder = reminderId(fixture.appointmentId(), 1);
    Instant startsAt = Instant.parse("2027-01-10T10:00:00Z");

    assertThat(notifications.claimDue(1, Duration.ofMinutes(1))).isEmpty();
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET due_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        reminder);
    NotificationRepository.ReminderBacklog backlog = notifications.reminderBacklog();
    assertThat(backlog.pendingCount()).isEqualTo(1);
    assertThat(backlog.overdueCount()).isEqualTo(1);
    assertThat(backlog.oldestOverdueSeconds()).isGreaterThanOrEqualTo(0);
    assertThat(workerWithoutTransport().processBatch()).isZero();
    assertThat(reminderState(reminder)).isEqualTo("PENDING");
    assertThat(attemptCountFor(reminder)).isZero();
    NotificationWorker delayedWorker = worker(Clock.fixed(startsAt, java.time.ZoneOffset.UTC));
    AtomicInteger sends = new AtomicInteger();

    assertThat(
            delayedWorker.processBatch(
                message -> {
                  sends.incrementAndGet();
                  return NotificationTransport.TransportResult.accepted(
                      "test-adapter", "reminder-at-start");
                }))
        .isEqualTo(1);

    assertThat(sends).hasValue(0);
    assertThat(reminderState(reminder)).isEqualTo("OBSOLETE");
    assertThat(attemptCountFor(reminder)).isEqualTo(1);
  }

  @Test
  void concurrentReminderWorkersUseOnePersistedClaimAndOneLogicalEffect() throws Exception {
    Fixture fixture = fixture("reminder-concurrent-workers");
    insertReminderPolicy(fixture, 2);
    record(fixture, 1, "AppointmentConfirmed");
    reminderEventConsumer.processBatch();
    UUID reminder = reminderId(fixture.appointmentId(), 1);
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET due_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        reminder);

    CountDownLatch transportStarted = new CountDownLatch(1);
    CountDownLatch releaseTransport = new CountDownLatch(1);
    NotificationWorker firstWorker = worker(Clock.systemUTC());
    NotificationWorker secondWorker = worker(Clock.systemUTC());
    AtomicInteger sends = new AtomicInteger();
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(
              () ->
                  firstWorker.processBatch(
                      message -> {
                        sends.incrementAndGet();
                        transportStarted.countDown();
                        await(releaseTransport);
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "single-reminder-reference");
                      }));
      assertThat(transportStarted.await(10, TimeUnit.SECONDS)).isTrue();
      Future<Integer> second =
          workers.submit(
              () ->
                  secondWorker.processBatch(
                      message -> {
                        sends.incrementAndGet();
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "duplicate-must-not-send");
                      }));
      assertThat(second.get(10, TimeUnit.SECONDS)).isZero();
      releaseTransport.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseTransport.countDown();
    }

    assertThat(
            worker(Clock.systemUTC())
                .processBatch(
                    message -> {
                      sends.incrementAndGet();
                      return NotificationTransport.TransportResult.accepted(
                          "test-adapter", "restart-duplicate");
                    }))
        .isZero();
    assertThat(sends).hasValue(1);
    assertThat(reminderState(reminder)).isEqualTo("ACCEPTED");
    assertThat(attemptCountFor(reminder)).isEqualTo(1);
  }

  @Test
  void appointmentChangeBeforeFinalSendAuthorizationPreventsReminderTransport() throws Exception {
    Fixture fixture = fixture("reminder-change-before-send");
    insertReminderPolicy(fixture, 2);
    record(fixture, 1, "AppointmentConfirmed");
    reminderEventConsumer.processBatch();
    UUID reminder = reminderId(fixture.appointmentId(), 1);
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET due_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        reminder);

    CountDownLatch staleSnapshotRead = new CountDownLatch(1);
    CountDownLatch releaseSnapshot = new CountDownLatch(1);
    AtomicInteger reads = new AtomicInteger();
    AppointmentNotificationQuery delayedRead =
        (tenantId, appointmentId) -> {
          Optional<AppointmentNotificationQuery.NotificationAppointment> snapshot =
              appointments.find(tenantId, appointmentId);
          if (reads.incrementAndGet() == 2) {
            staleSnapshotRead.countDown();
            await(releaseSnapshot);
          }
          return snapshot;
        };
    NotificationWorker worker = worker(Clock.systemUTC(), delayedRead);
    AtomicInteger sends = new AtomicInteger();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Integer> processing =
          executor.submit(
              () ->
                  worker.processBatch(
                      message -> {
                        sends.incrementAndGet();
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "should-not-send");
                      }));
      assertThat(staleSnapshotRead.await(10, TimeUnit.SECONDS)).isTrue();
      updateAppointment(fixture, 2, "COMPLETED", Instant.parse("2027-01-10T10:00:00Z"));
      releaseSnapshot.countDown();
      assertThat(processing.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseSnapshot.countDown();
    }

    assertThat(sends).hasValue(0);
    assertThat(reminderState(reminder)).isEqualTo("OBSOLETE");
  }

  @Test
  void appointmentChangeAfterTransportStartsCannotRetractTheInFlightReminder() throws Exception {
    Fixture fixture = fixture("reminder-change-in-flight");
    insertReminderPolicy(fixture, 2);
    record(fixture, 1, "AppointmentConfirmed");
    reminderEventConsumer.processBatch();
    UUID reminder = reminderId(fixture.appointmentId(), 1);
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET due_at = clock_timestamp() - interval '1 second', next_attempt_at = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        reminder);
    CountDownLatch transportStarted = new CountDownLatch(1);
    CountDownLatch releaseTransport = new CountDownLatch(1);
    NotificationWorker worker = worker(Clock.systemUTC());
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Integer> processing =
          executor.submit(
              () ->
                  worker.processBatch(
                      message -> {
                        transportStarted.countDown();
                        await(releaseTransport);
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "already-in-flight");
                      }));
      assertThat(transportStarted.await(10, TimeUnit.SECONDS)).isTrue();
      updateAppointment(fixture, 2, "CANCELLED_BY_PROVIDER", Instant.parse("2027-01-10T10:00:00Z"));
      releaseTransport.countDown();
      assertThat(processing.get(10, TimeUnit.SECONDS)).isEqualTo(1);
    } finally {
      releaseTransport.countDown();
    }

    assertThat(reminderState(reminder)).isEqualTo("ACCEPTED");
    assertThat(attemptCountFor(reminder)).isEqualTo(1);
  }

  @Test
  void concurrentEventRedeliveryCreatesOneReceiptAndOneIntent() throws Exception {
    Fixture fixture = fixture("concurrent");
    record(fixture, 1, "AppointmentConfirmed");
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(
              () -> {
                start.await();
                return eventConsumer.processBatch();
              });
      Future<Integer> second =
          workers.submit(
              () -> {
                start.await();
                return eventConsumer.processBatch();
              });
      assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.processed_event WHERE event_id = ?",
                Integer.class,
                fixture.eventId()))
        .isEqualTo(1);
  }

  @Test
  void concurrentWorkersClaimAnIntentOnlyOnce() throws Exception {
    Fixture fixture = fixture("worker-claim");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    CyclicBarrier start = new CyclicBarrier(2);
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> first =
          workers.submit(
              () -> {
                start.await();
                return notifications.claimDue(1, Duration.ofSeconds(30)).size();
              });
      Future<Integer> second =
          workers.submit(
              () -> {
                start.await();
                return notifications.claimDue(1, Duration.ofSeconds(30)).size();
              });
      assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
    }
    assertThat(attemptCount(fixture.tenant().tenantId())).isEqualTo(1);
  }

  @Test
  void secondWorkerGetsFreshClaimWhileFirstTransportIsBlockedAndItsLeaseExpires() throws Exception {
    Fixture first = fixture("claim-first");
    Fixture second = fixture("claim-second");
    record(first, 1, "AppointmentConfirmed");
    record(second, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    runtimeJdbc.update(
        "UPDATE agendamento.notification_intent SET created_at = clock_timestamp() - interval '2 minutes' WHERE appointment_id = ?",
        first.appointmentId());
    runtimeJdbc.update(
        "UPDATE agendamento.notification_intent SET created_at = clock_timestamp() WHERE appointment_id = ?",
        second.appointmentId());

    CountDownLatch firstCallEntered = new CountDownLatch(1);
    CountDownLatch releaseFirstCall = new CountDownLatch(1);
    NotificationWorker worker = workerWithoutTransport();
    try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
      Future<Integer> firstBatch =
          workers.submit(
              () ->
                  worker.processBatch(
                      message -> {
                        assertThat(message.appointmentId()).isEqualTo(first.appointmentId());
                        firstCallEntered.countDown();
                        try {
                          assertThat(releaseFirstCall.await(15, TimeUnit.SECONDS)).isTrue();
                        } catch (InterruptedException interrupted) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(interrupted);
                        }
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "first-reference");
                      }));
      assertThat(firstCallEntered.await(10, TimeUnit.SECONDS)).isTrue();
      UUID firstNotification = notificationIdForAppointment(first.appointmentId());
      UUID secondNotification = notificationIdForAppointment(second.appointmentId());
      assertThat(attemptState(firstNotification)).isEqualTo("STARTED");
      assertThat(attemptCountFor(secondNotification)).isZero();
      runtimeJdbc.update(
          "UPDATE agendamento.notification_intent SET lease_until = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
          firstNotification);

      Future<Integer> concurrentScan =
          workers.submit(
              () ->
                  worker.processBatch(
                      message -> {
                        assertThat(message.appointmentId()).isEqualTo(second.appointmentId());
                        return NotificationTransport.TransportResult.accepted(
                            "test-adapter", "second-reference");
                      }));
      assertThat(concurrentScan.get(15, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(intentStateByNotificationId(secondNotification)).isEqualTo("ACCEPTED");
      assertThat(attemptState(firstNotification)).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(attemptCountFor(secondNotification)).isEqualTo(1);
      assertThat(attemptState(secondNotification)).isEqualTo("ACCEPTED");
      releaseFirstCall.countDown();
      assertThat(firstBatch.get(15, TimeUnit.SECONDS)).isEqualTo(1);
      assertThat(intentStateByNotificationId(firstNotification)).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(attemptCountFor(firstNotification)).isEqualTo(1);
    } finally {
      releaseFirstCall.countDown();
    }
  }

  @Test
  void expiredClaimDuringPreflightIsNotSent() {
    Fixture fixture = fixture("claim-expired-preflight");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    AppointmentNotificationQuery invalidateAfterSnapshot =
        (tenantId, appointmentId) -> {
          Optional<AppointmentNotificationQuery.NotificationAppointment> current =
              appointments.find(tenantId, appointmentId);
          runtimeJdbc.update(
              "UPDATE agendamento.notification_intent "
                  + "SET lease_until = clock_timestamp() - interval '1 second' "
                  + "WHERE appointment_id = ?",
              appointmentId);
          return current;
        };
    NotificationWorker guardedWorker =
        new NotificationWorker(
            notifications,
            invalidateAfterSnapshot,
            contacts,
            context.getBeanProvider(NotificationTransport.class),
            new SimpleMeterRegistry(),
            Clock.systemUTC(),
            20,
            60,
            8);
    AtomicInteger sends = new AtomicInteger();

    assertThat(
            guardedWorker.processBatch(
                message -> {
                  sends.incrementAndGet();
                  return NotificationTransport.TransportResult.accepted(
                      "test-adapter", "never-sent");
                }))
        .isEqualTo(1);

    UUID notificationId = notificationIdForAppointment(fixture.appointmentId());
    assertThat(sends).hasValue(0);
    assertThat(attemptState(notificationId)).isEqualTo("OUTCOME_UNKNOWN");
    assertThat(intentStateByNotificationId(notificationId)).isEqualTo("OUTCOME_UNKNOWN");
  }

  @Test
  void eventReceiptAndIntentRollbackTogetherWhenIntentInsertFails() {
    Fixture fixture = fixture("rollback");
    record(fixture, 1, "AppointmentConfirmed");
    migrationJdbc.execute(
        """
        CREATE OR REPLACE FUNCTION agendamento.reject_notification_insert() RETURNS trigger
        LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected notification insert failure'; END $$
        """);
    migrationJdbc.execute(
        "CREATE TRIGGER reject_notification_insert BEFORE INSERT ON agendamento.notification_intent "
            + "FOR EACH ROW EXECUTE FUNCTION agendamento.reject_notification_insert()");

    try {
      assertThatThrownBy(eventConsumer::processBatch).isInstanceOf(RuntimeException.class);
      assertThat(intentCount(fixture.tenant().tenantId())).isZero();
      assertThat(
              runtimeJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.processed_event WHERE event_id = ?",
                  Integer.class,
                  fixture.eventId()))
          .isZero();
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_notification_insert ON agendamento.notification_intent");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_notification_insert()");
    }

    assertThat(eventConsumer.processBatch()).isEqualTo(1);
    assertThat(intentCount(fixture.tenant().tenantId())).isEqualTo(1);
  }

  @Test
  void controlledTransportRetriesOnlyKnownNoEffectAndKeepsStableExternalIdentity() {
    Fixture fixture = fixture("retry");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWorker worker = workerWithoutTransport();
    assertThat(worker.processBatch()).isZero();
    assertThat(attemptCount(fixture.tenant().tenantId())).isZero();

    AtomicInteger sends = new AtomicInteger();
    java.util.ArrayList<String> externalKeys = new java.util.ArrayList<>();
    NotificationTransport controlled =
        message -> {
          sends.incrementAndGet();
          externalKeys.add(message.externalIdempotencyKey());
          assertThat(message.e164Recipient()).isEqualTo("+15551234567");
          assertThat(message.clientName()).isEqualTo("Cliente sintético");
          return sends.get() == 1
              ? NotificationTransport.TransportResult.failed(
                  com.appagendamento.notifications.domain.TransportFailure.RETRYABLE_NO_EFFECT)
              : NotificationTransport.TransportResult.accepted("test-adapter", "synthetic-ref");
        };

    assertThat(worker.processBatch(controlled)).isEqualTo(1);
    runtimeJdbc.update(
        "UPDATE agendamento.notification_intent SET next_attempt_at = clock_timestamp() WHERE tenant_id = ?",
        fixture.tenant().tenantId());
    assertThat(worker.processBatch(controlled)).isEqualTo(1);
    assertThat(sends).hasValue(2);
    assertThat(externalKeys).hasSize(2).allMatch(externalKeys.getFirst()::equals);
    assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("ACCEPTED");
    assertThat(attemptCount(fixture.tenant().tenantId())).isEqualTo(2);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_attempt WHERE state = 'RETRYABLE_NO_EFFECT'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void expiredLeaseWithStartedAttemptBecomesUnknownAndCannotBeReclaimedByOldWorker() {
    Fixture fixture = fixture("lease");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationRepository.NotificationClaim original =
        notifications.claimDue(1, Duration.ofSeconds(30)).getFirst();
    runtimeJdbc.update(
        "UPDATE agendamento.notification_intent SET lease_until = clock_timestamp() - interval '1 second' WHERE notification_id = ?",
        original.notificationId());

    assertThat(notifications.claimDue(1, Duration.ofSeconds(30))).isEmpty();
    assertThat(notifications.markAccepted(original, "test-adapter", "late-ref")).isFalse();
    assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("OUTCOME_UNKNOWN");
    assertThat(attemptCount(fixture.tenant().tenantId())).isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT state FROM agendamento.notification_attempt WHERE claim_token = ?",
                String.class,
                original.claimToken()))
        .isEqualTo("OUTCOME_UNKNOWN");
  }

  @Test
  void definitiveAndAmbiguousTransportFailuresAreNotRetriedAutomatically() {
    Fixture definitive = fixture("definitive");
    record(definitive, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWorker worker = workerWithoutTransport();
    AtomicInteger definitiveSends = new AtomicInteger();
    NotificationTransport terminal =
        message -> {
          definitiveSends.incrementAndGet();
          return NotificationTransport.TransportResult.failed(
              com.appagendamento.notifications.domain.TransportFailure.DEFINITIVE);
        };

    assertThat(worker.processBatch(terminal)).isEqualTo(1);
    assertThat(worker.processBatch(terminal)).isZero();
    assertThat(definitiveSends).hasValue(1);
    assertThat(intentState(definitive.tenant().tenantId())).isEqualTo("FAILED_FINAL");

    Fixture ambiguous = fixture("ambiguous");
    record(ambiguous, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    AtomicInteger ambiguousSends = new AtomicInteger();
    NotificationTransport unknown =
        message -> {
          ambiguousSends.incrementAndGet();
          return NotificationTransport.TransportResult.failed(
              com.appagendamento.notifications.domain.TransportFailure.AMBIGUOUS);
        };

    assertThat(worker.processBatch(unknown)).isEqualTo(1);
    assertThat(worker.processBatch(unknown)).isZero();
    assertThat(ambiguousSends).hasValue(1);
    assertThat(intentState(ambiguous.tenant().tenantId())).isEqualTo("OUTCOME_UNKNOWN");

    Fixture exhausted = fixture("attempt-limit");
    record(exhausted, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWorker oneAttemptWorker =
        new NotificationWorker(
            notifications,
            appointments,
            contacts,
            context.getBeanProvider(NotificationTransport.class),
            new SimpleMeterRegistry(),
            Clock.systemUTC(),
            20,
            60,
            1);
    AtomicInteger exhaustedSends = new AtomicInteger();
    NotificationTransport noEffect =
        message -> {
          exhaustedSends.incrementAndGet();
          return NotificationTransport.TransportResult.failed(
              com.appagendamento.notifications.domain.TransportFailure.RETRYABLE_NO_EFFECT);
        };
    assertThat(oneAttemptWorker.processBatch(noEffect)).isEqualTo(1);
    assertThat(oneAttemptWorker.processBatch(noEffect)).isZero();
    assertThat(exhaustedSends).hasValue(1);
    assertThat(intentState(exhausted.tenant().tenantId())).isEqualTo("FAILED_FINAL");
  }

  @Test
  void acceptedTransportWithPersistenceFailureRemainsUnknownAndIsNotRetried() {
    Fixture fixture = fixture("accepted-persist-failure");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    migrationJdbc.execute(
        "CREATE OR REPLACE FUNCTION agendamento.reject_notification_acceptance() "
            + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
            + "IF NEW.state = 'ACCEPTED' THEN RAISE EXCEPTION 'injected acceptance persistence failure'; "
            + "END IF; RETURN NEW; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_notification_acceptance BEFORE UPDATE ON "
            + "agendamento.notification_intent FOR EACH ROW "
            + "EXECUTE FUNCTION agendamento.reject_notification_acceptance()");
    AtomicInteger sends = new AtomicInteger();
    NotificationTransport accepted =
        message -> {
          sends.incrementAndGet();
          return NotificationTransport.TransportResult.accepted("test-adapter", "accepted-ref");
        };

    try {
      NotificationWorker worker = workerWithoutTransport();
      assertThat(worker.processBatch(accepted)).isEqualTo(1);
      assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("OUTCOME_UNKNOWN");
      assertThat(worker.processBatch(accepted)).isZero();
      assertThat(sends).hasValue(1);
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_notification_acceptance ON agendamento.notification_intent");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_notification_acceptance()");
    }
  }

  @Test
  void obsoleteAppointmentVersionIsNotSentAndAdminReadsAreTenantScopedAndMinimal() {
    Fixture first = fixture("tenant-a");
    Fixture second = fixture("tenant-b");
    record(first, 1, "AppointmentConfirmed");
    record(second, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    updateAppointment(first, 2, "CONFIRMED", Instant.parse("2027-01-10T11:00:00Z"));
    AtomicInteger sends = new AtomicInteger();
    NotificationWorker worker = workerWithoutTransport();
    assertThat(
            worker.processBatch(
                message -> {
                  sends.incrementAndGet();
                  return NotificationTransport.TransportResult.accepted("test-adapter", "ref-2");
                }))
        .isEqualTo(2);
    assertThat(sends).hasValue(1);
    assertThat(
            runtimeJdbc.queryForList(
                "SELECT state, appointment_version, attempt_count, last_error_code "
                    + "FROM agendamento.notification_intent WHERE tenant_id = ?",
                first.tenant().tenantId()))
        .as("The older intent should be marked obsolete")
        .anySatisfy(row -> assertThat(row.get("state")).isEqualTo("OBSOLETE"));
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_intent WHERE tenant_id = ? AND state = 'OBSOLETE'",
                Integer.class,
                first.tenant().tenantId()))
        .isEqualTo(1);

    GetNotificationOperations operations =
        new GetNotificationOperations((AuthenticatedAdmin admin) -> true, notifications);
    AuthenticatedAdmin admin = admin(first.tenant().tenantId());
    assertThat(operations.list(admin, 100)).hasSize(1);
    assertThat(operations.summary(admin)).extracting("state").contains("OBSOLETE");
    assertThat(operations.list(admin(second.tenant().tenantId()), 100)).hasSize(1);
    String visible =
        operations.detail(admin, operations.list(admin, 10).getFirst().notificationId()).toString();
    assertThat(visible).doesNotContain("+15551234567", "Cliente sintético", "providerReference");
  }

  @Test
  void deliveryEvidenceIsAuthenticatedBeforePersistenceDeduplicatedAndMonotonic() {
    Fixture fixture = fixture("webhook");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWorker worker = workerWithoutTransport();
    worker.processBatch(
        message -> NotificationTransport.TransportResult.accepted("test-provider", "msg-ref"));

    ProviderWebhookVerifier verifier =
        (provider, headers, rawBody) -> {
          if (!"synthetic-valid".equals(headers.get("x-test-auth"))
              || !"evidence".equals(new String(rawBody, StandardCharsets.UTF_8))) {
            return Optional.empty();
          }
          return Optional.of(
              new NotificationRepository.VerifiedDeliveryEvidence(
                  provider,
                  headers.get("x-test-event"),
                  "msg-ref",
                  Long.parseLong(headers.get("x-test-sequence")),
                  headers.get("x-test-state"),
                  Instant.parse(headers.get("x-test-time"))));
        };
    org.springframework.beans.factory.support.StaticListableBeanFactory factory =
        new org.springframework.beans.factory.support.StaticListableBeanFactory();
    factory.addBean("testWebhookVerifier", verifier);
    NotificationWebhookAdmission admission =
        new NotificationWebhookAdmission(
            factory.getBeanProvider(ProviderWebhookVerifier.class), notifications);
    Map<String, String> headers = evidenceHeaders("event-2", "2", "DELIVERED");
    byte[] body = "evidence".getBytes(StandardCharsets.UTF_8);

    assertThat(admission.admit("test-provider", Map.of(), body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.REJECTED);
    assertThat(admission.admit("test-provider", headers, body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.ADMITTED);
    assertThat(admission.admit("test-provider", headers, body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.DUPLICATE);
    assertThat(admission.admit("test-provider", evidenceHeaders("event-1", "1", "FAILED"), body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.ADMITTED);
    assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("DELIVERED");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE notification_id = ?",
                Integer.class,
                notificationId(fixture.tenant().tenantId())))
        .isEqualTo(2);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE provider_code = ? AND provider_event_id = ?",
                Integer.class,
                "test-provider",
                "event-1"))
        .isEqualTo(1);
  }

  @Test
  void authenticatedEvidenceDuringSendIsDurableAndReconciledAfterAcceptanceWithoutRedelivery() {
    Fixture fixture = fixture("early-evidence");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWebhookAdmission admission = webhookAdmission();
    byte[] body = "evidence".getBytes(StandardCharsets.UTF_8);
    NotificationWorker worker = workerWithoutTransport();

    assertThat(
            worker.processBatch(
                message -> {
                  assertThat(
                          admission.admit(
                              "test-provider",
                              evidenceHeaders("early-delivery", "7", "DELIVERED"),
                              body))
                      .isEqualTo(
                          NotificationWebhookAdmission.AdmissionResult
                              .ADMITTED_PENDING_CORRELATION);
                  assertThat(
                          admission.admit(
                              "test-provider",
                              evidenceHeaders("early-delivery", "7", "DELIVERED"),
                              body))
                      .isEqualTo(NotificationWebhookAdmission.AdmissionResult.DUPLICATE);
                  assertThat(
                          runtimeJdbc.queryForObject(
                              "SELECT count(*) FROM agendamento.notification_evidence_inbox WHERE provider_event_id = 'early-delivery' AND notification_id IS NULL",
                              Integer.class))
                      .isEqualTo(1);
                  return NotificationTransport.TransportResult.accepted("test-provider", "msg-ref");
                }))
        .isEqualTo(1);

    UUID notificationId = notificationId(fixture.tenant().tenantId());
    assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("DELIVERED");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_evidence_inbox WHERE provider_event_id = 'early-delivery' AND notification_id = ?",
                Integer.class,
                notificationId))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE provider_event_id = 'early-delivery' AND notification_id = ?",
                Integer.class,
                notificationId))
        .isEqualTo(1);
    migrationJdbc.update(
        "UPDATE agendamento.notification_evidence_inbox SET notification_id = NULL WHERE provider_code = ? AND provider_event_id = ?",
        "test-provider",
        "early-delivery");
    migrationJdbc.update(
        "DELETE FROM agendamento.notification_delivery_evidence WHERE provider_code = ? AND provider_event_id = ?",
        "test-provider",
        "early-delivery");
    migrationJdbc.update(
        "UPDATE agendamento.notification_intent SET state = 'ACCEPTED', delivered_at = NULL, last_evidence_sequence = -1 WHERE notification_id = ?",
        notificationId);
    assertThat(
            admission.admit(
                "test-provider", evidenceHeaders("early-delivery", "7", "DELIVERED"), body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.DUPLICATE);
    assertThat(intentState(fixture.tenant().tenantId())).isEqualTo("DELIVERED");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT notification_id FROM agendamento.notification_evidence_inbox WHERE provider_event_id = 'early-delivery'",
                UUID.class))
        .isEqualTo(notificationId);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE provider_event_id = 'early-delivery'",
                Integer.class))
        .isEqualTo(1);
  }

  @Test
  void overlappingEvidenceAdmissionAndAcceptanceTransactionsConvergeForEitherLockWinner()
      throws Exception {
    assertConcurrentEvidenceAndAcceptanceConverge("evidence-lock-first", false);
    assertConcurrentEvidenceAndAcceptanceConverge("acceptance-lock-first", true);
  }

  private void assertConcurrentEvidenceAndAcceptanceConverge(
      String fixtureName, boolean acceptanceWinsLock) throws Exception {
    Fixture fixture = fixture(fixtureName);
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationRepository.NotificationClaim claim =
        notifications.claimDue(1, Duration.ofSeconds(30)).getFirst();
    NotificationWebhookAdmission admission = webhookAdmission();
    byte[] body = "evidence".getBytes(StandardCharsets.UTF_8);
    String providerReference = "ref-" + fixtureName;
    Map<String, String> headers =
        evidenceHeaders("overlap-" + fixtureName, "11", "DELIVERED", providerReference);
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CountDownLatch firstOperationComplete = new CountDownLatch(1);
    CountDownLatch releaseFirstTransaction = new CountDownLatch(1);
    CountDownLatch secondTransactionStarted = new CountDownLatch(1);
    AtomicInteger secondBackendPid = new AtomicInteger();

    try (ExecutorService connections = Executors.newFixedThreadPool(2)) {
      Future<?> first =
          connections.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        if (acceptanceWinsLock) {
                          assertThat(
                                  notifications.markAccepted(
                                      claim, "test-provider", providerReference))
                              .isTrue();
                        } else {
                          assertThat(admission.admit("test-provider", headers, body))
                              .isEqualTo(
                                  NotificationWebhookAdmission.AdmissionResult
                                      .ADMITTED_PENDING_CORRELATION);
                        }
                        firstOperationComplete.countDown();
                        await(releaseFirstTransaction);
                      }));

      boolean firstCompleted = firstOperationComplete.await(10, TimeUnit.SECONDS);
      if (!firstCompleted) {
        first.get(1, TimeUnit.SECONDS);
      }
      assertThat(firstCompleted).isTrue();
      Future<?> second =
          connections.submit(
              () ->
                  transaction.executeWithoutResult(
                      status -> {
                        secondBackendPid.set(
                            runtimeJdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondTransactionStarted.countDown();
                        if (acceptanceWinsLock) {
                          assertThat(admission.admit("test-provider", headers, body))
                              .isEqualTo(NotificationWebhookAdmission.AdmissionResult.ADMITTED);
                        } else {
                          assertThat(
                                  notifications.markAccepted(
                                      claim, "test-provider", providerReference))
                              .isTrue();
                        }
                      }));

      assertThat(secondTransactionStarted.await(10, TimeUnit.SECONDS)).isTrue();
      awaitAdvisoryLockWait(secondBackendPid.get());
      releaseFirstTransaction.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    } finally {
      releaseFirstTransaction.countDown();
    }

    assertThat(intentStateByNotificationId(claim.notificationId())).isEqualTo("DELIVERED");
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT notification_id FROM agendamento.notification_evidence_inbox WHERE provider_event_id = ?",
                UUID.class,
                "overlap-" + fixtureName))
        .isEqualTo(claim.notificationId());
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE notification_id = ?",
                Integer.class,
                claim.notificationId()))
        .isEqualTo(1);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_attempt WHERE notification_id = ? AND state = 'ACCEPTED'",
                Integer.class,
                claim.notificationId()))
        .isEqualTo(1);
    assertThat(admission.admit("test-provider", headers, body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.DUPLICATE);
    assertThat(
            runtimeJdbc.queryForObject(
                "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE notification_id = ?",
                Integer.class,
                claim.notificationId()))
        .isEqualTo(1);
  }

  private void awaitAdvisoryLockWait(int backendPid) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      Boolean waiting =
          runtimeJdbc.queryForObject(
              "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE pid = ? AND locktype = 'advisory' AND NOT granted)",
              Boolean.class,
              backendPid);
      if (Boolean.TRUE.equals(waiting)) {
        return;
      }
      Thread.sleep(10);
    }
    assertThat(false).as("second PostgreSQL connection waits on provider/reference lock").isTrue();
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError(
          "Interrupted while synchronizing PostgreSQL transactions", exception);
    }
  }

  @Test
  void evidenceAdmissionAndAcceptanceReconciliationRollbackAtomically() {
    Fixture fixture = fixture("evidence-rollback");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    NotificationWebhookAdmission admission = webhookAdmission();
    byte[] body = "evidence".getBytes(StandardCharsets.UTF_8);
    migrationJdbc.execute(
        "CREATE OR REPLACE FUNCTION agendamento.reject_evidence_inbox() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected evidence admission failure'; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_evidence_inbox BEFORE INSERT ON "
            + "agendamento.notification_evidence_inbox FOR EACH ROW "
            + "EXECUTE FUNCTION agendamento.reject_evidence_inbox()");
    try {
      assertThatThrownBy(
              () ->
                  admission.admit(
                      "test-provider",
                      evidenceHeaders("rolled-back-admission", "1", "DELIVERED"),
                      body))
          .isInstanceOf(RuntimeException.class);
      assertThat(
              runtimeJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.notification_evidence_inbox WHERE provider_event_id = 'rolled-back-admission'",
                  Integer.class))
          .isZero();
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_evidence_inbox ON agendamento.notification_evidence_inbox");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_evidence_inbox()");
    }

    NotificationRepository.NotificationClaim claim =
        notifications.claimDue(1, Duration.ofSeconds(30)).getFirst();
    assertThat(
            admission.admit(
                "test-provider", evidenceHeaders("rolled-back-admission", "1", "DELIVERED"), body))
        .isEqualTo(NotificationWebhookAdmission.AdmissionResult.ADMITTED_PENDING_CORRELATION);
    migrationJdbc.execute(
        "CREATE OR REPLACE FUNCTION agendamento.reject_delivery_evidence() RETURNS trigger "
            + "LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'injected reconciliation failure'; END $$");
    migrationJdbc.execute(
        "CREATE TRIGGER reject_delivery_evidence BEFORE INSERT ON "
            + "agendamento.notification_delivery_evidence FOR EACH ROW "
            + "EXECUTE FUNCTION agendamento.reject_delivery_evidence()");
    try {
      assertThatThrownBy(() -> notifications.markAccepted(claim, "test-provider", "msg-ref"))
          .isInstanceOf(RuntimeException.class);
      assertThat(intentStateByNotificationId(claim.notificationId())).isEqualTo("CLAIMED");
      assertThat(
              runtimeJdbc.queryForObject(
                  "SELECT notification_id IS NULL FROM agendamento.notification_evidence_inbox WHERE provider_event_id = 'rolled-back-admission'",
                  Boolean.class))
          .isTrue();
      assertThat(
              runtimeJdbc.queryForObject(
                  "SELECT count(*) FROM agendamento.notification_delivery_evidence WHERE provider_event_id = 'rolled-back-admission'",
                  Integer.class))
          .isZero();
    } finally {
      migrationJdbc.execute(
          "DROP TRIGGER IF EXISTS reject_delivery_evidence ON agendamento.notification_delivery_evidence");
      migrationJdbc.execute("DROP FUNCTION IF EXISTS agendamento.reject_delivery_evidence()");
    }
    assertThat(notifications.markAccepted(claim, "test-provider", "msg-ref")).isTrue();
    assertThat(intentStateByNotificationId(claim.notificationId())).isEqualTo("DELIVERED");
  }

  @Test
  void notificationTablesDoNotCopySensitiveRecipientOrMessageFields() {
    Fixture fixture = fixture("privacy");
    record(fixture, 1, "AppointmentConfirmed");
    eventConsumer.processBatch();
    String intentTable =
        runtimeJdbc.queryForObject(
            "SELECT row_to_json(n)::text FROM agendamento.notification_intent n WHERE tenant_id = ?",
            String.class,
            fixture.tenant().tenantId());
    boolean minimalPayload =
        runtimeJdbc.queryForObject(
            "SELECT payload = ?::jsonb FROM agendamento.outbox_event WHERE event_id = ?",
            Boolean.class,
            "{\"appointmentId\":\"" + fixture.appointmentId() + "\",\"version\":1}",
            fixture.eventId());
    assertThat(intentTable)
        .doesNotContain("+15551234567", "Cliente sintético", "cookie", "secret-value");
    assertThat(minimalPayload).isTrue();
  }

  private NotificationWorker workerWithoutTransport() {
    ObjectProvider<NotificationTransport> provider =
        context.getBeanProvider(NotificationTransport.class);
    return new NotificationWorker(
        notifications,
        appointments,
        contacts,
        provider,
        new SimpleMeterRegistry(),
        Clock.systemUTC(),
        20,
        60,
        8);
  }

  private NotificationWebhookAdmission webhookAdmission() {
    ProviderWebhookVerifier verifier =
        (provider, headers, rawBody) -> {
          if (!"synthetic-valid".equals(headers.get("x-test-auth"))
              || !"evidence".equals(new String(rawBody, StandardCharsets.UTF_8))) {
            return Optional.empty();
          }
          return Optional.of(
              new NotificationRepository.VerifiedDeliveryEvidence(
                  provider,
                  headers.get("x-test-event"),
                  headers.getOrDefault("x-test-reference", "msg-ref"),
                  Long.parseLong(headers.get("x-test-sequence")),
                  headers.get("x-test-state"),
                  Instant.parse(headers.get("x-test-time"))));
        };
    org.springframework.beans.factory.support.StaticListableBeanFactory factory =
        new org.springframework.beans.factory.support.StaticListableBeanFactory();
    factory.addBean("testWebhookVerifier", verifier);
    return new NotificationWebhookAdmission(
        factory.getBeanProvider(ProviderWebhookVerifier.class), notifications);
  }

  private Fixture fixture(String slugPrefix) {
    Tenant tenant =
        createTenant.execute(
            "Notificações " + slugPrefix,
            slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8),
            "America/Sao_Paulo");
    UUID clientId =
        clients.findOrCreate(
            tenant.tenantId(),
            ClientName.of("Cliente sintético"),
            E164Phone.fromNormalized("+15551234567"));
    UUID serviceId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    Instant starts = Instant.parse("2027-01-10T10:00:00Z");
    runtimeJdbc.update(
        """
        INSERT INTO agendamento.service
          (service_id, tenant_id, name, normalized_name, duration_minutes, buffer_after_minutes, status, version)
        VALUES (?, ?, 'Serviço sintético', ?, 30, 15, 'ACTIVE', 0)
        """,
        serviceId,
        tenant.tenantId(),
        "svc-" + UUID.randomUUID());
    insertAppointment(
        tenant.tenantId(), clientId, serviceId, appointmentId, starts, 1, "CONFIRMED");
    return new Fixture(tenant, clientId, serviceId, appointmentId, eventId);
  }

  private void insertAppointment(
      UUID tenantId,
      UUID clientId,
      UUID serviceId,
      UUID appointmentId,
      Instant startsAt,
      long version,
      String status) {
    runtimeJdbc.update(
        """
        INSERT INTO agendamento.appointment
          (appointment_id, tenant_id, client_id, service_id, starts_at, service_ends_at,
           occupied_until, time_zone, client_name_snapshot, service_name_snapshot,
           service_duration_minutes, service_buffer_after_minutes, status, created_at, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, 'America/Sao_Paulo', 'Cliente sintético', 'Serviço sintético',
                30, 15, ?, clock_timestamp(), ?)
        """,
        appointmentId,
        tenantId,
        clientId,
        serviceId,
        Timestamp.from(startsAt),
        Timestamp.from(startsAt.plusSeconds(1800)),
        Timestamp.from(startsAt.plusSeconds(2700)),
        status,
        version);
  }

  private void updateAppointment(Fixture fixture, long version, String status, Instant startsAt) {
    runtimeJdbc.update(
        """
        UPDATE agendamento.appointment
        SET version = ?, status = ?, starts_at = ?, service_ends_at = ?, occupied_until = ?
        WHERE tenant_id = ? AND appointment_id = ?
        """,
        version,
        status,
        Timestamp.from(startsAt),
        Timestamp.from(startsAt.plusSeconds(1800)),
        Timestamp.from(startsAt.plusSeconds(2700)),
        fixture.tenant().tenantId(),
        fixture.appointmentId());
  }

  private void record(Fixture fixture, long version, String type) {
    UUID eventId = fixture.claimInitialEventId(type, version);
    outbox.record(
        new OutboxEvent(
            eventId,
            fixture.tenant().tenantId(),
            "APPOINTMENT",
            fixture.appointmentId(),
            version,
            type,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{\"appointmentId\":\"" + fixture.appointmentId() + "\",\"version\":" + version + "}"));
    fixture.lastEventId(eventId);
  }

  private void insertReminderPolicy(Fixture fixture, int offsetHours) {
    migrationJdbc.update(
        """
        INSERT INTO agendamento.availability_policy
          (tenant_id, slot_step_minutes, minimum_notice_hours, booking_horizon_days,
           cancellation_cutoff_hours, reminder_offset_hours, version)
        VALUES (?, 30, 2, 30, 2, ?, 1)
        """,
        fixture.tenant().tenantId(),
        offsetHours);
  }

  private UUID reminderId(UUID appointmentId, long version) {
    return runtimeJdbc.queryForObject(
        "SELECT notification_id FROM agendamento.notification_intent WHERE appointment_id = ? AND appointment_version = ? AND message_type = 'APPOINTMENT_REMINDER'",
        UUID.class,
        appointmentId,
        version);
  }

  private Instant reminderDueAt(UUID notificationId) {
    return runtimeJdbc
        .queryForObject(
            "SELECT due_at FROM agendamento.notification_intent WHERE notification_id = ?",
            Timestamp.class,
            notificationId)
        .toInstant();
  }

  private String reminderState(UUID notificationId) {
    return runtimeJdbc.queryForObject(
        "SELECT state FROM agendamento.notification_intent WHERE notification_id = ?",
        String.class,
        notificationId);
  }

  private int reminderCount(UUID appointmentId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.notification_intent WHERE appointment_id = ? AND message_type = 'APPOINTMENT_REMINDER'",
        Integer.class,
        appointmentId);
  }

  private NotificationWorker worker(Clock clock) {
    return worker(clock, appointments);
  }

  private NotificationWorker worker(Clock clock, AppointmentNotificationQuery appointmentQuery) {
    return new NotificationWorker(
        notifications,
        appointmentQuery,
        contacts,
        context.getBeanProvider(NotificationTransport.class),
        new SimpleMeterRegistry(),
        clock,
        20,
        60,
        8);
  }

  private int intentCount(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.notification_intent WHERE tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private int attemptCount(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.notification_attempt a JOIN agendamento.notification_intent n USING (notification_id) WHERE n.tenant_id = ?",
        Integer.class,
        tenantId);
  }

  private String intentState(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT state FROM agendamento.notification_intent WHERE tenant_id = ? ORDER BY created_at LIMIT 1",
        String.class,
        tenantId);
  }

  private UUID notificationId(UUID tenantId) {
    return runtimeJdbc.queryForObject(
        "SELECT notification_id FROM agendamento.notification_intent WHERE tenant_id = ?",
        UUID.class,
        tenantId);
  }

  private Map<String, String> evidenceHeaders(String eventId, String sequence, String state) {
    return evidenceHeaders(eventId, sequence, state, "msg-ref");
  }

  private Map<String, String> evidenceHeaders(
      String eventId, String sequence, String state, String providerReference) {
    return Map.of(
        "x-test-auth",
        "synthetic-valid",
        "x-test-event",
        eventId,
        "x-test-sequence",
        sequence,
        "x-test-reference",
        providerReference,
        "x-test-state",
        state,
        "x-test-time",
        "2027-01-10T10:30:00Z");
  }

  private UUID notificationIdForAppointment(UUID appointmentId) {
    return runtimeJdbc.queryForObject(
        "SELECT notification_id FROM agendamento.notification_intent WHERE appointment_id = ?",
        UUID.class,
        appointmentId);
  }

  private int attemptCountFor(UUID notificationId) {
    return runtimeJdbc.queryForObject(
        "SELECT count(*) FROM agendamento.notification_attempt WHERE notification_id = ?",
        Integer.class,
        notificationId);
  }

  private String attemptState(UUID notificationId) {
    return runtimeJdbc.queryForObject(
        "SELECT state FROM agendamento.notification_attempt WHERE notification_id = ?",
        String.class,
        notificationId);
  }

  private String intentStateByNotificationId(UUID notificationId) {
    return runtimeJdbc.queryForObject(
        "SELECT state FROM agendamento.notification_intent WHERE notification_id = ?",
        String.class,
        notificationId);
  }

  private AuthenticatedAdmin admin(UUID tenantId) {
    return new AuthenticatedAdmin() {
      @Override
      public UUID tenantId() {
        return tenantId;
      }

      @Override
      public UUID adminUserId() {
        return UUID.randomUUID();
      }

      @Override
      public String issuer() {
        return ISSUER;
      }

      @Override
      public String subject() {
        return "test-admin";
      }

      @Override
      public String role() {
        return "TENANT_ADMIN";
      }
    };
  }

  private static final class Fixture {
    private final Tenant tenant;
    private final UUID clientId;
    private final UUID serviceId;
    private final UUID appointmentId;
    private final UUID initialEventId;
    private UUID lastEventId;
    private boolean initialEventUsed;

    private Fixture(
        Tenant tenant, UUID clientId, UUID serviceId, UUID appointmentId, UUID initialEventId) {
      this.tenant = tenant;
      this.clientId = clientId;
      this.serviceId = serviceId;
      this.appointmentId = appointmentId;
      this.initialEventId = initialEventId;
      this.lastEventId = initialEventId;
    }

    Tenant tenant() {
      return tenant;
    }

    UUID clientId() {
      return clientId;
    }

    UUID serviceId() {
      return serviceId;
    }

    UUID appointmentId() {
      return appointmentId;
    }

    UUID eventId() {
      return lastEventId;
    }

    UUID claimInitialEventId(String type, long version) {
      if (!initialEventUsed && type.equals("AppointmentConfirmed") && version == 1) {
        initialEventUsed = true;
        return initialEventId;
      }
      return UUID.randomUUID();
    }

    void lastEventId(UUID value) {
      lastEventId = value;
    }
  }
}
