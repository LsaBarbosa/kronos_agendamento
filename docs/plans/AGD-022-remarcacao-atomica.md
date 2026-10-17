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
| Somente CONFIRMED; snapshots mantidos; versão +1 | `PublicAppointmentIT.reschedulingUpdatesSameAppointmentAtomicallyAndReplayDoesNotDuplicateEffects` | PostgreSQL/Testcontainers confirma mesmo ID, versão única +1, snapshot, história e revisão atualizada | passou em `./mvnw -B -ntp spotless:check verify` no commit `a3f4d997b1fdb715ec7848d44c35e23508a542b1`; CI final no head `885bd8c25ea62a8c25566f18b65a54f894580314` também passou |
| Conflito/versão/rollback preservam horário antigo | `PublicAppointmentIT.conflictingReschedulePreservesOriginalIntervalAndCreatesNoSuccessEffects`, `auditFailureRollsBackRescheduledIntervalVersionHistoryOutboxAndRevision` | conflito de destino e falha de auditoria não alteram intervalo, versão ou efeitos de sucesso | classe PostgreSQL/HTTP passou; falha de auditoria também comprova rollback do receipt idempotente |
| Sobreposição da própria ocupação | `PublicAppointmentIT.reschedulingMayOverlapItsOwnOldOccupiedInterval` | intervalos antigos `[09:00,09:45)` e novos `[09:30,10:15)` se sobrepõem, mesmo ID, versão +1, sem terceira ocupação | comprovado em PostgreSQL/Testcontainers; separado da disputa CAS |
| Concorrência reserva/bloqueio | lock tenant-first `READ_COMMITTED`, CAS e constraint existentes | alvo da remarcação revalidado após lock, conflito preserva intervalo anterior | a classe PostgreSQL/HTTP passou com CAS concorrente same-version e corridas remarcação→bloqueio/bloqueio→remarcação |
| Replay e resposta perdida | repetição da mesma chave/comando em PostgreSQL e browser E2E | receipt original; sem duplicar história, auditoria, outbox, versão ou revisão | E2E Chrome/Keycloak/PostgreSQL passou no head `885bd8c25ea62a8c25566f18b65a54f894580314`; resposta simulada perdida preservou uma única mutação |
| OIDC/CSRF/tenant | MockMvc autenticado com CSRF; controller administrativo | operação passa pela associação OIDC atual e resolução tenant-scoped; capability pública não é consultada | contrato/código coberto por contexto administrativo nos testes; o E2E Chrome/Keycloak também validou a operação autenticada |
| UI | `AdminAgendaPanel`, testes/lint/build e E2E Keycloak | estados de carregamento/erro, DST offset-aware, retry idêntico e atualização após sucesso | frontend 23 Vitest/lint/typecheck/build passou localmente; E2E no head `885bd8c25ea62a8c25566f18b65a54f894580314` cobre A/B, navegação bloqueada enquanto envia/incerto, retry pós-commit com alvo/body/chave idênticos, única mutação e sucesso atrasado |
| PostgreSQL/Flyway | `./mvnw spotless:check verify` | V1–V16 imutáveis, V17 fresh/upgrade, validate/rerun e grants | BUILD SUCCESS no código `a3f4d997b1fdb715ec7848d44c35e23508a542b1`: 70 testes unitários/arquiteturais e 129 integrações PostgreSQL 16.13/Testcontainers; V17 fresh, V16→V17, validate/rerun, grants e falha explícita para legado incompatível passaram |
| CHECK do histórico | `PublicAppointmentIT.historyConstraintRejectsUnknownAndInvalidRescheduleCombinations`; `TenantPersistenceIT.v17FailsExplicitlyAndPreservesLegacyRescheduleHistoryWithUnknownCheckResult` | nulos isolados/duplos, operação/estado, intervalos e versões inválidos rejeitados; histórico inicial/remarcação completa válidos; legado incompatível falha sem alteração | passou em PostgreSQL real no verify focado (58 ITs) |
| Elegibilidade do serviço e snapshot | `PublicAppointmentIT.rescheduleRejectsInactiveCurrentServiceWithoutSuccessEffects`; `rescheduleUsesCurrentServiceEligibilityButKeepsOriginalSnapshotAndReplay` | serviço inativo bloqueia nova mutação sem efeitos; snapshot continua original; replay concluído funciona após inativação | passou em PostgreSQL/HTTP no verify focado |
| Identidade da UI durante requisição | Chrome/Keycloak/PostgreSQL em `scripts/dev/test-keycloak-browser.mjs` | duas reservas, POST controlado pendente, tentativas de navegação bloqueadas, perda pós-commit/retry idêntico e sucesso atrasado associado ao alvo | E2E passou no SHA `885bd8c25ea62a8c25566f18b65a54f894580314`; valida A e B simultaneamente visíveis, mantém o alvo correto durante envio/incerteza, confere chave/payload/alvo no retry e verifica resposta bem-sucedida atrasada |
| Revisão independente | até dois agentes em leitura | achados registrados sem substituir aprovação humana | ferramenta indisponível por limite de threads; revisão sequencial do coordenador sem bloqueador identificado, não independente nem aprovação humana |
| CI remoto | App CI e CodeQL push/PR | sucesso no head final publicado | No SHA `885bd8c25ea62a8c25566f18b65a54f894580314`, App CI push [39054182944](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39054182944)/PR [39054187494](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39054187494) e CodeQL push [39054182936](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39054182936)/PR [39054187479](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39054187479) passaram, incluindo Testcontainers, imagem, Chrome/Keycloak/PostgreSQL, frontend, security e secrets |

## Descobertas e limites

- O pipeline de lembretes não está persistido/implementado. Outbox de remarcação informa uma mudança lógica, não prova invalidação nem envio.
- Prontidão legal/comercial, HTTPS/staging, secrets/rotação, preflight hospedado, scan completo, retenção aprovada, fornecedores reais e integração de restrições AGD-032/033 permanecem pendentes.
- Histórico e resposta nunca expõem capability de recuperação, token, digest ou contexto OIDC.

## Encerramento ou retomada

- A revisão desta retomada partiu do head remoto confirmado `78d356cc2d6361e28dac0ba798afb5b15db1b900` e encontrou quatro achados: identidade da intenção escondida durante POST pendente; CHECK V16 que aceitava UNKNOWN; ausência de elegibilidade atual do serviço; e teste que alegava overlap sem sobreposição real. Correções de backend/migration foram testadas em `a3f4d997b1fdb715ec7848d44c35e23508a542b1`; guard/UI, roteiro browser e gates frontend foram validados localmente nos commits seguintes. O E2E/CI integrado passou em `885bd8c25ea62a8c25566f18b65a54f894580314`.
- O E2E intermediário falhou em cabeçalhos de fixture/sincronização e conflito de horários: helper para inputs públicos, espera de detalhe (não apenas texto na linha), revisão CAS desatualizada, alvo A fora da semana exibida/terça fechada e fixture administrativa ocupando 10:00. Cada causa foi corrigida sem enfraquecer assertions; o roteiro completo passou no head `885bd8c25ea62a8c25566f18b65a54f894580314`. O CodeQL em `53cb12c` teve falha transitória de download do Maven Wrapper (HTTP/Wget); execuções posteriores push/PR passaram.
- AGD-022 permanece parcial por dependências operacionais, inclusive lembretes reais de AGD-030/031; não será marcada DONE por CI isolado.
- Evidências locais do backend são do commit `a3f4d997b1fdb715ec7848d44c35e23508a542b1`; frontend e sintaxe do roteiro browser, `53cb12c6fd91ab822b5e9cea47bc55f1646d7faf`; E2E, App CI e CodeQL passaram no head publicado `885bd8c25ea62a8c25566f18b65a54f894580314`. PR [#14](https://github.com/LsaBarbosa/kronos_agendamento/pull/14) permanece aberto, sem merge/deploy. Revisão final sequencial do coordenador; revisores independentes indisponíveis nesta sessão e sem inferir aprovação humana.
- Próximo passo após revisão/integração: AGD-023 — Cancelamento e encerramento.
