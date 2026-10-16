package com.appagendamento.availability.infrastructure;

import com.appagendamento.availability.application.AppointmentOccupancyQuery;
import com.appagendamento.availability.application.AvailabilityReadTransaction;
import com.appagendamento.availability.application.CalculateAvailabilityForDateRange;
import com.appagendamento.availability.application.ScheduleRepository;
import com.appagendamento.catalog.api.ServiceAvailabilityQuery;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AvailabilityCalculationConfiguration {

  @Bean
  Clock availabilityClock() {
    return Clock.systemUTC();
  }

  @Bean
  CalculateAvailabilityForDateRange calculateAvailabilityForDateRange(
      AvailabilityReadTransaction readTransaction,
      ScheduleRepository schedules,
      ServiceAvailabilityQuery services,
      AppointmentOccupancyQuery appointments,
      Clock availabilityClock) {
    return new CalculateAvailabilityForDateRange(
        readTransaction, schedules, services, appointments, availabilityClock);
  }
}
