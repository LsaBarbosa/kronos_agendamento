# AGD-022 — Remarcação atômica

## Identificação e objetivo

- História P0 AGD-022. Expor `POST /api/v1/admin/appointments/{id}/reschedule` para alterar atomicamente o intervalo de uma reserva administrativa `CONFIRMED`, preservando sua identidade, cliente, serviço e snapshot.
- Referências: documentos 01 §§8/9/11/14; documento 02, estados, snapshots e ocupação; documento 03, agenda, idempotência, `AppointmentRescheduled`; documento 04, transações e módulos; documento 06, AGD-022 e E2E-04.
- Branch: `agd-022-remarcacao-atomica`, derivada da main após integrar PR #13.
- Exclusões: cancelamento/conclusão/falta, mudança de serviço/cliente, remarcação pelo cliente, WhatsApp real, cobrança, cache e alteração de políticas/grade.

## Estado e dependências

- AGD-021 integrada em `f62b35af2e9ae14c138219964458aed5f2b55a47`; a branch já continha V1–V16 no head revisado `78d356cc2d6361e28dac0ba798afb5b15db1b900`.
- O protocolo ADR-0014 serializa mutações de agenda pelo lock `tenant FOR UPDATE`, com transação `READ_COMMITTED`; reserva-reserva tem exclusion constraint, e bloqueios consultam ocupações sob o mesmo lock.
- A disponibilidade administrativa reutiliza grade, exceções, políticas, DST, bloqueios e ocupações reais. O destino será calculado com duração/buffer do snapshot da reserva, sem reler o catálogo como fonte histórica.
- Não existe tabela/pipeline persistido de lembretes. `AppointmentRescheduled` é o contrato versionado de outbox; invalidação/solicitação efetiva de lembretes depende de AGD-030/031.

## Contrato e decisões técnicas

- Body: `expectedVersion`, `expectedScheduleRevision`, `startsAt` offset-aware, `overridePolicies` (somente `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE`) e `reason` obrigatório quando houver override. `Idempotency-Key` identifica o comando.
- As revisões não são agregadas: `expectedVersion` protege a reserva; `expectedScheduleRevision` protege a configuração de agenda. Ambas entram na canonicalização versionada.
- Replays revalidam a associação administrativa atual, mas não reaplicam nem revalidam a versão/revisão já consumidas pelo próprio comando. Digest divergente permanece conflito.
- Ordem: idempotência sob `READ_COMMITTED` → lock tenant `FOR UPDATE` → lock/leitura da reserva no tenant → lock/check da revisão → leituras de grade, exceção, bloqueio e ocupações → update CAS → história, auditoria, outbox e receipt idempotente, todos na mesma transação. Para evitar ciclo modular, avançar `schedule_revision` usa `AgendaRevisionAdvancer` na API de appointments, implementado pelo adapter availability.
- O próprio appointment é excluído da consulta de ocupações; a exclusion constraint PostgreSQL continua como garantia final. Bloqueios, CLOSED, SPECIAL e regras não ignoráveis continuam efetivos. Overrides não são herdados; exigem conteúdo explícito e motivo novo.
- **Decisão aprovada nesta instrução em 17/10/2026:** nova remarcação exige serviço atualmente ACTIVE no tenant; elegibilidade é consultada separadamente com leitura `FOR SHARE`, enquanto o cálculo continua usando snapshots imutáveis. Serviço inativo não é ignorável. Replay autorizado já concluído não revalida elegibilidade.
- V16 acrescentou campos before/after. V17 substitui a CHECK por expressão booleana estrita (`IS TRUE`), exige limites e versões completos e preserva linhas iniciais válidas. V1–V16 são imutáveis. A migration falha perante linhas legadas incompatíveis, sem correção ou exclusão de dados.

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
| Sobreposição da própria ocupação | `PublicAppointmentIT.reschedulingMayOverlapItsOwnOldOccupiedInterval` | intervalos antigos `[09:00,09:45)` e novos `[09:30,10:15)` se sobrepõem, mesmo ID, versão +1, sem terceira ocupação | comprovado em PostgreSQL/Testcontainers; separado da disputa CAS |
| Concorrência reserva/bloqueio | lock tenant-first `READ_COMMITTED`, CAS e constraint existentes | alvo da remarcação revalidado após lock, conflito preserva intervalo anterior | a classe PostgreSQL/HTTP passou com CAS concorrente same-version e corridas remarcação→bloqueio/bloqueio→remarcação |
| Replay e resposta perdida | repetição da mesma chave/comando em PostgreSQL e browser E2E | receipt original; sem duplicar história, auditoria, outbox, versão ou revisão | replay HTTP após commit e E2E Chrome/Keycloak/PostgreSQL passaram no head `30d88d63e71937695a130506c3b8ac31968d0809`; resposta simulada perdida preservou uma única mutação |
| OIDC/CSRF/tenant | MockMvc autenticado com CSRF; controller administrativo | operação passa pela associação OIDC atual e resolução tenant-scoped; capability pública não é consultada | contrato/código coberto por contexto admin dos testes; browser ainda pendente |
| UI | `AdminAgendaPanel`, testes/lint/build e E2E Keycloak | estados de carregamento/erro, DST offset-aware, retry idêntico e atualização após sucesso | frontend 23 Vitest/lint/typecheck/build passou localmente; E2E browser validou remarcação, persistência/história, conflito com bloqueio preservando horário e draft após reload no head `30d88d63e71937695a130506c3b8ac31968d0809` |
| PostgreSQL/Flyway | `./mvnw spotless:check verify` | V1–V16 imutáveis, V17 fresh/upgrade, validate/rerun e grants | BUILD SUCCESS no código `a3f4d997b1fdb715ec7848d44c35e23508a542b1`: 70 testes unitários/arquiteturais e 129 integrações PostgreSQL 16.13/Testcontainers; V17 fresh, V16→V17, validate/rerun, grants e falha explícita para legado incompatível passaram |
| CHECK do histórico | `PublicAppointmentIT.historyConstraintRejectsUnknownAndInvalidRescheduleCombinations`; `TenantPersistenceIT.v17FailsExplicitlyAndPreservesLegacyRescheduleHistoryWithUnknownCheckResult` | nulos isolados/duplos, operação/estado, intervalos e versões inválidos rejeitados; histórico inicial/remarcação completa válidos; legado incompatível falha sem alteração | passou em PostgreSQL real no verify focado (58 ITs) |
| Elegibilidade do serviço e snapshot | `PublicAppointmentIT.rescheduleRejectsInactiveCurrentServiceWithoutSuccessEffects`; `rescheduleUsesCurrentServiceEligibilityButKeepsOriginalSnapshotAndReplay` | serviço inativo bloqueia nova mutação sem efeitos; snapshot continua original; replay concluído funciona após inativação | passou em PostgreSQL/HTTP no verify focado |
| Identidade da UI durante requisição | Chrome/Keycloak/PostgreSQL em `scripts/dev/test-keycloak-browser.mjs` | duas reservas, POST controlado pendente, tentativas de navegação bloqueadas, perda pós-commit/retry idêntico e sucesso atrasado associado ao alvo | roteiro testado sintaticamente no código `53cb12c6fd91ab822b5e9cea47bc55f1646d7faf`; browser e CI do novo SHA pendentes |
| Revisão independente | até dois agentes em leitura | achados registrados sem substituir aprovação humana | ferramenta indisponível por limite de threads; revisão sequencial do coordenador sem bloqueador identificado, não independente nem aprovação humana |
| CI remoto | App CI e CodeQL push/PR | sucesso no head final publicado | App CI push [39042910603](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39042910603)/PR [39042912754](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39042912754) e CodeQL push [39042910624](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39042910624)/PR [39042912800](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39042912800) passaram no head funcional `30d88d63e71937695a130506c3b8ac31968d0809`, incluindo Testcontainers, imagem, browser E2E, frontend, security, secrets e CodeQL |

## Descobertas e limites

- O pipeline de lembretes não está persistido/implementado. Outbox de remarcação informa uma mudança lógica, não prova invalidação nem envio.
- Prontidão legal/comercial, HTTPS/staging, secrets/rotação, preflight hospedado, scan completo, retenção aprovada, fornecedores reais e integração de restrições AGD-032/033 permanecem pendentes.
- Histórico e resposta nunca expõem capability de recuperação, token, digest ou contexto OIDC.

## Encerramento ou retomada

- A revisão desta retomada partiu do head remoto confirmado `78d356cc2d6361e28dac0ba798afb5b15db1b900` e encontrou quatro achados: identidade da intenção escondida durante POST pendente; CHECK V16 que aceitava UNKNOWN; ausência de elegibilidade atual do serviço; e teste que alegava overlap sem sobreposição real. Correções de backend/migration foram testadas em `a3f4d997b1fdb715ec7848d44c35e23508a542b1`; guard persistente no estado incerto, regressão do browser e gates frontend foram finalizados/testados localmente em `53cb12c6fd91ab822b5e9cea47bc55f1646d7faf`.
- AGD-022 permanece parcial até execução do browser E2E atualizado e CI remoto no novo head; lembretes efetivos seguem dependentes de AGD-030/031. Não será marcada DONE por CI isolado.
- Evidências locais do backend/frontend são do código `23dcb94531af...`; o E2E corrigido foi executado remotamente no head `30d88d63e71937695a130506c3b8ac31968d0809`. PR [#14](https://github.com/LsaBarbosa/kronos_agendamento/pull/14) permanece aberto, sem merge/deploy. Revisão sequencial do coordenador, sem revisores independentes disponíveis nesta sessão e sem inferir aprovação humana.
- Próximo passo após revisão/integração: AGD-023 — Cancelamento e encerramento.
