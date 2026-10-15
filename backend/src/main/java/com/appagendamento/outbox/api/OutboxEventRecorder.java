package com.appagendamento.outbox.api;

public interface OutboxEventRecorder {

  void record(OutboxEvent event);
}
