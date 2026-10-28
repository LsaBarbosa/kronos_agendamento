package com.appagendamento.platform.infrastructure;

import com.appagendamento.platform.api.DependencyMetrics;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class MicrometerDependencyMetrics implements DependencyMetrics {

  private final MeterRegistry meters;
  private final Map<Dependency, AtomicLong> availability = new EnumMap<>(Dependency.class);

  public MicrometerDependencyMetrics(MeterRegistry meters) {
    this.meters = meters;
  }

  @Override
  public void configured(Dependency dependency, boolean configured) {
    registerConfiguration(dependency, "configured", configured ? 1 : 0);
    registerConfiguration(dependency, "missing_configuration", configured ? 0 : 1);
  }

  @Override
  public void observed(Dependency dependency, boolean available) {
    AtomicLong state = availability.computeIfAbsent(dependency, this::registerAvailability);
    state.set(available ? 1 : 0);
  }

  private void registerConfiguration(Dependency dependency, String state, long value) {
    AtomicLong gauge = new AtomicLong(value);
    Gauge.builder("agd.dependency.configuration", gauge, AtomicLong::doubleValue)
        .tag("dependency", dependency.tag())
        .tag("state", state)
        .strongReference(true)
        .register(meters);
  }

  private AtomicLong registerAvailability(Dependency dependency) {
    AtomicLong value = new AtomicLong();
    Gauge.builder("agd.dependency.available", value, AtomicLong::doubleValue)
        .tag("dependency", dependency.tag())
        .strongReference(true)
        .register(meters);
    return value;
  }
}
