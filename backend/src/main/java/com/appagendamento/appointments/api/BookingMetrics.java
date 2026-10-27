package com.appagendamento.appointments.api;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class BookingMetrics {

  private final MeterRegistry meters;

  public BookingMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  public void attempt(String channel) {
    meters.counter("agd.appointments.booking.attempts", "channel", channel).increment();
  }

  public void created(String channel, boolean replayed, String effect) {
    String result = replayed ? "replay" : "created";
    meters
        .counter("agd.appointments.booking.commands", "channel", channel, "result", result)
        .increment();
    if (!replayed) {
      meters
          .counter(
              "agd.appointments.booking.effects.confirmed", "channel", channel, "effect", effect)
          .increment();
    }
  }

  public void conflict(String channel) {
    meters
        .counter("agd.appointments.booking.commands", "channel", channel, "result", "conflict")
        .increment();
  }

  public void rejected(String channel) {
    meters
        .counter("agd.appointments.booking.commands", "channel", channel, "result", "rejected")
        .increment();
  }
}
