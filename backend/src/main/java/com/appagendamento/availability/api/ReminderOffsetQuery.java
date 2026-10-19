package com.appagendamento.availability.api;

import java.util.OptionalInt;
import java.util.UUID;

public interface ReminderOffsetQuery {

  OptionalInt findReminderOffsetHours(UUID tenantId);
}
