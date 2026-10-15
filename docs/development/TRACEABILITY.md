# Rastreabilidade inicial

| Referência/critério | Implementação ou preparação | Evidência/estado |
|---|---|---|
| AGD-001: backend modular | `backend/pom.xml`, aplicação e pacotes por módulo/camada | base presente; resultados em VALIDATION.md |
| AGD-001: frontend React/TypeScript | `frontend/src/`, `frontend/package.json`, `frontend/package-lock.json`, `frontend/README.md` | `npm ci`, 2 testes, TypeScript e build Vite passaram; sem fluxos de produto |
| AGD-001: PostgreSQL em integração | `DatabaseBootstrapIT`, `PostgresTestEnvironment` | 5 integrações passaram em PostgreSQL 16.13; detalhes em VALIDATION.md |
| AGD-001: dependências modulares | `ModularArchitectureTest` | regras executadas nos tipos existentes; cobertura cresce com a implementação dos módulos |
| AGD-001: format/lint frontend | scripts `format:check` e `lint`, Prettier/Oxlint fixados | `npm ci`, format, lint, testes/build e audit passaram; comando e resultado em VALIDATION.md |
| AGD-001: format Java | Spotless + Google Java Format em `backend/pom.xml` | `spotless:check verify` passou; 18 testes unitários/arquitetura e 5 integrações PostgreSQL; VALIDATION.md |
| AGD-001: SAST, dependências e segredos | CodeQL, Trivy, npm audit e scan de imagem | App CI ([run 38663531502](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38663531502)) e CodeQL ([run 38663531528](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38663531528)) passaram no SHA `3a4653442762abc5b0a64f32c633394b733715de`; todos os jobs dos dois workflows concluíram com sucesso. A checagem de config do App CI cobre somente `backend/Dockerfile`; scan Trivy completo de dependências segue pendente após HTTP 429 de Maven Central observado na falha anterior. Detalhes e avisos não bloqueantes em VALIDATION.md. |
| AGD-001: imagem | Dockerfile com bases por digest e Trivy | imagem final digest `sha256:5802db603808725c81e9cf0b779eb9bf8a118ea9cef28301a79c726c27603aee`; 0 HIGH/CRITICAL no scan local; não publicada |
| AGD-001: migrations | Flyway V1/V2, `DatabaseBootstrapIT`, `TenantPersistenceIT` | V1 preservada; V1+V2 em schema vazio e upgrade V1→V2 passaram em PostgreSQL 16.13 no commit `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`; comando em VALIDATION.md |
| Documento 02: intervalo `[início, fim)` | `TimeInterval` e seus testes | value object validado; não equivale à proteção concorrente de AGD-015 |
| Documento 03: envelope de erro | `ApiErrorResponse`, `SecurityErrorWriter` | suporte inicial de segurança; APIs de negócio futuras |
| AGD-004: correlação | `CorrelationIdFilter` e testes | correlação local; auditoria completa/tracing distribuído pendentes |
| AGD-003: autenticação administrativa | fronteira fechada em `SecurityConfiguration` | autenticação/tenant/papéis ainda não implementados |
| AGD-002: Tenant e schema | `tenant.domain.Tenant`, `TenantSlug`, `TenantStatus`; `PostgresTenantRepository`; V2; `TenantPersistenceIT` | Tenant ID/name/slug/timezone/status/created_at/version; slug canonizado e constraint global; fresh/upgrade/validate/rerun e corrida simultânea passaram em PostgreSQL 16.13 no commit `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`; comando em VALIDATION.md; CI remoto ainda não executado |
| AGD-002: isolamento de aplicação | `AuthenticatedTenant`, `GetTenant`, `RenameTenant`, adapter escopado por `tenant_id` | testes de ator controlado permitem ler/renomear tenant próprio e negam leitura/mutação de outro com o mesmo `TenantNotFoundException`; valida isolamento do caso de uso/persistência, não autenticação real nem contrato HTTP |
| AGD-002: derivação do principal e HTTP | `SecurityConfiguration` permanece deny-all; contrato descrito no plano AGD-002/ADR-0005 | pendente AGD-003: login/sessão, associação `AdminUser`–tenant verificada pelo servidor, resolver principal autorizado, ignorar/rejeitar tenantId do cliente e respostas HTTP não reveladoras |
| AGD-005 | pacote reservado | idempotência/outbox não implementadas |
| Demais histórias P0/P1/P2 | especificação preservada | não implementadas |

Não converter preparação em conclusão de história. AGD-002 demonstra isolamento somente nos casos de uso/persistência com ator de teste; autenticação e garantia HTTP dependem de AGD-003. Evidências locais, remotas e homologação devem permanecer distinguidas e associadas a commits observáveis.
