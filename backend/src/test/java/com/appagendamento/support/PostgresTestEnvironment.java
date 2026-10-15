package com.appagendamento.support;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
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
      String flywayUrl = System.getenv("TEST_FLYWAY_DATABASE_URL");
      registry.add("spring.flyway.url", () -> flywayUrl == null ? externalUrl : flywayUrl);
      registry.add("spring.flyway.user", () -> required("TEST_FLYWAY_DATABASE_USERNAME"));
      registry.add("spring.flyway.password", () -> required("TEST_FLYWAY_DATABASE_PASSWORD"));
    } else {
      if (container == null) {
        container =
            new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("agendamento_test")
                .withUsername("agendamento_test")
                .withPassword("test-only-password");
        container.start();
        createRuntimeRole();
      }
      registry.add("spring.datasource.url", container::getJdbcUrl);
      registry.add("spring.datasource.username", () -> "agendamento_app");
      registry.add("spring.datasource.password", () -> "test-only-app-password");
      registry.add("spring.flyway.url", container::getJdbcUrl);
      registry.add("spring.flyway.user", container::getUsername);
      registry.add("spring.flyway.password", container::getPassword);
    }
  }

  private static void createRuntimeRole() {
    try (var connection =
            DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), container.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE ROLE agendamento_app LOGIN PASSWORD 'test-only-app-password'");
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Não foi possível preparar o papel runtime de teste", exception);
    }
  }

  public static synchronized DataSource migrationDataSource() {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    if (container != null) {
      dataSource.setURL(container.getJdbcUrl());
      dataSource.setUser(container.getUsername());
      dataSource.setPassword(container.getPassword());
      return dataSource;
    }
    String url = System.getenv("TEST_FLYWAY_DATABASE_URL");
    dataSource.setURL(url == null || url.isBlank() ? required("TEST_DATABASE_URL") : url);
    dataSource.setUser(required("TEST_FLYWAY_DATABASE_USERNAME"));
    dataSource.setPassword(required("TEST_FLYWAY_DATABASE_PASSWORD"));
    return dataSource;
  }

  private static String required(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Defina " + name + " para o PostgreSQL de testes");
    }
    return value;
  }
}
