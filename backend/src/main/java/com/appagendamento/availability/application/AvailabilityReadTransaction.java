package com.appagendamento.availability.application;

import java.util.function.Supplier;

public interface AvailabilityReadTransaction {

  <T> T execute(Supplier<T> query);
}
