package com.appagendamento.outbox.api;

public interface OutboxPublisher {

  DeliveryResult publish(OutboxClaim event);
}
