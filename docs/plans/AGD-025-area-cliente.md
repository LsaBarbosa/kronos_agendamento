# AGD-025 — Área do cliente

## Identificação e objetivo

- Incremento autorizado: área privada do cliente autenticado por desafio AGD-024.
- Referências: documento 01 (acesso e cancelamento), documento 02 (reserva e estados), documento 03 (rotas de cliente), documento 04 (segurança/modularidade) e documento 06 (AGD-025).
- Base integrada: PR #16/AGD-024, merge commit `7abb329dca1170348b2ed104ae7cc7d2569de260`.
- Branch: `agd-025-area-cliente`.

## Escopo

- Listar reservas próprias com cursor tenant/cliente-bound; consultar detalhe próprio.
- Cancelar reserva própria `CONFIRMED` somente até `startsAt - cancellationCutoffHours`, inclusive.
- Restaurar a sessão `AGDCLIENT` após reload, encerrar sessão e limpar o estado privado no frontend.
- Preservar CSRF, `Cache-Control: no-store`, titularidade por tenant + telefone verificados e transação/idempotência/história/auditoria/outbox do cancelamento.

Exclusões: sessão de cliente verificado além do mecanismo existente, busca por telefone, acesso de terceiros, remarcação, cancelamento administrativo, fornecedores, WhatsApp, cobrança, integração comercial e regras automáticas de faltas.

## Estado e dependências

- AGD-024 persiste sessões com `tenant_id`, telefone cifrado, expiração, revogação e vínculo ao challenge; `GET /api/v1/client/session` devolve apenas booleano. TTL/renovação continuam pendentes de decisão e configuração de produção.
- Clientes e reservas já persistem `tenant_id`; telefones são normalizados E.164 e únicos por tenant. Reservas preservam snapshots e a exclusão de ocupação somente considera `CONFIRMED`.
- A política de cancelamento do cliente foi aprovada em ADR-0018: igualdade no cutoff é permitida; não aplicar tolerância adicional. Serviço inativo e restrição de autoagendamento não bloqueiam consulta/cancelamento de reserva existente.
- O contrato existente só documenta `status=future` como filtro. Até decisão adicional, esse é o único valor de filtro temporal; omitido, lista-se o histórico próprio completo. Cursor é opaco e vinculado ao tenant/cliente autenticados.
- Fornecedor/template, configuração de segredos e homologação WhatsApp, HTTPS/staging, retenção, prontidão legal/comercial e scan completo permanecem pendentes.

## Critérios verificáveis

- [x] Todas as leituras e mutações derivam identidade do cookie persistido e vigente; sessão ausente, inválida, expirada ou revogada não acessa recursos.
- [x] Consulta por cursor/filtro retorna somente reservas do telefone e tenant autenticados, em ordenação estável; o cursor preserva a tupla original `(startsAt, appointmentId)` mesmo que a reserva marcadora seja remarcada, e cursor estrangeiro é rejeitado sem revelar registros.
- [x] Detalhe alheio e inexistente são indistinguíveis e não revelam dados.
- [x] Consulta/cancelamento não consultam restrição de autoagendamento; serviço inativo não impede cancelamento. A integração de restrições reais não existe e permanece em AGD-032/033.
- [x] Cancelamento exige reserva própria `CONFIRMED`, versão esperada e chave idempotente; cutoff inclusivo respeita igualdade, antes/depois, sem default ou tolerância.
- [x] Replay revalida sessão/titularidade, devolve o receipt original e não duplica estado, versão, revisão, história, auditoria ou outbox.
- [x] Teste PostgreSQL injeta falha de auditoria e verifica rollback de estado, versão, história, outbox e receipt; retry com a mesma chave após remover a falha executa uma transição única.
- [x] Interface restaura sessão após reload e oferece logout; respostas atrasadas não restauram dados privados. E2E real com Chrome/Keycloak/PostgreSQL no SHA `7b7bad96b4b64d0074cba6b63aed24e8019ffbff` passou push/PR: DOM mostra cancelamento recuperado após resposta perdida e reload; lista atrasada liberada após logout não restaura a área; challenge de outro telefone não é confirmado pela sessão anterior.
- [x] DTOs e UI não persistem nome/telefone/código/cookie em URL, storage ou telemetria; respostas de recursos contêm apenas os dados necessários.

## Estratégia técnica

- Reutilizar `AGDCLIENT` como única autoridade; expor contexto interno de tenant + telefone verificado através de porta do módulo `clientaccess`, sem entregar segredo/cookie ao JavaScript.
- Resolver `client_id` por tenant + telefone na fronteira do módulo `clients`; aplicar ownership em cada query de appointment.
- Consultas usam cursor keyset determinístico `(starts_at, appointment_id)`, páginas limitadas a 20, filtro temporal `status=future` e consultas tenant/client-scoped. O cursor opaco versiona sua fronteira pelo timestamp e ID emitidos e inclui vínculo ao tenant/cliente; a propriedade do marcador é revalidada sem reconstruir a fronteira do estado mutável da reserva. V21 adiciona índice `(tenant_id, client_id, starts_at, appointment_id)` para suportar consulta e ordenação sem leitura ilimitada.
- Cancelamento reutiliza lock de agenda tenant-first e `IdempotencyCoordinator`; revalida sessão antes de replay e aplica política temporal dentro da transação. Estado, versão, revisão, histórico, auditoria, receipt e outbox confirmam juntos.
- Migration V21 adiciona o índice de consulta e amplia constraints de `appointment_history`/`audit_event` para a ação de cancelamento do ator `CLIENT`; V1–V20 permanecem imutáveis.
- Frontend mantém dados somente em memória, verifica `/client/session` após reload, trata 401/403, cancelamento incerto com mesma intenção e limpa dados privados em logout/expiração com proteção contra respostas fora de ordem.

## Verificação

| Critério | Procedimento | Resultado observado |
|---|---|---|
| Instalação/upgrade e grants | `cd backend && ./mvnw -B -ntp spotless:check verify` com PostgreSQL Testcontainers | `BUILD SUCCESS`; V1–V21 aplicadas em instalação limpa; upgrades/validate/rerun e runtime grants cobertos por `TenantPersistenceIT`; V1–V20 intactas. Verify final: 73 testes unitários/arquiteturais + 163 integrações, todos aprovados. |
| API/ownership/cutoff/replay/rollback | `ClientAccessIT` com PostgreSQL real | 22 casos passaram; inclui isolamento por telefone/tenant, cursor, sessão expirada/revogada, CSRF, cutoff antes/igual/depois, replay autorizado e injeção de falha de auditoria com rollback de estado/versão/história/outbox/receipt. |
| Frontend | `npm run format:check && npm run lint && npm test && npm run check` | Format/lint, 23 Vitest, TypeScript e build passaram; `node --check` do E2E passou. |
| Browser E2E com sessão real | App CI `oidc-browser-e2e`, emissor de teste AGD-024, PostgreSQL e Chrome | Head `7b7bad96`: App CI push [39260666437](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39260666437) e PR [39260674294](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39260674294) passaram todos os jobs. Browser comprovou reserva pública persistida, sessão cliente, cancelamento e replay no mesmo alvo/body/chave, DOM/status após reload e efeito único; challenge de outro telefone retorna sessão vinculada falsa sem invalidar a sessão anterior; logout com JSON de lista atrasado limpa dados privados. Falhas de helper anteriores estão registradas; corrigidas sem reduzir assertions. Teste local não executado para preservar Compose compartilhado/configuração incompleta. |
| Revisão | revisão independente em leitura | Revisão sequencial pelo coordenador; criação de revisores independentes bloqueada pelo limite de threads. Não equivale a aprovação humana. |
| CI remoto | App CI e CodeQL em push/PR | Código/E2E `7b7bad96`: App CI push/PR e CodeQL push/PR passaram todos os jobs. Head documental `f6c3f771`: App CI push/PR e CodeQL push/PR também passaram todos os jobs. |

## Decisões/limites

- Não definir TTL ou renovação: preservar a configuração atual e manter produção bloqueada enquanto a decisão pendente em ADR-0019 não estiver aprovada.
- Não confiar no booleano de sessão para acesso; cada recurso valida o cookie no servidor e aplica a titularidade persistida.
- Dados da área e cancelamento não criam sessão administrativa nem sessão/capability de recuperação AGD-016.
- Outbox registra intenção prevista; não comprova envio/invalidação efetiva de lembretes, ainda dependente de AGD-030/031.
- Agentes independentes não estavam disponíveis no início desta rodada por limite de threads; revisão final será feita sequencialmente, salvo liberação de capacidade.

## Retomada

AGD-025 permanece parcial até E2E real no CI e homologação/decisões operacionais aplicáveis. Fixture E2E usa reserva criada pelo fluxo público real e emissor AGD-024 restrito ao ambiente de teste; não prova entrega WhatsApp ou prontidão legal/comercial real.

## Follow-up P2 do PR #17

- Cursor: PostgreSQL/Testcontainers cobre página inicial de 20 entre 21, movimento da reserva marcadora para depois e antes da reserva restante, preservação da fronteira, e rejeição de cursor emitido para outro cliente autenticado. Nenhuma migration nova; V1–V21 permanecem intactas.
- Confirmação de cancelamento: o snapshot (incluindo `expectedVersion`) é capturado ao abrir a confirmação; detalhe atualizado não é substituído por item antigo da lista. O E2E real confirmou no DOM lista v1/detalhe v2 e envio de `expectedVersion=2`; CAS continua rejeitando concorrência sem atualizar silenciosamente a intenção.
- Concorrência cliente/admin: PostgreSQL/Testcontainers força cada comando a pausar no UPDATE sob o lock compartilhado; prova cancelamento do cliente vencendo remarcação e remarcação vencendo cancelamento, com apenas um estado/história/auditoria/outbox confirmado.
- Sequências UI: listagem, paginação, detalhe e refresh usam tokens de sequência separados por operação; `finally` limpa somente a flag da chamada responsável, e logout invalida respostas privadas tardias. Browser E2E real com Chrome/Keycloak/PostgreSQL controla paginação/refresh pendentes, abre detalhes antes de liberar as respostas e confirma no DOM a continuidade da paginação e o encerramento do loading; o cenário de logout com resposta pendente permanece aprovado.
- Validação deste follow-up no SHA `d753ad0680b3b9dbf4962e6fcd90e1103404b956`: App CI push [39268895864](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39268895864) e PR [39268904957](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39268904957), CodeQL push [39268895870](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39268895870) e PR [39268904911](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39268904911) terminaram com todos os jobs verdes, incluindo `oidc-browser-e2e` e os analisadores Java/Kotlin e JavaScript/TypeScript. Testes locais: 73 unitários/arquiteturais + 167 integrações PostgreSQL; frontend format/lint, 23 Vitest, typecheck/build e sintaxe do roteiro. E2E local não executado para preservar o Compose compartilhado; PR #17 aberto, sem merge/deploy.
- Revisão independente: tentativa de delegar dois revisores foi recusada pelo limite de threads do ambiente; revisão final sequencial, sem alegar independência ou aprovação humana.
