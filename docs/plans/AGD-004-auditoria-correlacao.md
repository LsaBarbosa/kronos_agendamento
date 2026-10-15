# AGD-004 — Auditoria e correlação

**Estado:** em andamento · **Branch:** `agd-004-auditoria-correlacao` · **Base:** `81ce55d944c8c09a1a29ab1cb91ed03e5d97f4c5`

**Decisão técnica:** [ADR-0007 — trilha append-only](../decisions/ADR-0007-auditoria-append-only.md).

## Escopo deste incremento

Implementar trilha append-only no PostgreSQL para mutações Tenant já existentes, correlação por `X-Correlation-ID`/`traceId`, contrato interno de consulta por tenant autenticado e separação entre identidade runtime e ownership/migrations. Não criar endpoints/UI de consulta nem declarar cobertura de operações de negócio ainda inexistentes. Login, logout, tentativas negadas e falhas de segurança não são escritos como `COMMITTED` na trilha de mutações. O canal atual registra somente parte dos logins/falhas; logout e negações HTTP não têm cobertura uniforme e permanecem pendentes, sem bloquear a prova transacional das mutações existentes.

## Matriz de eventos

| Operação | Evento | Ator | Tenant | Alvo | Resultado | Dados permitidos | Transação | Evidência esperada |
|---|---|---|---|---|---|---|---|---|
| Criar tenant por caso de uso | `TENANT_CREATED` | `SYSTEM`, `CONTROLLED_TENANT_PROVISIONER` | tenant criado | tenant UUID | `COMMITTED` | somente nomes dos campos alterados | mesma transação da inserção | PostgreSQL: sucesso, rollback e falha de auditoria |
| Renomear tenant | `TENANT_RENAMED` | `ADMIN`, admin_user UUID | contexto autenticado do servidor | tenant UUID | `COMMITTED` | `changed_fields`, versões anterior/nova; sem valores de nome | mesma transação do CAS | PostgreSQL: escopo próprio, ocultação cross-tenant e atomicidade |
| Provisionamento local controlado | `TENANT_CREATED`, `TENANT_ADMIN_LINKED` | `SYSTEM`, `CONTROLLED_DEMO_PROVISIONER` | tenant provisionado | tenant/admin/membership UUID | `COMMITTED` | IDs e campos permitidos, nunca email, segredo ou credencial | uma transação SQL | script reproduzível e verificação no PostgreSQL |
| Login/logout, negação e falha de segurança | canal de segurança existente; sem `COMMITTED` na trilha de mutação | identidade/categoria técnica somente no canal seguro | se confiável | recurso normalizado, se aplicável | resultado de autenticação/autorização, não de commit de negócio | não aplicável | testes existentes de segurança/log sem segredos |
| Outras mutações críticas (agenda, reserva, falta, assinatura, exportação, suporte, retenção) | não implementadas neste incremento | — | — | — | — | — | futura transação dos respectivos casos de uso | critério permanece pendente até cada mutação existir |

## Contrato da trilha

`event_id`, `tenant_id`, `actor_id`/`actor_type`/`actor_ref`, `action`, `target_type`/`target_id`, `result`, `occurred_at` UTC do banco, `recorded_at` UTC do banco, `correlation_id`, `trace_id` e JSONB mínimo de chaves allowlist. Sem serialização genérica de request, entidade, principal OIDC, email, telefone, códigos, tokens, cookies, senhas ou payloads. Na fundação atual correlation e trace usam o mesmo UUID normalizado do filtro HTTP; chamadas fora de HTTP geram UUID.

Sistema/provisionamento controlado não é usuário privilegiado: usa `actor_type=SYSTEM`, `actor_id=NULL` e `actor_ref` fixo enumerado no código/script. A rotina de provisionamento escreve tenant, identidade/vínculo e eventos com a mesma transação.

Consulta é apenas contrato interno: recebe identidade autenticada, deriva `tenant_id` do principal confiável e filtra no SQL. Não há rota HTTP ou tela de auditoria nesta entrega.

## Plano de execução e critérios verificáveis

1. Criar V5 sem alterar V1–V4, com tabela sem FK em registros que possam ser removidos, role owner NOLOGIN e runtime sem UPDATE/DELETE/TRUNCATE/ownership.
2. Gravar evento de sucesso na mesma transação dos casos de uso de criar/renomear Tenant; testes PostgreSQL provam commit, rollback e falha ao inserir auditoria.
3. Provar isolamento da consulta e ausência de detalhes sensíveis; ampliar testes de correlação para limpeza em falha e requisições sucessivas.
4. Fazer provisioning local controlado em transação única e registrar somente dados permitidos.
5. Executar verify/gates locais, revisão independente, publicar PR e acompanhar App CI/CodeQL.
6. Atualizar STATUS, VALIDATION e TRACEABILITY com commit e execução remota.

## Progresso

- [x] 15/10/2026 — V5, papéis runtime/owner e contratos/eventos Tenant implementados; V1–V4 preservadas.
- [x] 15/10/2026 — Testes PostgreSQL reais cobrem fresh/upgrade, isolamento, privilégios, rollback e falha ao gravar auditoria na criação e renomeação; revisão independente executada e achados pertinentes corrigidos.
- [x] Publicar PR #3 (head final `708205aa6db54bd1525173eda24250baabb56370`); App CI e CodeQL push/PR concluíram com sucesso. Review humana/merge seguem pendentes.
- [ ] Resolver cobertura de segurança e retenção conforme decisão de produto/operação; adicionar eventos às operações críticas futuras junto aos seus casos de uso.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Formatação/build/testes de backend | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:check verify` (`backend/`) | Zero falhas; migrations e IT em PostgreSQL real | Passou em 15/10/2026 no commit `d24d53bd0bbdfa63ecc1c29cc6db52a74cd9e149`; 23 unitários/arquitetura + 15 IT, registrado em VALIDATION.md |
| Append-only e privilégios | `AuditTrailIT` em `verify` | Runtime só lê/insere; UPDATE/DELETE/TRUNCATE e membership ao owner falham | Passou sob `agendamento_app` em PostgreSQL 16.13 em 15/10/2026 no commit `d24d53bd0bbdfa63ecc1c29cc6db52a74cd9e149` |
| Atomicidade criação/renomeação | `AuditTrailIT` em `verify` | Commit persiste ambos; rollback ou erro de auditoria não deixa alteração/evento de sucesso isolado | Passou em PostgreSQL 16.13 em 15/10/2026 no commit `d24d53bd0bbdfa63ecc1c29cc6db52a74cd9e149` |
| Correlação e allowlist | `CorrelationIdFilterTest`, `AuditEventTest`, `AuditTrailIT` | Response/MDC/evento compartilham UUID; dados sensíveis não são aceitos em details | Passou localmente em 15/10/2026 no commit `d24d53bd0bbdfa63ecc1c29cc6db52a74cd9e149`; revisão de cobertura de logs HTTP permanece pendente |
| CI remoto — correção de provisioning | [App CI push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38773326787), [App CI PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38773334702), [CodeQL push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38773326727), [CodeQL PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38773334839) | Todos os checks verdes | Quatro execuções `success` em `544c016c1ccbcbac70299af43d551228bd22744e`, incluindo Testcontainers, Trivy, OIDC browser E2E e CodeQL. |
| CI remoto — head com registros finais | [App CI push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38774003394), [App CI PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38774007947), [CodeQL push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38774003424), [CodeQL PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38774007941) | Todos os checks verdes | Quatro execuções `success` em `708205aa6db54bd1525173eda24250baabb56370`. App CI completo inclui Testcontainers, Trivy, browser OIDC e secrets; CodeQL Java/JS. PR #3 aberto, sem review humana registrada. |

## Pendências e limites

- Cobertura das operações de negócio futuras depende de seus casos de uso e deve ser adicionada junto às mutações, nunca por endpoint artificial.
- Retenção, base legal por categoria, legal hold e rotina de descarte exigem decisão explícita; esta história não apaga trilha.
- Donos/superusuários PostgreSQL e processos de migração/backup pertencem a uma fronteira privilegiada separada e podem alterar/remover a tabela; o controle não é prova contra administrador de banco.
- Scan completo de dependências e homologação HTTPS de AGD-001/003 permanecem pendentes.
- Runs iniciais do App CI em `cb0901b` falharam apenas no provisioning do browser E2E: `psql -c` não substituía variáveis `:'...'`. A chamada foi corrigida para SQL via stdin no commit `544c016`; os quatro novos runs de push/PR passaram, sem enfraquecer gates.
