# Estado do projeto

**Atualização:** 15/10/2026.
**AGD-001:** em andamento; scan Trivy completo de dependências e homologação/staging continuam pendentes. Scan limitado ao Dockerfile não substitui o completo.
**AGD-002:** parcial; PR #1 integrado em `main` pelo merge commit `a708dcf358e551304018cde22a428a1275de4d13`. A prova de isolamento dos casos de uso está disponível; não há rotas de negócio para comprovar isolamento HTTP. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).
**AGD-003:** parcial/em andamento; PR #2 integrado em `main` pelo merge commit `81ce55d944c8c09a1a29ab1cb91ed03e5d97f4c5`. OIDC, sessão JDBC, associação server-side e expiração local de 30 minutos têm evidências locais e CI. Homologação HTTPS hospedada permanece pendente; papéis de suporte seguem fechados até decisão de RBAC/MFA. Ver [plano AGD-003](../plans/AGD-003-identidade-keycloak.md), [runbook HTTPS](../plans/AGD-003-homologacao-https.md) e [ADR-0006](../decisions/ADR-0006-keycloak-oidc-sessao-administrativa.md).
**AGD-004:** parcial, PR [#3](https://github.com/LsaBarbosa/kronos_agendamento/pull/3) aberto sobre `main`, head `23731b8036861683e0353e18a1b164fe0ce9a47b`, branch `agd-004-auditoria-correlacao`, estado `CLEAN`; revisão humana não consta. App CI/CodeQL e os jobs push/PR foram confirmados verdes em 15/10/2026. O browser E2E falhou uma vez no head anterior ao reautenticar; o diagnóstico seguro não determinou a causa, portanto a possível intermitência permanece. AGD-005 deriva deste head e depende da decisão de integração do PR #3. Ver [plano AGD-004](../plans/AGD-004-auditoria-correlacao.md) e [VALIDATION.md](VALIDATION.md).

**AGD-005:** implementação funcional na branch `agd-005-idempotencia-outbox`; PR [#4](https://github.com/LsaBarbosa/kronos_agendamento/pull/4) aberto com base `agd-004-auditoria-correlacao`, explicitamente dependente do PR #3. Código em `ee3bdfd15ddf4275351c4a75e17d03a2ce9e082a`; head publicado/documentado `dafda4730244ac9c378a85d3ab4035a41ce0bfbd`. V6, digest/canonicalização versionada, replay autorizado, rename+auditoria+outbox+resultado atômicos, worker com lease/retry/reconciliação e consumer deduplicado estão implementados. `spotless:check verify` passou com PostgreSQL 16.13/Testcontainers (25 testes unitários/arquiteturais + 29 integrações); frontend format/lint/test/build e npm audit passaram (2 testes, 0 vulnerabilidades high+). App CI e CodeQL push/PR passaram no head `dafda47`, incluindo browser E2E OIDC. Sem destinos externos reais; retenção e homologação seguem pendentes. Ver [plano AGD-005](../plans/AGD-005-idempotencia-outbox.md) e [VALIDATION.md](VALIDATION.md).

## Base disponível

- Java 21/Spring Boot 4.1.1, React/TypeScript/Vite, PostgreSQL 16, Maven/npm lockfiles e regras de arquitetura modular.
- Flyway V1–V6; V1–V5 preservadas. Tenant em V2, associação administrativa em V3, Spring Session JDBC em V4, trilha de auditoria em V5 e idempotência/outbox/receipts em V6.
- Keycloak OIDC, associação AdminUser–tenant verificada no servidor, sessão JDBC de 30 minutos, CSRF e UI de entrada/login/logout.
- `CorrelationIdFilter` propaga UUID validado/gerado à resposta e MDC. AGD-004 grava o mesmo ID em eventos de sucesso das mutações Tenant.
- AGD-004 inclui lista allowlist de detalhes, consulta interna filtrada pelo tenant autenticado e separação entre `agendamento_app`, `agendamento_runtime`, owner NOLOGIN da auditoria e credenciais Flyway.
- CI existente executa Spotless/Maven verify/Testcontainers, frontend, npm audit, Trivy e CodeQL.

## Evidências locais atuais

- No branch AGD-004, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:check verify` passou em PostgreSQL 16.13/Testcontainers: 23 testes unitários/arquiteturais e 15 integrações, incluindo commit/rollback, falha ao persistir auditoria, isolamento, allowlist e rejeição runtime de UPDATE/DELETE/TRUNCATE. Frontend format/lint/tests/build, `npm audit --audit-level=high`, sintaxe dos scripts, Compose config e `git diff --check` também passaram. Revisões independentes por agentes não equivalem a review/aprovação humana.
- As evidências locais estão associadas ao commit de implementação `d24d53b`; os quatro checks remotos do head `a6e3dc9` passaram. Links, falha anterior do browser E2E e ressalva de possível flake estão em VALIDATION.md.
- Para evidências históricas de AGD-001–003 e limitações, consultar [VALIDATION.md](VALIDATION.md) e [TRACEABILITY.md](TRACEABILITY.md).

## Pendências e retomada

- **AGD-001:** executar scan Trivy completo de filesystem/dependências quando Maven Central responder, além da homologação/staging aprovada.
- **AGD-002:** implementar operações de negócio e seus testes HTTP próprios/cross-tenant em histórias futuras.
- **AGD-003:** disponibilizar domínio/issuer/TLS/secrets e executar smoke HTTPS observado; não inferir homologação dos testes locais. Não habilitar suporte sem RBAC/MFA aprovados.
- **AGD-004:** review humana/decisão de merge do PR #3; fechar cobertura de falhas/logout/negações de segurança conforme decisão e implementação futura; integrar auditoria a operações de negócio futuras; decidir retenção/legal hold antes de excluir qualquer evento.
- **AGD-005:** PR #4 dependente do #3; aprovação humana ainda não registrada. Destinos WhatsApp/cobrança/broker e reconciliação real não estão integrados; consumo PostgreSQL é prova de infraestrutura. Aprovar retenção para idempotency records/outbox/receipts antes de expurgar. Homologação permanece pendente.

Após AGD-005, próximo incremento recomendado: AGD-010 — Catálogo de serviços. As demais integrações de produto permanecem fora desta fundação e não foram homologadas em produção.
