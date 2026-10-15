# Estado do projeto

**Atualização:** 15/10/2026.
**AGD-001:** em andamento; scan Trivy completo de dependências e homologação/staging continuam pendentes. Scan limitado ao Dockerfile não substitui o completo.
**AGD-002:** parcial; PR #1 integrado em `main` pelo merge commit `a708dcf358e551304018cde22a428a1275de4d13`. A prova de isolamento dos casos de uso está disponível; AGD-010 acrescentou rotas de negócio e testes HTTP de leitura/mutação próprio, alheio e inexistente, além de CSRF e associação revogada. Isso não comprova isolamento de operações futuras. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).
**AGD-003:** parcial/em andamento; PR #2 integrado em `main` pelo merge commit `81ce55d944c8c09a1a29ab1cb91ed03e5d97f4c5`. OIDC, sessão JDBC, associação server-side e expiração local de 30 minutos têm evidências locais e CI. Homologação HTTPS hospedada permanece pendente; papéis de suporte seguem fechados até decisão de RBAC/MFA. Ver [plano AGD-003](../plans/AGD-003-identidade-keycloak.md), [runbook HTTPS](../plans/AGD-003-homologacao-https.md) e [ADR-0006](../decisions/ADR-0006-keycloak-oidc-sessao-administrativa.md).
**AGD-004:** parcial; PR [#3](https://github.com/LsaBarbosa/kronos_agendamento/pull/3) integrado em `main` pelo merge commit `1138395acb4a5f50979461fce3894db60bd5b761`, após revisão independente sem bloqueadores reproduzíveis. App CI/CodeQL push/PR passaram no head original `23731b8`. A possível intermitência anterior do callback OAuth não tem causa comprovada. Cobertura futura de segurança, retenção e legal hold continuam pendentes. Ver [plano AGD-004](../plans/AGD-004-auditoria-correlacao.md) e [VALIDATION.md](VALIDATION.md).

**AGD-005:** integrada em `main` pelo merge #4 `1090b44a5652a13c1c858fdbbb32d870cd9f85d0`. V6, digest/canonicalização versionada, replay autorizado, rename+auditoria+outbox+resultado atômicos e consumidor deduplicado estão implementados. Sem destinos externos reais; retenção e homologação seguem pendentes. Ver [revisão de integração](PR-003-004-INTEGRATION-REVIEW.md), [plano AGD-005](../plans/AGD-005-idempotencia-outbox.md) e [VALIDATION.md](VALIDATION.md).

**AGD-010:** parcial; branch `agd-010-catalogo-servicos`, entrega pelo PR [#5](https://github.com/LsaBarbosa/kronos_agendamento/pull/5) sobre `main`; integração autorizada após checks do head final, acompanhada no próprio PR. Sem deploy. Catálogo administrativo CRUD/estado, isolamento por associação autenticada, V7, CAS, idempotência, auditoria atômica e `ServiceCreated` outbox estão implementados. A falha browser em `cd61e60` (CSRF indisponível após login) foi corrigida em `eaddf0f`; App CI e CodeQL push/PR passaram no código `d88f175` e novamente no head documental `5d64bf8` (runs `38801099775`, `38801112602`, `38801099641`, `38801112548`), com browser E2E real. Buffer zero/positivo múltiplo de 15 confirmado pelo usuário em 15/10/2026; bloqueio público mantido até gate explícito aprovado. Permanecem pendentes os critérios/implementação do gate público, preço/moeda não exposto e snapshot de reserva que depende de AGD-015. Ver [plano AGD-010](../plans/AGD-010-catalogo-servicos.md) e [VALIDATION.md](VALIDATION.md).
**AGD-011:** parcial; branch `agd-011-grade-semanal-politicas`, baseada na `origin/main` `c5dbec26cf88843704c6bf1575b69666ed235ab0`; commit funcional `4344d98a16161c17385a82bfd11c10e8be2de66b`; PR [#6](https://github.com/LsaBarbosa/kronos_agendamento/pull/6), aberto para `main`. App CI/CodeQL push e PR passaram no SHA funcional (runs `38821277299`, `38821277272`, `38821300931`, `38821300944`). Backend V8/API/OpenAPI/UI, CAS, idempotência e auditoria/outbox transacionais implementados. Validação local: PostgreSQL/Testcontainers (32 unitários/arquiteturais + 50 integrações), frontend (4 testes Vitest) e browser E2E Keycloak passaram. Duas revisões independentes em leitura tiveram achados corrigidos; não equivalem a aprovação humana. Alinhamento de horários e períodos sobre meia-noite aguardam decisão de produto; AGD-011 permanece parcial. Sem merge/deploy.

## Base disponível

- Java 21/Spring Boot 4.1.1, React/TypeScript/Vite, PostgreSQL 16, Maven/npm lockfiles e regras de arquitetura modular.
- Flyway V1–V8; V1–V7 preservadas. Tenant em V2, associação administrativa em V3, Spring Session JDBC em V4, trilha de auditoria em V5, idempotência/outbox/receipts em V6, catálogo em V7 e grade/políticas em V8.
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
- **AGD-002:** testes HTTP próprios/cross-tenant já existem para catálogo em AGD-010; ampliar às operações futuras, sem inferir cobertura global.
- **AGD-003:** disponibilizar domínio/issuer/TLS/secrets e executar smoke HTTPS observado; não inferir homologação dos testes locais. Não habilitar suporte sem RBAC/MFA aprovados.
- **AGD-004:** fechar cobertura de falhas/logout/negações de segurança conforme decisão e implementação futura; integrar auditoria a operações de negócio futuras; decidir retenção/legal hold antes de excluir qualquer evento.
- **AGD-005:** PR #4 integrado. Destinos WhatsApp/cobrança/broker e reconciliação real não estão integrados; consumo PostgreSQL é prova de infraestrutura. Aprovar retenção para idempotency records/outbox/receipts antes de expurgar. Homologação permanece pendente.
- **AGD-010:** Integração do PR #5 autorizada após revisão e checks; estado definitivo e SHA de merge no próprio PR. Sem deploy. No head documental `5d64bf8`, App CI push/PR (`38801099775`, `38801112602`) e CodeQL push/PR (`38801099641`, `38801112548`) passaram. Decisões pendentes: exposição pública/gate de prontidão, preço/moeda; buffer zero/positivo múltiplo de 15 foi confirmado em 15/10/2026. Snapshot preservado em reservas requer AGD-015; este incremento não alterou reservas.
- **AGD-011:** obter resposta do produto sobre alinhamento dos limites e cruzamento de meia-noite; acompanhar CI no head documental final e concluir critérios após decisão. Nenhuma rota pública/publicação é habilitada por ter grade. Pendências de AGD-001, AGD-003, retenção e AGD-015 permanecem.

Próximo incremento funcional recomendado após AGD-011: AGD-012 — Exceções e bloqueios. Disponibilidade/slots continuam na história própria; as demais integrações de produto permanecem fora desta fundação e sem homologação de produção.

Revisão de integração AGD-010 em 15/10/2026: corrigida a identidade idempotente de retries na UI; decisões de buffer/bloqueio público registradas em ADR-0009 e requisitos. Validação do novo head e integração serão registradas no PR #5. Sem deploy.
