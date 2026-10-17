# AGD-022 — Remarcação atômica

## Identificação e objetivo

- História P0 AGD-022. Expor `POST /api/v1/admin/appointments/{id}/reschedule` para alterar atomicamente o intervalo de uma reserva administrativa `CONFIRMED`, preservando sua identidade, cliente, serviço e snapshot.
- Referências: documentos 01 §§8/9/11/14; documento 02, estados, snapshots e ocupação; documento 03, agenda, idempotência, `AppointmentRescheduled`; documento 04, transações e módulos; documento 06, AGD-022 e E2E-04.
- Branch: `agd-022-remarcacao-atomica`, derivada da main após integrar PR #13.
- Exclusões: cancelamento/conclusão/falta, mudança de serviço/cliente, remarcação pelo cliente, WhatsApp real, cobrança, cache e alteração de políticas/grade.

## Estado e dependências

- AGD-021 integrada em `f62b35af2e9ae14c138219964458aed5f2b55a47`; V1–V15 existentes.
- O protocolo ADR-0014 serializa mutações de agenda pelo lock `tenant FOR UPDATE`, com transação `READ_COMMITTED`; reserva-reserva tem exclusion constraint, e bloqueios consultam ocupações sob o mesmo lock.
- A disponibilidade administrativa reutiliza grade, exceções, políticas, DST, bloqueios e ocupações reais. O destino será calculado com duração/buffer do snapshot da reserva, sem reler o catálogo como fonte histórica.
- Não existe tabela/pipeline persistido de lembretes. `AppointmentRescheduled` é o contrato versionado de outbox; invalidação/solicitação efetiva de lembretes depende de AGD-030/031.

## Contrato e decisões técnicas

- Body: `expectedVersion`, `expectedScheduleRevision`, `startsAt` offset-aware, `overridePolicies` (somente `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE`) e `reason` obrigatório quando houver override. `Idempotency-Key` identifica o comando.
- As revisões não são agregadas: `expectedVersion` protege a reserva; `expectedScheduleRevision` protege a configuração de agenda. Ambas entram na canonicalização versionada.
- Replays revalidam a associação administrativa atual, mas não reaplicam nem revalidam a versão/revisão já consumidas pelo próprio comando. Digest divergente permanece conflito.
- Ordem: idempotência sob `READ_COMMITTED` → lock tenant `FOR UPDATE` → lock/leitura da reserva no tenant → lock/check da revisão → leituras de grade, exceção, bloqueio e ocupações → update CAS → história, auditoria, outbox e receipt idempotente, todos na mesma transação. Para evitar ciclo modular, avançar `schedule_revision` usa `AgendaRevisionAdvancer` na API de appointments, implementado pelo adapter availability.
- O próprio appointment é excluído da consulta de ocupações; a exclusion constraint PostgreSQL continua como garantia final. Bloqueios, CLOSED, SPECIAL e regras não ignoráveis continuam efetivos. Overrides não são herdados; exigem conteúdo explícito e motivo novo.
- V15 não tinha campos before/after em `appointment_history`; V16 funcional os adiciona, amplia as allowlists SQL de auditoria/outbox e concede apenas UPDATE de starts/ends/occupied/version ao runtime. A migration usa objetos no schema Flyway configurado (sem fixar `agendamento`) para suportar instalação, upgrade e testes em schemas isolados; V1–V15 permanecem imutáveis.

## Estratégia

- Adicionar caso de uso, DTOs/OpenAPI, erros não reveladores/version conflict e atualização tenant-scoped com CAS.
- Reutilizar a avaliação de agenda com parâmetros de duração/buffer do snapshot e exclusão do ID atual; não chamar provedores externos na transação.
- Registrar a mudança em `appointment_history`, auditoria allowlisted, `AppointmentRescheduled` allowlisted e idempotency result no mesmo commit.
- A UI usa a agenda/detail existentes, horário e zona IANA, versões distintas, buffer visível e preservação do rascunho/intenção incerta. Nada será substituído quando houver conflito.
- Sem provider ou storage de lembrete: declarar integração pendente, sem afirmar cancelamento/envio efetivos.

## Progresso e validação

| Critério | Procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Somente CONFIRMED; snapshots mantidos; versão +1 | `PublicAppointmentIT.reschedulingUpdatesSameAppointmentAtomicallyAndReplayDoesNotDuplicateEffects` | PostgreSQL/Testcontainers confirma mesmo ID, versão única +1, snapshot, história e revisão atualizada | passou localmente no teste focal e na classe de integração; SHA final pendente |
| Conflito/versão/rollback preservam horário antigo | `PublicAppointmentIT.conflictingReschedulePreservesOriginalIntervalAndCreatesNoSuccessEffects`, `auditFailureRollsBackRescheduledIntervalVersionHistoryOutboxAndRevision` | conflito de destino e falha de auditoria não alteram intervalo, versão ou efeitos de sucesso | classe PostgreSQL/HTTP passou; falha de auditoria também comprova rollback do receipt idempotente |
| Ocupação do próprio ID excluída | teste PostgreSQL/HTTP acima e disponibilidade após remarcação | o próprio intervalo é removido da fonte de ocupações; disponibilidade reflete destino e origem | comprovado; comparação usa instantes normalizados corretamente |
| Concorrência reserva/bloqueio | lock tenant-first `READ_COMMITTED`, CAS e constraint existentes | alvo da remarcação revalidado após lock, conflito preserva intervalo anterior | a classe PostgreSQL/HTTP passou com CAS concorrente same-version e corridas remarcação→bloqueio/bloqueio→remarcação |
| Replay e resposta perdida | repetição da mesma chave/comando em PostgreSQL e browser E2E | receipt original; sem duplicar história, auditoria, outbox, versão ou revisão | replay HTTP após commit passou; E2E browser e simulação de resposta perdida aguardam CI do head final |
| OIDC/CSRF/tenant | MockMvc autenticado com CSRF; controller administrativo | operação passa pela associação OIDC atual e resolução tenant-scoped; capability pública não é consultada | contrato/código coberto por contexto admin dos testes; browser ainda pendente |
| UI | `AdminAgendaPanel`, testes/lint/build e E2E Keycloak | estados de carregamento/erro, DST offset-aware, retry idêntico e atualização após sucesso | frontend 23 Vitest/lint/typecheck/build passou; navegador aguardando execução CI |
| PostgreSQL/Flyway | `./mvnw spotless:check verify` | V1–V15 imutáveis, V16 fresh/upgrade, validate/rerun e grants | BUILD SUCCESS local: 70 unitários/arquiteturais e 123 integrações PostgreSQL 16.13/Testcontainers; V16 aplicada/validada em instalação e schemas de upgrade isolados |
| Revisão independente | até dois agentes em leitura | achados registrados sem substituir aprovação humana | ferramenta indisponível por limite de threads; revisão sequencial do coordenador sem bloqueador identificado, não independente nem aprovação humana |
| CI remoto | App CI e CodeQL push/PR | sucesso no head final publicado | pendente de publicação do head final |

## Descobertas e limites

- O pipeline de lembretes não está persistido/implementado. Outbox de remarcação informa uma mudança lógica, não prova invalidação nem envio.
- Prontidão legal/comercial, HTTPS/staging, secrets/rotação, preflight hospedado, scan completo, retenção aprovada, fornecedores reais e integração de restrições AGD-032/033 permanecem pendentes.
- Histórico e resposta nunca expõem capability de recuperação, token, digest ou contexto OIDC.

## Encerramento ou retomada

- AGD-022 permanece parcial: suíte completa backend/PostgreSQL (70 + 123), frontend (23 Vitest + format/lint/typecheck/build), migração/grants e corridas de remarcação passaram localmente. Browser E2E e CI push/PR aguardam o head publicado; integração real de lembretes continua dependente de AGD-030/031 e não é comprovada pelo evento outbox. Não será marcada `DONE` por CI isolado.
- Evidências locais correspondem ao código do commit `23dcb94531af...`; a atualização documental seguinte não altera o código exercitado. Os resultados remotos serão associados ao head final após a publicação.
- Próximo passo após revisão/integração: AGD-023 — Cancelamento e encerramento.
