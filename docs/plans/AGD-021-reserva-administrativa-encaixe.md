# AGD-021 — Reserva administrativa e encaixe

## Identificação e objetivo

- História P0 AGD-021; implementar criação administrativa CONFIRMED de reserva para cliente existente ou novo, incluindo encaixes explícitos.
- Referências: documento 01, administração/encaixe/cliente bloqueado; documento 02, §§4, 9 e 13; documento 03, `POST /api/v1/admin/appointments`; documento 06, critério AGD-021.
- Escopo: comando, validação de regras excepcionais, snapshot, histórico, auditoria, idempotência, outbox, concorrência PostgreSQL e interface iniciada pela agenda.
- Exclui remarcação, cancelamento, faltas/desbloqueio, notificações externas, cobrança e integração de cliente verificado.

## Estado e dependências

- Branch `agd-021-reserva-administrativa-encaixe`, derivada da main pós-PR #12 em `160db144adc6bcff72b6f293a2a561a33bdfb2f1`.
- AGD-015 já fornece V13, cliente tenant-scoped/E.164, snapshots imutáveis, história, exclusion constraint, lock de tenant compartilhado com bloqueios, coordenação idempotente, auditoria e `AppointmentConfirmed` na outbox. V14 permanece intacta.
- AGD-020 fornece agenda administrativa e detalhes/história; nenhuma tabela ou migration posterior a V14 existe na base.
- AGD-032/033 ainda não implementaram faltas/restrições persistidas. A evidência sobre cliente restringido será somente por porta/adaptador controlado; não se alegará integração real.
- Subagentes independentes não puderam iniciar porque o ambiente atingiu o limite de threads. Revisão será sequencial e essa limitação será registrada; não equivale a revisão independente nem aprovação humana.

## Contrato e decisões

- `POST /api/v1/admin/appointments` recebe `serviceId`, `startsAt` com offset, exatamente uma forma de cliente (`clientId` existente ou `client{name,phone}` novo), `overridePolicies`, `reason` e `expectedScheduleVersion`.
- Allowlist exclusiva de override: `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE`. Decisão aprovada em 17/10/2026 e registrada em ADR-0016: `OUTSIDE_WEEKLY_SCHEDULE` ignora somente a grade semanal quando não existe exceção por data. `CLOSED` permanece fechado. `SPECIAL` substitui a grade e exige que a ocupação completa, incluindo buffer, caiba em um único período final. `MIN_NOTICE` é independente e pode ser ignorada dentro de `SPECIAL`, com motivo, respeitando seu período.
- Horizonte, serviço inativo, ausência de configuração, `CLOSED`, saída dos períodos de `SPECIAL`, bloqueios, ocupações e `OVERLAP` não são ignoráveis. Políticas desconhecidas/repetidas são rejeitadas. Qualquer override requer motivo não vazio. O comando não altera a grade nem cria/altera/remove exceção.
- `expectedScheduleVersion` é o contador monotônico já persistido em `schedule_revision`, não soma das versões independentes. Será obtido/validado junto das leituras relevantes e protegido por lock compartilhado até o commit para serializar contra alterações de grade, política, exceção e bloqueio. Mudança após análise na UI resulta em conflito e nova decisão.
- Mesmo tenant e mesmo slot seguem o lock comum de agenda em READ COMMITTED; exclusion constraint permanece autoridade final. Bloqueios e appointments são reconsultados após aquisição do lock. Nenhuma exceção autoriza overlap.
- O service é lido e travado via porta de catálogo `FOR SHARE`; identidade do cliente e serviço são validadas pelo tenant. Snapshot é formado pelo servidor e não contém preço/moeda quando ausentes.
- Idempotência usa escopo administrativo versionado; canonicalização inclui tenant, serviço, instante, cliente (ID ou dados canônicos), revisão esperada, overrides ordenados e motivo normalizado. Replay revalida associação; não repete efeitos. Timeout mantém a mesma chave/conteúdo e não autoriza trocar a intenção.
- Reserva, histórico, auditoria de sucesso, resultado idempotente e `AppointmentConfirmed` compartilham a transação. Auditoria/outbox/history não recebem telefone nem motivo livre; somente códigos/campos permitidos. Evento não significa envio de WhatsApp.
- Readiness/publicação pública não é pré-requisito para comando administrativo; os estados comerciais exigidos para comando administrativo permanecem sujeitos ao contrato vigente e não serão inferidos de integração ausente.
- A busca mínima de cliente será tenant-scoped, limitada e omitirá telefone nos logs/telemetria. Se não for necessária para o fluxo de formulário, manter IDs/dados de cliente como contrato mínimo e não expandir o módulo.

## Estratégia

1. Usar a revisão monotônica já persistida em `schedule_revision`; adicionar lock compartilhado para impedir leituras obsoletas durante mutações concorrentes de agenda.
2. Reusar as regras temporais de AGD-013 para serviço, zona, duração/buffer, grade/exceções, antecedência, horizonte, bloqueios e ocupações; só `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE` integram a allowlist.
3. Reutilizar a confirmação PostgreSQL de AGD-015 para cliente, snapshot, exclusion, história, auditoria, idempotência e outbox, com ator administrativo e detalhes explicitamente permitidos.
4. Criar somente V15, necessária para histórico de motivo e allowlist SQL de auditoria; manter V1–V14 intactas e validar instalação, upgrade, `validate`/rerun e grants runtime.
5. Integrar à agenda administrativa seleção/criação de cliente, data/hora manual, timezone/offset, estimativa preliminar das regras, confirmação explícita de override e recuperação de versão/retry.
6. Provar com PostgreSQL/Testcontainers isolamento, CAS/replay, atomicidade, as duas ordens da corrida reserva–bloqueio e rejeição de overlap; browser E2E de reserva/encaixe/agenda fica no CI por não substituir o Compose local compartilhado.

## Progresso

- [x] 17/10/2026 — PR #12 revisado sequencialmente e integrado em merge `160db144adc6bcff72b6f293a2a561a33bdfb2f1`; head verificado `f8b6c1dde251964ee43b6d9d4dede7118f16bdfe`.
- [x] 17/10/2026 — checks de PR e push passaram no head AGD-020; App CI/CodeQL pós-merge passaram em `160db144adc6bcff72b6f293a2a561a33bdfb2f1`.
- [x] Implementar POST administrativo, busca tenant-scoped de cliente, regras explícitas de override, revisão/CAS, snapshot, histórico, auditoria, idempotência, outbox e UI na agenda.
- [x] Criar V15 funcional, preservando V1–V14; validar PostgreSQL/Testcontainers em banco limpo e upgrades.
- [x] 17/10/2026 — backend local: `spotless:check verify` passou com 70 testes unitários/arquiteturais e 111 integrações PostgreSQL 16.13/Testcontainers; frontend Prettier, Oxlint, 23 Vitest, TypeScript e Vite build passaram. `node --check` e `git diff --check` passaram.
- [x] Diagnóstico do primeiro App CI: o E2E recebeu conflito de revisão porque a UI reutilizava `scheduleRevision` carregada antes de mudanças posteriores de grade/exceção/bloqueio. A abertura do formulário agora atualiza grade e política e mantém o envio desabilitado até essa leitura terminar.
- [x] 17/10/2026 — head `33cf966dd0398ba323d855cb2a5daeed94ea17b2`: App CI push/PR, incluindo Keycloak/PostgreSQL/Chrome E2E, e CodeQL push/PR passaram. Runs em `docs/development/VALIDATION.md`.
- [x] 17/10/2026 — decisão aprovada para CLOSED/SPECIAL/MIN_NOTICE registrada em ADR-0016, requisitos, contrato OpenAPI, plano e `OPEN-QUESTIONS.md`.
- [x] 17/10/2026 — decisão aplicada no backend/API, UI e contratos; `spotless:check verify` passou com 70 unitários/arquiteturais e 114 integrações PostgreSQL 16.13/Testcontainers; frontend format/lint/23 Vitest/TS/build passou.
- [x] Publicado o head `40bcd32004f1d7e529f50952c67d2c662f9b00cd`; CodeQL push/PR passou. App CI push/PR falhou no E2E por seletor de rótulo obsoleto após atualização de texto na UI; seletor corrigido em seguida.
- [ ] Publicar a correção do seletor, acompanhar App CI/CodeQL em push e PR no head final e retirar o draft depois dos checks aprovados.
- [x] Revisão sequencial do diff original, commits e PR #13; revisores independentes indisponíveis por limite de threads. O CI do head anterior `2bd45cd` passou, mas não valida o delta desta decisão.

## Validação

| Critério | Procedimento esperado | Resultado observado |
|---|---|---|
| Integração AGD-020 | App CI/CodeQL push/PR e pós-merge | Passou; runs e escopo registrados em `docs/development/VALIDATION.md`. |
| Reserva administrativa normal/encaixe e limites de override | `./mvnw -B -ntp spotless:check verify` com PostgreSQL/Testcontainers | Passou em 17/10/2026: 70 unitários/arquiteturais + 114 integrações. Testes HTTP/PostgreSQL cobrem CLOSED rejeitado sem efeitos; SPECIAL permitido dentro e negado fora; atendimento que cabe mas buffer que excede; MIN_NOTICE isolado dentro de SPECIAL; replay sem duplicar reserva/história/auditoria/outbox e sem alterar revisão. A reserva às 08:00 sem exceção continua demonstrando encaixe fora da grade semanal. |
| Isolamento, CAS, atomicidade e conflitos | MockMvc + Testcontainers/transações independentes | Passou localmente: CSRF/revisão atual, tenant próprio/alheio, associação revogada, replay, conflitos, reserva–bloqueio nos dois sentidos e migrations V15. |
| Frontend | `npm run format && npm run format:check && npm run lint && npm test -- --run && npm run check` | Passou em 17/10/2026: Prettier/Oxlint, 23 Vitest, TypeScript e Vite build; mensagem de UI diferencia grade semanal, CLOSED, períodos SPECIAL e override independente de antecedência. |
| Browser E2E real | App CI `oidc-browser-e2e` com Keycloak/PostgreSQL/Chrome | Passou no SHA `33cf966dd0398ba323d855cb2a5daeed94ea17b2`, push e PR. E2E local não foi executado para preservar Compose/volumes compartilhados. |
| Gate remoto AGD-021 | App CI e CodeQL em push e PR | Passou no SHA `33cf966dd0398ba323d855cb2a5daeed94ea17b2`; links e registro da falha inicial/correção em `docs/development/VALIDATION.md`. |

## Pendências e limites

- A decisão CLOSED/SPECIAL/MIN_NOTICE está aprovada e os testes locais passaram. App CI/CodeQL no novo head ainda pendentes; o E2E existente no head anterior prova o fluxo geral, não estes cenários temporais novos.
- Restrições reais de cliente dependem de AGD-032/033; adapters de teste demonstram apenas composição.
- Revisões por subagentes não disponíveis por limite de threads; sem aprovação humana GitHub inferida.
- Homologação, prontidão legal/comercial, secret manager/rotação em staging, HTTPS, preflight, scan completo, retenção e fornecedores reais permanecem fora deste incremento.

## Encerramento ou retomada

- Não marcar AGD-021 concluída enquanto integração real de restrições ou outro critério aplicável estiver sem evidência.
- Próximo escopo após esta história: AGD-022 — Remarcação atômica.
