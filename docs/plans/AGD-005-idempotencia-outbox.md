# AGD-005 — Idempotência e outbox reutilizáveis

## Identificação e objetivo

- Incremento derivado do PR #3 (AGD-004), branch de origem `agd-004-auditoria-correlacao`, head confirmado `23731b8036861683e0353e18a1b164fe0ce9a47b`. PR #3 foi integrado em `1138395acb4a5f50979461fce3894db60bd5b761`; PR #4 atualizado para base main.
- Objetivo: fornecer idempotência persistida e outbox/consumer reutilizáveis, demonstrados em `RenameTenant`.
- Referências: documentos 01 §3/14; 02 §§11/14/16; 03 §§1/2/8/12; 04 §§7/11/12; documento 05 §22; documento 06 critérios AGD-005 e DoD.
- Inclui migrations V6, chave/digest/operationId, replay com autorização atual, atomização rename+auditoria+outbox+resposta, worker lease/retry/reprocessamento manual interno e consumer local deduplicado.
- Exclui endpoints de reserva/catálogo, publicação real para WhatsApp/cobrança/broker, política de retenção/expurgo e alegação de entrega exatamente uma vez.

## Estado e dependências

- Origem histórica: AGD-005 partiu do head `23731b8` do PR #3. Após o merge de AGD-004, a branch incorporou main sem reescrever histórico e o PR #4 passou a ter base main.
- V1–V5, `RenameTenant`, auditoria append-only e PostgreSQL/Testcontainers existem. O rename é a operação escolhida por validar versão esperada, mutação de domínio e auditoria já implementada, sem inventar endpoint de produto.
- Não há serviços de negócio externos nem destino real configurado; a prova de consumo usa efeito local persistido em PostgreSQL.
- Subagentes independentes não estavam disponíveis nesta sessão; revisão será sequencial com a skill local de leitura.

## Estratégia e decisões

1. Criar V6 sem modificar V1–V5; manter os grants de auditoria append-only e conceder apenas privilégios necessários às tabelas novas.
2. Registrar chave como SHA-256 da chave recebida (a chave/header bruto não é persistido); unicidade é `(tenant_id, command_scope, key_digest)`. O scope inclui versão do comando.
3. Canonicalizar o comando `tenant.rename.v1` depois da validação/normalização por campos fixos, em codificação UTF-8 com comprimento prefixado, e SHA-256 versionado; inclui tenant autenticado, alvo, `expectedVersion` e nome normalizado. Não serializa request, headers ou principal OIDC.
4. Criar o operationId uma vez no servidor. Requisição, correlation ID e operação lógica são identidades distintas. Guardar só resultado JSON allowlisted necessário ao replay; sem cookie/token/header.
5. Inserção da chave, mutação, auditoria, outbox e resultado `COMPLETED` compartilham a transação. Em corrida, a constraint única do PostgreSQL serializa contenders; o segundo espera o commit/rollback e, após commit, reproduz o resultado sem incrementar versão, auditar novamente ou gerar novo event ID. Como o comando síncrono não confirma `IN_PROGRESS` fora da transação, o estado normalmente não é observável; se houver registro `IN_PROGRESS` assíncrono, retornar `OPERATION_IN_PROGRESS` com operationId, nunca executar em paralelo. Falha/rollback local remove também o registro e não fixa a chave para conteúdo futuro; erro determinístico não é armazenado por este comando síncrono.
6. Antes de replay, validar principal atual pela política de segurança por requisição e carregar o alvo sob o tenant do contexto. Digest diferente dá `IDEMPOTENCY_KEY_REUSED`; resultado/estado externo ambíguo não é executado de novo.
7. Outbox é pelo menos uma vez. Worker reivindica um item por vez, imediatamente antes da publicação, em transação curta por `FOR UPDATE SKIP LOCKED`, grava token/lease e incrementa tentativas; publica fora da transação; confirma apenas se token ainda for atual e lease ainda válido. Backoff exponencial limitado e número de tentativas são parâmetros operacionais, não retenção de produto. Timeout/resultado desconhecido vai para `RECONCILIATION_REQUIRED`, não retry automático. Essa transição aceita lease vencido somente enquanto CLAIMED e claim_token ainda forem atuais; após reassunção, o token antigo é rejeitado. Reprocessamento é método interno controlado, com identidade original preservada.
8. Consumer registra `(consumer_name,event_id)` e efeito local na mesma transação; redelivery vira no-op. Falha antes do commit reverte ambos.
9. Sem prazo aprovado de retenção, nenhuma tabela terá job de expurgo. Idempotency records, receipts e outbox terminal permanecem persistidos até política aprovada; questionamento registrado em OPEN-QUESTIONS.md.
10. Adapter externo classifica sucesso, falha retryable, falha terminal e outcome desconhecido. Não há destino real neste incremento; nenhuma mensagem/cobrança é declarada publicada.

## Provas

- PostgreSQL real: migração limpa/upgrade/validate/rerun; chaves equivalentes/divergentes, tenant/scope diferentes, concorrência com conexões, replay após commit, rollback cruzado, injeção de falhas de auditoria/outbox e grants runtime.
- Outbox: lease/crash, reclaim por segundo worker, stale token incapaz de confirmar, backoff/terminal/unknown, chamada externa fora da transação e reprocessamento com a mesma identidade.
- Consumer PostgreSQL: duplicate no-op, erro antes do commit reverte receipt e efeito, redelivery após efeito/antes do ack permanece segura.
- Gates existentes: Spotless/Maven verify (Testcontainers), frontend, `npm audit`, secret scan, scan de imagem e CodeQL; CI remoto por commit entregue.

## Progresso

- [x] 15/10/2026 — PR #3 confirmado aberto e branch AGD-005 criada a partir do head publicado; escolhas do incremento registradas.
- [x] Implementar V6 e serviços idempotency/outbox/consumer; V1–V5 permanecem imutáveis.
- [x] Integrar `RenameTenant` e provar riscos PostgreSQL reais, incluindo transações/conexões independentes.
- [x] Revisar entrega sequencialmente e executar gates locais e remotos.
- [x] Atualizar os registros com SHAs e resultados observados.

## Validação

| Critério | Comando/procedimento | Resultado esperado | Observado |
|---|---|---|---|
| PostgreSQL, migração e invariantes | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:check verify` em `backend/` | Testes reais de V6, concorrência, replay, worker, consumer e grants passam | Passou em 15/10/2026: BUILD SUCCESS; 25 testes unitários/arquiteturais e 29 testes de integração, PostgreSQL 16.13/Testcontainers. |
| Frontend e dependências | `npm run format:check && npm run lint && npm test && npm run check && npm audit --audit-level=high` em `frontend/` | Sem regressão; audit high+ não encontra vulnerabilidades | Passou em 15/10/2026; 2 testes, build Vite e `found 0 vulnerabilities`. |
| App CI e CodeQL | execuções push/PR no SHA publicado | `success`; links associados ao SHA | Passaram em 15/10/2026 no head `c38f55f5be4edd6a355b764f4ce08bf2fe0b0024`: App CI push [38785326767](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38785326767), PR [38785336422](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38785336422), CodeQL push [38785326543](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38785326543), PR [38785336533](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38785336533). Todos os jobs passaram, incluindo verify/Testcontainers, frontend, security/image, secrets e OIDC browser E2E. |

## Pendências e retomada

- Política de retenção de auditoria, idempotência, outbox e receipts, prazos por categoria, tombstones e expurgo precisa de aprovação; nenhum expurgo será implementado.
- WhatsApp, cobrança e broker reais dependem de AGD-024/030/041 e contratos/destinos de fornecedor; integração de teste não comprova tais fornecedores.
- PR #3 integrado em `1138395`; integração de AGD-005 acompanhada no PR #4, com base main e verificação do head corrigido. Autorização do mantenedor na sessão não equivale a review humana registrada no GitHub.
- Após AGD-005, próximo incremento recomendado é AGD-010 — Catálogo de serviços.
