# AGD-012 — Exceções e bloqueios

## Identificação e objetivo

- Próxima história P0, tamanho M, backlog do documento 06; preparação vinculada à evolução do PR #6, sem implementar nem publicar comportamento AGD-012 nesta rodada.
- Objetivo: permitir administrar exceções por data e períodos de bloqueio sem ocultar conflitos com reservas confirmadas, invalidando a disponibilidade derivada e preservando auditoria.
- Fontes: `docs/spec/app-agendamento-01-especificacao-mvp.md` §5.3; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` entidades `ScheduleException`/`BlockedPeriod` e §12; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção “Grade, exceções e bloqueios” e evento `ScheduleChanged`; `docs/spec/app-agendamento-04-arquitetura-implementacao.md` §§4, 8–10; critérios AGD-012 em `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`.
- Escopo: exceção de data fechada ou com horário especial; criar, consultar e remover bloqueios com motivo; detectar/resolver conflito com reserva confirmada segundo fluxo aprovado; invalidação pós-commit da disponibilidade em cache; histórico/auditoria do ator.
- Exclusões: motor/consulta de disponibilidade AGD-013, reservas/remarcação/cancelamento, publicação pública, notificações, calendário externo, bloqueio de cliente e qualquer alteração da grade semanal AGD-011.

## Estado e dependências

- AGD-011 fornece grade semanal, timezone IANA do tenant, OIDC, autorização tenant-scoped, CSRF, CAS, idempotência, auditoria transacional e outbox; V9 valida alinhamento temporal da grade. PR #6 está aberto, sem merge/deploy; confira o estado remoto e derive a branch da `main` atualizada após integração autorizada.
- A especificação propõe `ScheduleException(tenant, local_date, kind, periods, reason, version)` e `BlockedPeriod(block_id, tenant, starts_at, ends_at, reason, status, version)`; contrato REST administrativo existe em forma proposta no documento 03.
- Confirme a última migration integrada antes de reservar número. Esperado após integração de AGD-011: V10; nunca alterar V1–V9 nem assumir o estado apenas pelo plano.
- Autorização deve reutilizar a associação ativa e o papel já existente `TENANT_ADMIN`; ator, tenant e timezone vêm do contexto autenticado/servidor, não do payload.

## Critérios de aceite verificáveis

- [ ] Para uma data, administrador pode definir fechado ou substituir grade por horários especiais; leitura diferencia ausência de exceção da exceção explicitamente fechada.
- [ ] Administrador pode criar e remover/cancelar bloqueios, sempre exigindo e preservando motivo conforme contrato aprovado.
- [ ] Bloqueio que intersecta reserva confirmada é recusado com conflito identificável sem ocultar a reserva; somente adicionar fluxo de resolução se houver decisão e caso de uso aprovado.
- [ ] Leitura e mutação respeitam tenant, associação atual, OIDC, CSRF e política não reveladora para objetos alheios/inexistentes.
- [ ] Atualizações concorrentes usam CAS/versionamento; comando repetido é idempotente; autorização atual é revalidada antes de replay.
- [ ] Mutação, resultado idempotente, auditoria de sucesso e `ScheduleChanged` previsto compartilham transação. Falha em qualquer gravação obrigatória reverte a alteração.
- [ ] Cache de disponibilidade é invalidado somente após commit; não é autoridade para aceitar reserva e nenhuma rota pública é habilitada por AGD-012.
- [ ] UI autenticada lista/edita exceções e bloqueios, apresenta motivo/conflito e confirma estado persistido após reload.
- [ ] Migração instala schema em banco vazio e atualiza versão anterior sem modificar dados silenciosamente; `validate`/rerun e privilégios runtime passam em PostgreSQL real.

## Decisões pendentes

- Produto: confirmar se períodos especiais herdam integralmente a semântica temporal de AGD-011 (múltiplos de 15, `[início,fim)`, mesmo dia) ou se têm regras distintas. Não assumir equivalência silenciosamente.
- Produto/arquitetura: definir como a UI informa horários de bloqueio (`Instant`/offset explícito ou horário local do tenant), sobretudo em transições DST com hora inexistente ou ambígua. Rejeitar entradas sem interpretação inequívoca; não usar offsets UTC fixos para recorrência.
- Produto: confirmar edição de exceção existente, limites de intervalo de consulta e semântica de remoção de bloqueio. API documenta PATCH/DELETE por CAS e recomenda remoção lógica após efeito auditável; manter isso alinhado com a necessidade de história, sem apagar auditoria.
- Produto/arquitetura: o MVP pode escolher recusar bloqueio conflitante (opção já permitida pelo requisito). Qualquer resolução/cancelamento em cascata de reserva exige comando separado, autorização, confirmação explícita e critérios próprios; não inferir de criar bloqueio.
- Operação: o cache concreto ainda pode não existir. Confirmar adapter de invalidação; se não houver cache ativo, manter uma porta/no-op justificável ou limitar-se ao mecanismo realmente existente, sem criar cache/publisher artificial.
- Não inventar retenção ou expurgo. Manter trilha, idempotência e outbox segundo decisão vigente até aprovação de política.

## Estratégia

1. Confirmar que PR #6 foi integrado, atualizar `main`, abrir branch AGD-012 e reler `AGENTS.md`, requisitos, ADRs e estado dos contratos.
2. Fechar as decisões de tempo local/DST e semântica de exceção; atualizar OpenAPI antes da implementação. Reutilizar validadores temporais apenas depois de confirmar compatibilidade de requisito.
3. Implementar agregados/casos de uso no módulo `availability`, adapters PostgreSQL e API protegida, mantendo tenant isolation e consultas não reveladoras.
4. Escolher bloqueio lógico (status ativo/cancelado) como padrão inicial coerente com auditoria e API proposta; efetivar em ADR apenas se houver consequência arquitetural relevante.
5. Definir constraints para período válido e unicidade/concorrência de exceção por tenant/data. Bloqueios devem impedir cruzamento de meia-noite local ambíguo; conflitos com reservas confirmadas precisam de prova transacional, não apenas precheck fora da transação.
6. Gravar outbox `ScheduleChanged` dentro da mesma transação e invalidar cache depois do commit/consumo; preservar claim fencing e entrega ao menos uma vez, sem alegar exactly-once externo.
7. Entregar UI com estados de carregamento, vazio, validação, conflito, erro, sessão expirada/revogação e sucesso. Não adicionar rotas públicas nem motor de disponibilidade.
8. Validar PostgreSQL/Testcontainers em instalação limpa/upgrade, isolamento HTTP, concorrência/CAS, rollback, replay, auditoria/outbox, DST e browser E2E OIDC; rodar gates CI e revisão independente.

## Estratégia de migration e compatibilidade

- Próxima migration estimada V10, a confirmar no remoto após merge do PR #6. Preservar V1–V9 e checksums.
- Separar tabelas para exceção, períodos especiais e bloqueios se isso refletir o modelo sem comprometer atomicidade; FK tenant com isolamento e índices por tenant/data/instante.
- Constraints PostgreSQL para `start < end`, intervalo semiaberto e integridade estrutural; exclusão/concorrência de bloqueios deve respeitar cancelamento lógico e reservas confirmadas. Justificar constraint por invariantes em vez de replicar somente validação Java.
- Sem backfill presumido: não há evidência de dados antigos dessas entidades. Migração deve criar schema funcional, grants mínimos runtime e manter auditoria append-only.

## Validação e evidências esperadas

| Critério | Verificação | Evidência esperada |
|---|---|---|
| Regras temporais e estado | testes unitários de domínio | fechado vs especial vs ausente; limites; DST conforme decisão |
| Persistência/concorrência | PostgreSQL real/Testcontainers com conexões independentes | migration fresh/upgrade, CAS, unicidade e integridade concorrente |
| Conflito com reserva | caso de uso e endpoint administrativo | bloqueio recusado, reserva preservada/visível e nenhuma trilha de sucesso |
| Segurança/isolation | testes HTTP autenticados com duas associações/tenants | permitido no próprio tenant, rejeição não reveladora para tenant alheio, CSRF e associação revogada |
| Atomicidade | falhar auditoria/outbox deliberadamente | rollback de alteração, resultado idempotente e evento |
| Cache/evento | consumer/adapter real do projeto, se existente | invalidação pós-commit; falha/retry não declara publicação não ocorrida |
| Interface | browser E2E com Keycloak real e PostgreSQL | criar/editar/fechar data, criar/cancelar bloqueio, reload e conflito visível |
| Gates | Maven, frontend, scanners, App CI e CodeQL | resultados ligados ao SHA publicado; homologação separada |

## Progresso e retomada

- [x] 15/10/2026 — preparação documental iniciada a partir das referências existentes; nenhum código, migration, endpoint ou regra AGD-012 implementados nesta etapa.
- [ ] Após decisão temporal de exceções/DST e integração do PR #6, confirmar migration real, revisar contratos e iniciar implementação na branch própria.
- [ ] Homologação, operação de cache e políticas de retenção permanecem evidências/decisões próprias; não inferir aprovação por CI.

## Encerramento ou retomada

AGD-012 está preparada para planejamento técnico, não iniciada funcionalmente. Antes de codificar, obter as decisões materiais de tempo das exceções e entrada de bloqueios em DST; em seguida implementar o caminho conservador que recusa bloqueio sobre reserva confirmada. Confirmar estado de `main`, migration e cache real no início do próximo incremento.
