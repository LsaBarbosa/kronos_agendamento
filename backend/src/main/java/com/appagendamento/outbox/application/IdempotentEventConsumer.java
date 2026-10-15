package com.appagendamento.outbox.application;

import java.util.UUID;

public interface IdempotentEventConsumer {

  boolean process(String consumerName, UUID eventId, Runnable localEffect);
}
