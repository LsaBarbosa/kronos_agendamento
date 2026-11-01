package com.appagendamento.platform.api;

import java.util.function.IntSupplier;

/** Bounded worker identifiers and lifecycle telemetry; never represents provider delivery. */
public interface WorkerMetrics {

  int execute(Worker worker, IntSupplier work);

  enum Worker {
    OUTBOX("outbox"),
    REMINDERS("reminders"),
    NOTIFICATIONS("notifications"),
    SUBSCRIPTION_DUNNING("subscription_dunning"),
    TRIAL_EXPIRY("trial_expiry"),
    EXPORTS("exports");

    private final String tag;

    Worker(String tag) {
      this.tag = tag;
    }

    public String tag() {
      return tag;
    }
  }
}
