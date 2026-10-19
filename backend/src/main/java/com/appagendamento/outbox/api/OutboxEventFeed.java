package com.appagendamento.outbox.api;

import java.util.List;

public interface OutboxEventFeed {

  List<OutboxEvent> unprocessedAppointmentEvents(String consumerName, int limit);
}
