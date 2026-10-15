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
| AGD-001: migrations | Flyway V1, `DatabaseBootstrapIT` | V1 aplicada de schema vazio e revalidada; V1→V2 depende da próxima migration funcional de AGD-002 |
| Documento 02: intervalo `[início, fim)` | `TimeInterval` e seus testes | value object validado; não equivale à proteção concorrente de AGD-015 |
| Documento 03: envelope de erro | `ApiErrorResponse`, `SecurityErrorWriter` | suporte inicial de segurança; APIs de negócio futuras |
| AGD-004: correlação | `CorrelationIdFilter` e testes | correlação local; auditoria completa/tracing distribuído pendentes |
| AGD-003: autenticação administrativa | fronteira fechada em `SecurityConfiguration` | autenticação/tenant/papéis ainda não implementados |
| AGD-002 e AGD-005 | documentação e pacotes reservados | funcionalidades não implementadas |
| Demais histórias P0/P1/P2 | especificação preservada | não implementadas |

Não converter preparação em conclusão de história. Evidências remotas, homologação e upgrade V1→V2 seguem pendentes; associar novos critérios a execuções e commits observáveis.
