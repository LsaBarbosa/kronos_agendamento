package com.appagendamento;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/** Ponto de entrada. A autenticação administrativa será implementada em AGD-003. */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class AgendamentoApplication {

  public static void main(String[] args) {
    SpringApplication.run(AgendamentoApplication.class, args);
  }
}
