package com.appagendamento.appointments.infrastructure;

import com.appagendamento.appointments.domain.ClientAppointmentCancellationPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AppointmentConfiguration {

  @Bean
  ClientAppointmentCancellationPolicy clientAppointmentCancellationPolicy() {
    return new ClientAppointmentCancellationPolicy();
  }
}
