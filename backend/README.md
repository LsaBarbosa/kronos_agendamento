# Backend

Java 21, Spring Boot 4.1.1, Maven Wrapper 3.9.11, PostgreSQL 16, Flyway e Spring Security. A mudança de baseline está justificada na [ADR-0004](../docs/decisions/ADR-0004-baseline-spring-boot-4.md).

Consulte [o README da raiz](../README.md) para preparar variáveis, executar e testar. A entrada é `com.appagendamento.AgendamentoApplication`. O build gera um JAR executável.

Os módulos seguem `docs/spec/app-agendamento-04-arquitetura-implementacao.md`. Cada módulo contém `api`, `application`, `domain` e `infrastructure`. Pacotes ainda sem implementação possuem apenas documentação de responsabilidade.

Comandos executados a partir desta pasta:

```bash
./mvnw spotless:check
./mvnw test
./mvnw verify
./mvnw spring-boot:run
```

`./mvnw spotless:apply` formata fontes Java segundo Google Java Format; `spotless:check` é um gate separado do ciclo `verify`.

`verify` requer Docker ou `TEST_DATABASE_*` para um PostgreSQL 16 descartável. `spring-boot:run` requer banco disponível e `DB_PASSWORD`. O provedor de identidade administrativa está pendente; a configuração atual nega acesso às rotas de negócio.
