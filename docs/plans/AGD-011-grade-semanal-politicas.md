# AGD-011 — Grade semanal e políticas

## Identificação e objetivo

- Branch `agd-011-grade-semanal-politicas`, derivada de `origin/main` em `c5dbec26cf88843704c6bf1575b69666ed235ab0` após fetch; PR #5 está integrado.
- Objetivo: permitir ao `TENANT_ADMIN` manter uma grade semanal recorrente e as políticas de reserva de forma tenant-scoped, versionada, auditável e idempotente.
- Fontes: documento 01 §§5.2–5.5; documento 02 §§1–5 e entidades `AvailabilityPolicy`/`WeeklyPeriod`; documento 03 §§2, 5, 6, 7, 13–15 e rotas administrativas de política/grade; documento 04 §§4–8, 14, 18 e critérios AGD-011 no documento 06.

## Escopo e exclusões

- Inclui operações administrativas GET/PUT para `/api/v1/admin/schedule/weekly-periods` e `/api/v1/admin/availability-policy`, UI após login existente, persistence PostgreSQL, CAS, idempotência, auditoria e eventos outbox já definidos (`ScheduleChanged`, `AvailabilityPolicyChanged`).
- Uma agenda por tenant. Dias são ISO `MONDAY`–`SUNDAY`; horários são locais no timezone IANA persistido do tenant. Períodos são intervalos semiabertos `[start,end)` no dia indicado; adjacência não é sobreposição. O contrato não introduz geração de slots nem converte recorrência em offsets UTC.
- Configuração ausente é representada explicitamente, distinta de semana configurada sem períodos (todos os dias fechados). Política não configurada não recebe valores padrão. Ausência nunca significa prontidão/publicação.
- Exclui exceções, bloqueios, disponibilidade, reservas, notificações, publicação pública e qualquer alteração de preço, retenção ou integração externa.

## Critérios verificáveis

- [ ] Períodos múltiplos, dias fechados, sobreposição, adjacência e rejeição de cruzamento de meia-noite foram exercitados. Aceite temporal permanece parcial até decisão de alinhamento/meia-noite.
- [x] Opções de slot (15/30/60), antecedência (1–24), horizonte (7/30/60/90), cancelamento (2–24) e lembrete (2/6/12/24) validadas no servidor e banco.
- [x] GET diferencia ausência de configuração de grade configurada vazia; timezone do tenant é devolvido como zona IANA.
- [x] PUT substitui integralmente cada recurso com versão independente/CAS; a substituição de períodos é transacional e falhas deixam estado anterior intacto.
- [x] Replay autorizado retorna resultado persistido; digest canônico ordena períodos e inclui tenant, expectedVersion, timezone e conteúdo validado.
- [x] Mutação, resultado idempotente, auditoria e evento de outbox definido são atômicos; replay/sem alteração não duplica versão, auditoria ou evento.
- [x] HTTP deriva tenant do vínculo ativo, impõe CSRF, revalida associação e não aceita tenant informado pelo cliente.
- [x] UI autenticada edita e recarrega os dados; E2E confirmou persistência, sessão revogada/expirada, preservação do rascunho e recuperação explícita de conflito.
- [x] V8 funcional: instalação V1–V8, upgrades V7→V8, validação/rerun e grants runtime verificados por PostgreSQL/Testcontainers.

## Estado, decisões e dependências

- Fundação observada: migrations V1–V7, OIDC/CSRF, `AdminAccessPolicy`, idempotência transacional, auditoria allowlist/append-only, outbox e catálogo React.
- Sem defaults aprovados para grade ou políticas. A primeira leitura comunica ausência; o administrador define toda a política e pode salvar grade vazia como semana fechada.
- Dias seguem `java.time.DayOfWeek` / enum ISO; API serializa nomes ingleses estáveis em maiúsculas. Horários usam `HH:mm` local.
- Documento 02 define intervalos semiabertos e adjacência de ocupações; tratamento de períodos semanais que cruzam meia-noite e alinhamento dos limites permanecem sob confirmação de produto em `OPEN-QUESTIONS.md`. Proposta conservadora: mesmo dia, sem cruzamento, adjacência permitida; alinhamento de início/fim a cada 15 minutos só será aplicado se aprovado.
- Grade semanal e política são recursos separados conforme as rotas/modelo documentados; cada um tem CAS próprio. Cada PUT substitui integralmente apenas seu recurso em uma única transação, impedindo perda parcial de seus períodos ou campos. Escritas independentes não sobrescrevem colunas umas das outras.
- Idempotency scopes versionados, canonicalização SHA-256 length-prefixed e identidade por tenant + scope + key, conforme ADR-0008; correlation ID não substitui `operationId`.
- Auditoria usa somente changed-fields/version before/after. Outbox usa eventos e payloads mínimos previstos pelo documento 03, sem publisher artificial.
- Rotas públicas continuam deny-all; AGD-010 não é concluída por configurar grade. AGD-001/003, snapshot AGD-015, retenção e integrações preservam pendências existentes.

## Estratégia técnica e validação

1. Completar contratos DTO/HTTP e OpenAPI sem modificar regras fora do backlog.
2. Implementar domínio de período/grade/política e casos de uso com autorização, CAS, canonicalização e limites transacionais.
3. Criar V8 funcional com exclusão PostgreSQL anti-sobreposição por tenant/dia, checks, índices, privilégios runtime mínimos e expansão das allowlists V5/V6; preservar V1–V7.
4. Implementar adapters JdbcTemplate e codecs explícitos para replay.
5. Integrar controlador ao matcher administrativo existente e construir tela React, mantendo estados de falha, sessão revogada e `VERSION_CONFLICT`.
6. Provar com PostgreSQL/Testcontainers, MockMvc e browser E2E real Keycloak/PostgreSQL/Chrome; executar gates backend/frontend, scan de segredos, App CI e CodeQL.
7. Fazer revisão independente em leitura sobre requisitos/tempo/UX e segurança/transação/isolamento; corrigir achados pertinentes.

## Progresso

- [x] 15/10/2026 — base remota verificada em `c5dbec2`; branch criada limpa a partir de `origin/main`.
- [x] Contratos OpenAPI, domínio/casos de uso, V8, API e UI implementados.
- [x] Testes PostgreSQL, frontend e browser E2E local; duas revisões independentes em leitura e correções de auditoria/CAS/UX.
- [x] Validação local completa: Maven/PostgreSQL, frontend e browser E2E real; detalhes em `VALIDATION.md`.
- [x] Commits `4344d98a16161c17385a82bfd11c10e8be2de66b` (implementação) e `87b2478ab85fad730413fa94d9f3b7bb121f50a8` (evidências) publicados; PR #6 aberta. App CI/CodeQL push/PR passaram no head `87b2478`; a primeira execução CodeQL PR teve falha transitória ao baixar Maven Wrapper e seu rerun passou. Atualização deste registro é documental; os checks do head mais recente do PR são acompanhados separadamente.
- [ ] Homologação/staging permanece separada e pendente.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Domínio de horários e políticas | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` em `backend/` | Testcontainers PostgreSQL e testes unitários | Passou no estado de trabalho em `c5dbec2` (32 testes unitários/arquiteturais e 50 de integração, zero falhas); aceite de alinhamento/meia-noite aguarda decisão do usuário |
| Persistência/CAS/atomicidade | Mesmo `verify`, PostgreSQL 16.13/Testcontainers | Migração V8, exclusão overlap, CAS/concorrência, rollback, replay e Flyway | Passou: fresh install, upgrade, validate/rerun, grants runtime, concorrência e rollback |
| UI com OIDC real | `node scripts/dev/test-keycloak-browser.mjs` | Keycloak/PostgreSQL/Chrome reais; save/reload, conflito, rascunho e recuperação | Passou localmente no stack reconstruído com esta branch e V8; sem homologação |
| Frontend | `npm run format:check && npm run check && npm run lint && npm test` em `frontend/`; `node --check scripts/dev/test-keycloak-browser.mjs` | Prettier, Oxlint, Vitest, TypeScript/build e sintaxe E2E | Passou: 2 arquivos/4 testes Vitest, build e lint verdes |
| CI remoto (head com evidências) | App CI e CodeQL em push/PR | Todos os jobs concluíram com `success` em `87b2478ab85fad730413fa94d9f3b7bb121f50a8`: App CI `38821660530`, `38821667659`; CodeQL `38821660513`, `38821667632`. A primeira tentativa CodeQL PR falhou por `wget` sem obter Maven Wrapper 3.9.11; análise/logs identificaram download, não erro do código. Rerun do workflow completou success, sem enfraquecer gate. Links em `VALIDATION.md`. |

## Retomada

AGD-011 permanece parcial enquanto houver critério aplicável sem evidência. A próxima história segue backlog após AGD-011; exceções e bloqueios pertencem a AGD-012. Registro de gates, SHAs, links do PR e pendências deve ser mantido em `STATUS.md`, `VALIDATION.md` e `TRACEABILITY.md`.
