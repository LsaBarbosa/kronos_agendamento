package com.appagendamento.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;

/** Usa container por padrão ou PostgreSQL descartável explicitamente informado. */
public final class PostgresTestEnvironment {

  private static PostgreSQLContainer<?> container;

  private PostgresTestEnvironment() {}

  public static synchronized void configure(DynamicPropertyRegistry registry) {
    String externalUrl = System.getenv("TEST_DATABASE_URL");
    if (externalUrl != null && !externalUrl.isBlank()) {
      if (!externalUrl.startsWith("jdbc:postgresql://")) {
        throw new IllegalArgumentException("TEST_DATABASE_URL deve usar PostgreSQL real");
      }
      registry.add("spring.datasource.url", () -> externalUrl);
      registry.add("spring.datasource.username", () -> required("TEST_DATABASE_USERNAME"));
      registry.add("spring.datasource.password", () -> required("TEST_DATABASE_PASSWORD"));
    } else {
      if (container == null) {
        container =
            new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("agendamento_test")
                .withUsername("agendamento_test")
                .withPassword("test-only-password");
        container.start();
      }
      registry.add("spring.datasource.url", container::getJdbcUrl);
      registry.add("spring.datasource.username", container::getUsername);
      registry.add("spring.datasource.password", container::getPassword);
    }
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Defina " + name + " para o PostgreSQL de testes");
    }
    return value;
  }
}
