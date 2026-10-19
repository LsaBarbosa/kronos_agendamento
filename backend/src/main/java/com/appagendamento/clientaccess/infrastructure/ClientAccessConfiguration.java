package com.appagendamento.clientaccess.infrastructure;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ClientAccessConfiguration {

  @Bean
  @ConditionalOnMissingBean(Clock.class)
  Clock clientAccessClock() {
    return Clock.systemUTC();
  }
}
