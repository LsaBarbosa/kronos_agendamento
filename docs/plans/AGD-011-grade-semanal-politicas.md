# AGD-011 — Grade semanal e políticas

## Identificação e objetivo

- Branch `agd-011-grade-semanal-politicas`, derivada da `origin/main` `c5dbec26cf88843704c6bf1575b69666ed235ab0`; PR [#6](https://github.com/LsaBarbosa/kronos_agendamento/pull/6) aberto para `main`, head temporal `b2e76a590c886f14e0908355ccea62dd2c3df69e`.
- Objetivo: permitir ao `TENANT_ADMIN` manter uma grade semanal recorrente e as políticas de reserva de forma tenant-scoped, versionada, auditável e idempotente.
- Fontes: documento 01 §§5.2–5.5; documento 02 §§1–5 e entidades `AvailabilityPolicy`/`WeeklyPeriod`; documento 03 §§2, 5, 6, 7, 13–15 e rotas administrativas de política/grade; documento 04 §§4–8, 14, 18 e critérios AGD-011 no documento 06.

## Escopo e exclusões

- Inclui operações administrativas GET/PUT para `/api/v1/admin/schedule/weekly-periods` e `/api/v1/admin/availability-policy`, UI após login existente, persistence PostgreSQL, CAS, idempotência, auditoria e eventos outbox já definidos (`ScheduleChanged`, `AvailabilityPolicyChanged`).
- Uma agenda por tenant. Dias são ISO `MONDAY`–`SUNDAY`; horários são locais no timezone IANA persistido do tenant. Períodos são intervalos semiabertos `[start,end)` no dia indicado; adjacência não é sobreposição. O contrato não introduz geração de slots nem converte recorrência em offsets UTC.
- Configuração ausente é representada explicitamente, distinta de semana configurada sem períodos (todos os dias fechados). Política não configurada não recebe valores padrão. Ausência nunca significa prontidão/publicação.
- Exclui exceções, bloqueios, disponibilidade, reservas, notificações, publicação pública e qualquer alteração de preço, retenção ou integração externa.
- Decisão aprovada em 15/10/2026: limites múltiplos de 15 minutos, intervalos `[início,fim)`, adjacência válida, sobreposição inválida, `início < fim` e rejeição de cruzamento de meia-noite. O alinhamento independe de slot 30/60; ver ADR-0010.

## Critérios verificáveis

- [x] Domínio/API/PostgreSQL cobrem limites de 15 minutos, ordem, sobreposição, adjacência e rejeição de cruzamento de meia-noite; browser verifica que 09:10 não persiste; independência de slot 30/60 comprovada na API com limites 09:15–12:45.
- [x] Opções de slot (15/30/60), antecedência (1–24), horizonte (7/30/60/90), cancelamento (2–24) e lembrete (2/6/12/24) validadas no servidor e banco.
- [x] GET diferencia ausência de configuração de grade configurada vazia; timezone do tenant é devolvido como zona IANA.
- [x] PUT substitui integralmente cada recurso com versão independente/CAS; a substituição de períodos é transacional e falhas deixam estado anterior intacto.
- [x] Replay autorizado retorna resultado persistido; digest canônico ordena períodos e inclui tenant, expectedVersion, timezone e conteúdo validado.
- [x] Mutação, resultado idempotente, auditoria e evento de outbox definido são atômicos; replay/sem alteração não duplica versão, auditoria ou evento.
- [x] HTTP deriva tenant do vínculo ativo, impõe CSRF, revalida associação e não aceita tenant informado pelo cliente.
- [x] UI autenticada edita e recarrega os dados; E2E confirmou persistência, sessão revogada/expirada, preservação do rascunho e recuperação explícita de conflito.
- [x] V8 funcional: instalação V1–V8, upgrades V7→V8, validação/rerun e grants runtime verificados por PostgreSQL/Testcontainers.
- [x] V9 temporal: fresh e upgrade V8, validate/rerun, constraint PostgreSQL e proteção contra alteração de dados legados incompatíveis passaram em PostgreSQL 16.13/Testcontainers.

## Estado, decisões e dependências

- Fundação observada: migrations V1–V7, OIDC/CSRF, `AdminAccessPolicy`, idempotência transacional, auditoria allowlist/append-only, outbox e catálogo React.
- Sem defaults aprovados para grade ou políticas. A primeira leitura comunica ausência; o administrador define toda a política e pode salvar grade vazia como semana fechada.
- Dias seguem `java.time.DayOfWeek` / enum ISO; API serializa nomes ingleses estáveis em maiúsculas. Horários usam `HH:mm` local.
- Documento 02 e ADR-0010 definem períodos semanais em horário local, `[início,fim)`, alinhados a 15 minutos e no mesmo dia; adjacência permitida. A granularidade não depende do slot de disponibilidade.
- Grade semanal e política são recursos separados conforme as rotas/modelo documentados; cada um tem CAS próprio. Cada PUT substitui integralmente apenas seu recurso em uma única transação, impedindo perda parcial de seus períodos ou campos. Escritas independentes não sobrescrevem colunas umas das outras.
- Idempotency scopes versionados, canonicalização SHA-256 length-prefixed e identidade por tenant + scope + key, conforme ADR-0008; correlation ID não substitui `operationId`.
- Auditoria usa somente changed-fields/version before/after. Outbox usa eventos e payloads mínimos previstos pelo documento 03, sem publisher artificial.
- Rotas públicas continuam deny-all; AGD-010 não é concluída por configurar grade. AGD-001/003, snapshot AGD-015, retenção e integrações preservam pendências existentes.

## Estratégia técnica e validação

1. Completar contratos DTO/HTTP e OpenAPI sem modificar regras fora do backlog.
2. Implementar domínio de período/grade/política e casos de uso com autorização, CAS, canonicalização e limites transacionais.
3. Preservar V8 e criar V9 com `CHECK` de alinhamento; interromper upgrade se houver legado fora da regra, sem arredondamento ou exclusão, e documentar preflight/ajuste aprovado via CAS.
4. Implementar adapters JdbcTemplate e codecs explícitos para replay.
5. Integrar controlador ao matcher administrativo existente e construir tela React, mantendo estados de falha, sessão revogada e `VERSION_CONFLICT`.
6. Provar com PostgreSQL/Testcontainers, MockMvc e browser E2E real Keycloak/PostgreSQL/Chrome; executar gates backend/frontend, scan de segredos, App CI e CodeQL.
7. Fazer revisão independente em leitura sobre requisitos/tempo/UX e segurança/transação/isolamento; corrigir achados pertinentes.

### Upgrade legado V8→V9

Antes de ativar a versão que migra para V9, executar no banco do tenant, em conexão somente leitura:

```sql
SELECT p.tenant_id, p.weekday, p.local_start, p.local_end, s.version
FROM agendamento.weekly_period p
JOIN agendamento.weekly_schedule s USING (tenant_id)
WHERE mod(extract(minute FROM p.local_start)::integer, 15) <> 0
   OR extract(second FROM p.local_start) <> 0
   OR mod(extract(minute FROM p.local_end)::integer, 15) <> 0
   OR extract(second FROM p.local_end) <> 0
   OR p.local_end >= TIME '24:00'
ORDER BY p.tenant_id, p.weekday, p.local_start;
```

Se houver linhas, interromper a promoção. O responsável apresenta cada grade ao administrador do tenant e registra a decisão de horários; usar o fluxo administrativo da versão V8 para substituição integral com `expectedVersion` atual (CAS), sem arredondar automaticamente. Reexecutar a consulta até zero linhas e só então habilitar a promoção/migração V9. Flyway falha fechado se a precondição não for atendida; V9 não altera dados nem apaga o histórico publicado.

## Progresso

- [x] 15/10/2026 — base e PR #6 confirmados: head `200f9c9070af07d274397af0f23e3c7b3aa85d3d`, aberto e mergeable; árvore limpa antes do incremento.
- [x] Contratos OpenAPI, domínio/casos de uso, V8, API e UI do incremento base implementados.
- [x] Validação Maven/PostgreSQL, frontend, npm audit, parse YAML e sintaxe do browser passaram; detalhes em `VALIDATION.md`.
- [x] Duas revisões independentes em leitura: inconsistência documental de cobertura e lacunas de evidência de rollback de substituição/replay após revogação corrigidas com testes/documentação; pareceres não equivalem a aprovação humana.
- [x] Incremento temporal publicado no PR #6 em `b2e76a590c886f14e0908355ccea62dd2c3df69e`; quatro checks push/PR passaram nesse SHA. Head documental `9f170e8cd39e1f1bcef04e148d09d872ea9f2f53` também passou nos quatro checks após rerun do App CI PR por indisponibilidade de download do Maven Wrapper; falha inicial registrada abaixo.
- [x] Decisão temporal aprovada e refletida nos requisitos, ADR-0010, OpenAPI, domínio, API e UI.
- [x] Commit temporal publicado no PR #6; App CI e CodeQL em push/PR passaram no SHA `b2e76a590c886f14e0908355ccea62dd2c3df69e`.
- [ ] Homologação/staging permanece separada e pendente.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Domínio/API de horários e políticas | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` em `backend/` | Domínio, HTTP, Testcontainers PostgreSQL e migrations | Passou: `BUILD SUCCESS`, 32 unitários/arquiteturais e 54 integrações, zero falhas. HTTP rejeita 09:10/cruzamento; rejeições não versionam nem criam sucesso; aceitou 09:15–12:45 com slots 30/60. |
| Persistência/CAS/atomicidade | Mesmo `verify`, PostgreSQL 16.13/Testcontainers | Fresh V1–V9, upgrades, Flyway validate/rerun, CAS, rollback, replay e constraint | Passou; V8→V9 com grade alinhada, V9 bloqueia legado 09:10 preservando dado/schema em V8 até reparo explícito e rerun; adjacência é gravada, overlap e desalinhamento/24:00 são rejeitados no PostgreSQL. V1–V8 sem alterações. |
| Dados locais para compatibilidade | Consulta read-only do preflight V9 no PostgreSQL Compose | Detectar dados que bloqueariam upgrade | Banco dev local em V8; consulta retornou zero períodos incompatíveis. É apenas evidência da cópia local; ambiente hospedado requer preflight próprio. Dados existentes preservados. |
| UI com OIDC real | App CI job `oidc-browser-e2e`; `node scripts/dev/test-keycloak-browser.mjs` | Keycloak/PostgreSQL/Chrome reais; 09:10 rejeitado sem salvar e save/reload | App CI push [38823847787](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823847787) e PR [38823854921](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823854921) passaram; ambos incluem `oidc-browser-e2e`. Não executado localmente para preservar banco dev com dados. |
| Frontend e contrato | `npm run format:check && npm run check && npm run lint && npm test` em `frontend/`; `node --check scripts/dev/test-keycloak-browser.mjs`; `prettier ../docs/openapi/admin-availability.yaml >/dev/null` | Prettier, Oxlint, Vitest, TypeScript/build, sintaxe E2E e parse YAML | Passou: 2 arquivos/4 testes Vitest, build/lint/TypeScript, script do browser e parse YAML. |
| Dependências | `npm audit --audit-level=high` em `frontend/` | Audit das dependências frontend | Passou com rede autorizada: `found 0 vulnerabilities`. Tentativa inicial no sandbox falhou `EAI_AGAIN registry.npmjs.org`; repetida com rede. Scan completo de dependências do produto continua pendente conforme AGD-001. |
| Diff e segredos | `git diff --check`; revisão; App CI secrets/imagem/config | Sem whitespace inválido nem segredo | `git diff --check` passou; jobs de segredo, security e verify passaram nos dois eventos. Scan completo de dependências continua pendente. |
| App CI push/PR | Runs [38823847787](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823847787) / [38823854921](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823854921) | Passar verify/Testcontainers, frontend, security, segredos e browser E2E | Ambos `success` no SHA `b2e76a590c886f14e0908355ccea62dd2c3df69e`; todos os jobs concluídos, incluindo `oidc-browser-e2e`. |
| CodeQL push/PR | Runs [38823847890](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823847890) / [38823854900](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38823854900) | Analisar Java/Kotlin e JavaScript/TypeScript | Ambos `success` no SHA `b2e76a590c886f14e0908355ccea62dd2c3df69e`; as duas linguagens passaram. |
| Checks do head documental | App CI push/PR [38824442067](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38824442067)/[38824448440](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38824448440); CodeQL push/PR [38824442019](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38824442019)/[38824448419](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38824448419) | Revalidar head atual, incluindo browser E2E | Todos `success` em `9f170e8cd39e1f1bcef04e148d09d872ea9f2f53`. A primeira tentativa App CI PR falhou por `wget: Failed to fetch` do Maven Wrapper no Maven Central na etapa Docker do E2E; rerun passou sem alteração de código/gates. |
| Checks do head documental seguinte | App CI push/PR [38825306221](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38825306221)/[38825311905](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38825311905); CodeQL push/PR [38825306228](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38825306228)/[38825311917](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38825311917) | Verificar documentação e head do PR | Todos `success` em `6b8ec74ca54d38ed50a2c5b9efe9edd18c0056cd`; browser E2E e verify/PostgreSQL passaram. |

## Retomada

AGD-011 permanece parcial enquanto houver critério aplicável sem evidência, inclusive homologação/staging. A semântica temporal está decidida, não é pendência de produto. A próxima história é AGD-012 — Exceções e bloqueios; sua preparação deve seguir os contratos do backlog sem antecipar implementação neste incremento. Scan completo de dependências, homologação HTTPS, retenção e integrações reais continuam pendentes.
