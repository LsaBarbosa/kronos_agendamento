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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.observability.scrape-token=synthetic-scrape-secret")
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
                "select count(*) from agendamento.flyway_schema_history where version in ('5', '6', '7', '8') and success",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            migrationJdbc.queryForObject(
                "select count(*) from agendamento.flyway_schema_history where version in ('9', '10', '11', '12') and success",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject("select to_regclass('agendamento.spring_session')", String.class))
        .isEqualTo("agendamento.spring_session");
    assertThat(jdbc.queryForObject("select to_regclass('agendamento.tenant')", String.class))
        .isEqualTo("agendamento.tenant");
    assertThat(jdbc.queryForObject("select to_regclass('agendamento.service')", String.class))
        .isEqualTo("agendamento.service");
    assertThat(
            jdbc.queryForObject("select to_regclass('agendamento.weekly_schedule')", String.class))
        .isEqualTo("agendamento.weekly_schedule");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema = 'agendamento' and table_name = 'schedule_exception_period' and column_name in ('local_start_offset_seconds', 'local_end_offset_seconds')",
                Integer.class))
        .isEqualTo(2);
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

  @Test
  void prometheusScrapeIsProtectedAndUsesMicrometerPrometheusExposition() throws Exception {
    mvc.perform(get("/actuator/prometheus")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    mvc.perform(
        get(
            "/api/v1/public/tenant-sensitive-sentinel-9d8837c4/unknown"
                + "?private-query-sentinel=57872f9a"));

    String scrape =
        mvc.perform(
                get("/actuator/prometheus")
                    .header("Authorization", "Bearer synthetic-scrape-secret"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(scrape).contains("# HELP").contains("# TYPE");
    assertThat(scrape).contains("http_server_requests_seconds_count");
    assertThat(scrape).doesNotContain("synthetic-scrape-secret");
    assertThat(scrape)
        .doesNotContain("tenant-sensitive-sentinel-9d8837c4")
        .doesNotContain("private-query-sentinel=57872f9a");
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
  }
}
