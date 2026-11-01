# ExportLifecycleIT Clock fix

## Scope and base

- Branch: `fix/export-lifecycle-it`
- Base: main merge commit `4edf01373871e9b7e338f3e42f0c2c50e0e2e56b`
- Scope: the integration-test clock setup for exports, a controlled Mockito race reproduction, and this handoff.
- No production source, business rules, TTLs, migrations, constraints, `AuditTrailIT`, or PR #30 files are changed.

## Historical failure and evidence boundary

App CI run [41261239083](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/41261239083), job [124714771146](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/41261239083/job/124714771146), reported `ExportLifecycleIT.setUp:134` with `WrongTypeOfReturnValue: ZoneOffset cannot be returned by instant()`. The captured log also showed NotificationWorker/scheduler activity. The historical log is preserved at its GitHub URL; this change does not claim to reproduce the exact historical scheduling interleaving.

In this test context `app.notifications.consumer.enabled` is omitted and the polling configuration is enabled when the property is missing. Its scheduled callbacks invoke `WorkerMetrics.execute`, whose `MicrometerWorkerMetrics.record` calls the injected `Clock`. The notifications callback also reaches `NotificationWorker.refreshReminderMetrics` and `MetricsSourceRecorder.success`, which call the same injected clock. This path can read the test clock concurrently with `@BeforeEach` reset/stubbing. The supplied historical log makes that overlap plausible, but does not establish that it occurred in the exact interval which produced the CI error.

## Controlled reproduction

`MockitoClockStubbingRaceTest` uses two threads and `CountDownLatch` barriers, with no sleeps or probabilistic retry:

1. Thread A captures `when(clock.getZone())` and pauses before `thenReturn(ZoneOffset.UTC)`.
2. Thread B invokes `clock.instant()` on the same Mockito mock.
3. Thread A completes the pending stubbing.

The test deterministically receives the same Mockito `WrongTypeOfReturnValue` diagnostic (`ZoneOffset cannot be returned by instant()`). This demonstrates that a concurrent invocation can misassociate stubbing on a shared mock; it is a controlled mechanism reproduction, not proof of the historical interleaving.

## Correction

`ExportLifecycleIT` now injects a test-only `MutableTestClock`, not a Mockito mock. Its zone is a fixed UTC value and its instant is stored in an `AtomicReference<Instant>`. Each test advances time with one atomic `setInstant`; worker reads cannot observe a partially configured clock or a null instant. Mockito reset/stubbing remains only for the artifact store and audit/outbox spies. The scheduled notification worker remains enabled, so the test does not hide the concurrent consumer.

An integration-test regression advances and reads the clock concurrently using a start latch, asserting every observed instant is non-null and the UTC zone remains stable.

## Validation performed

Environment: JDK 21.0.11; Testcontainers 2.0.5; Docker 28.5.2; ephemeral PostgreSQL 16.13 container.

- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -Dtest=MockitoClockStubbingRaceTest test` — passed; one deterministic reproduction test.
- `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -Dit.test=ExportLifecycleIT verify` — passed; 95 unit tests and all 27 `ExportLifecycleIT` PostgreSQL/Testcontainers tests, including expiry, concurrent active-job uniqueness, retry/fencing and rollback scenarios.
- Flyway applied and validated all 45 migrations in the disposable PostgreSQL database.
- `spotless:apply` was used only on the two changed Java test files; final `spotless:check` and `git diff --check` are recorded in the delivery.

The first in-sandbox Testcontainers attempt could not access `/var/run/docker.sock` (`Operation not permitted`). The same focal command was then executed with authorized Docker access and passed. No shared Compose project was operated.

## Limitations

The original CI log cannot reveal whether a worker call overlapped the exact Mockito stubbing/reset instructions. The deterministic test proves the mechanism; the mutable clock removes shared-mock reconfiguration and is safe for concurrent reads/atomic test-time changes. A passing local run is not proof of CI stability or a retroactive explanation of the historical event. The PR check remains necessary.
