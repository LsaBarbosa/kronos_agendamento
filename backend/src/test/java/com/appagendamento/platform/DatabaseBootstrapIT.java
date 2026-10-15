package com.appagendamento.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.appagendamento.platform.infrastructure.web.CorrelationIdFilter;
import com.appagendamento.support.PostgresTestEnvironment;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class DatabaseBootstrapIT {

  @Autowired private JdbcTemplate jdbc;
  private JdbcTemplate migrationJdbc;
  @Autowired private Flyway flyway;
  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestEnvironment.configure(registry);
  }

  @BeforeEach
  void privilegedMigrationInspection() {
    migrationJdbc = new JdbcTemplate(PostgresTestEnvironment.migrationDataSource());
  }

  @Test
  void startsWithRealPostgresAndAppliesMigration() {
    assertThat(jdbc.queryForObject("select version()", String.class)).contains("PostgreSQL 16");
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from pg_extension where extname = 'btree_gist'", Integer.class))
        .isEqualTo(1);
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from agendamento.flyway_schema_history where version = '1' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from agendamento.flyway_schema_history where version = '2' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from agendamento.flyway_schema_history where version in ('3', '4') and success",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from agendamento.flyway_schema_history where version in ('5', '6', '7') and success",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject("select to_regclass('agendamento.spring_session')", String.class))
        .isEqualTo("agendamento.spring_session");
    assertThat(jdbc.queryForObject("select to_regclass('agendamento.tenant')", String.class))
        .isEqualTo("agendamento.tenant");
    assertThat(jdbc.queryForObject("select to_regclass('agendamento.service')", String.class))
        .isEqualTo("agendamento.service");
  }

  @Test
  void migrationHistoryRemainsValidOnRepeatedStartup() {
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
  }

  @Test
  void healthIsPublicButDoesNotExposeComponents() throws Exception {
    mvc.perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist())
        .andExpect(header().exists(CorrelationIdFilter.HEADER));
  }

  @Test
  void businessRoutesAreClosedUntilAuthenticationIsImplemented() throws Exception {
    String traceId = UUID.randomUUID().toString();
    mvc.perform(get("/api/v1/admin/appointments").header(CorrelationIdFilter.HEADER, traceId))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
        .andExpect(jsonPath("$.error.traceId").value(traceId))
        .andExpect(jsonPath("$.error.retryable").value(false))
        .andExpect(header().string(CorrelationIdFilter.HEADER, traceId));
  }

  @Test
  void otherActuatorEndpointsAreNotPublic() throws Exception {
    mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
  }
}
