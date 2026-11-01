package com.appagendamento.exports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.exceptions.misusing.WrongTypeOfReturnValue;
import org.mockito.stubbing.OngoingStubbing;

class MockitoClockStubbingRaceTest {

  @Test
  void concurrentClockReadDuringWhenExpressionCanMisassociateTheStubbing() throws Exception {
    CountDownLatch stubbingCaptured = new CountDownLatch(1);
    CountDownLatch allowThenReturn = new CountDownLatch(1);
    Clock clock = mock(Clock.class);
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<?> stubbing =
          executor.submit(
              () -> {
                OngoingStubbing<ZoneId> pending = when(clock.getZone());
                stubbingCaptured.countDown();
                try {
                  if (!allowThenReturn.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to finish getZone stubbing");
                  }
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(
                      "Interrupted while controlling the race", interrupted);
                }
                pending.thenReturn(ZoneOffset.UTC);
              });
      if (!stubbingCaptured.await(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("getZone stubbing was not captured");
      }
      clock.instant();
      allowThenReturn.countDown();
      try {
        stubbing.get(5, TimeUnit.SECONDS);
      } catch (java.util.concurrent.ExecutionException failure) {
        if (failure.getCause() instanceof WrongTypeOfReturnValue) {
          assertThat(failure.getCause())
              .hasMessageContaining("ZoneOffset cannot be returned by instant()");
          return;
        }
        throw failure;
      }
      throw new AssertionError("Concurrent instant() did not interfere with getZone() stubbing");
    } finally {
      allowThenReturn.countDown();
      executor.shutdownNow();
    }
  }
}
