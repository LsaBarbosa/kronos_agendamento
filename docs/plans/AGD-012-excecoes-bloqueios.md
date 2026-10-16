# AGD-012 — Exceções e bloqueios

## Identificação e objetivo

- História P0, tamanho M, backlog do documento 06; incremento autorizado na branch `agd-012-excecoes-bloqueios`, base `4ce3cb5871ae89d026ebf61eb77534ff89742cad`.
- Objetivo: permitir administrar exceções por data e períodos de bloqueio sem ocultar conflitos com reservas confirmadas, invalidando a disponibilidade derivada e preservando auditoria.
- Fontes: `docs/spec/app-agendamento-01-especificacao-mvp.md` §5.3; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` entidades `ScheduleException`/`BlockedPeriod` e §12; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção “Grade, exceções e bloqueios” e evento `ScheduleChanged`; `docs/spec/app-agendamento-04-arquitetura-implementacao.md` §§4, 8–10; critérios AGD-012 em `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`.
- Escopo: CRUD tenant-scoped de exceções; criação/consulta/cancelamento lógico de bloqueios com motivo; OIDC/CSRF, CAS, replay idempotente, auditoria e `ScheduleChanged`; UI administrativa e testes PostgreSQL/HTTP/browser.
- Exclusões: motor/consulta de disponibilidade AGD-013, reservas/remarcação/cancelamento, publicação pública, notificações, calendário externo, bloqueio de cliente e qualquer alteração da grade semanal AGD-011.

## Estado e dependências

- AGD-011 fornece grade semanal, timezone IANA do tenant, OIDC, autorização tenant-scoped, CSRF, CAS, idempotência, auditoria transacional e outbox; PR #6 foi integrado e CI passou. AGD-012 está sendo implementada nesta branch.
- `origin/main` foi buscada e confirmou `4ce3cb5871ae89d026ebf61eb77534ff89742cad`; branch criada sobre esse SHA. V10 confirmada como próxima migration; preservar V1–V9.
- A inspeção confirmou que appointments/reservas persistidas, cache de disponibilidade e consumer de invalidação não existem. O módulo appointments contém somente `TimeInterval`; `ScheduleChanged` já é persistido na outbox.
- Autorização deve reutilizar a associação ativa e o papel já existente `TENANT_ADMIN`; ator, tenant e timezone vêm do contexto autenticado/servidor, não do payload.

## Critérios de aceite verificáveis

- [x] Para uma data, administrador pode definir fechado ou substituir grade por horários especiais; leitura diferencia ausência de exceção da exceção explicitamente fechada.
- [x] Administrador pode criar e cancelar logicamente bloqueios, sempre exigindo e preservando motivo conforme contrato aprovado.
- [ ] Bloqueio que intersecta reserva confirmada é recusado por uma fonte integrada; prova real e concorrente aguarda AGD-015. Sem resolução automática.
- [x] Leitura e mutação respeitam tenant, associação atual, OIDC, CSRF e política não reveladora para objetos alheios/inexistentes.
- [x] Atualizações concorrentes usam CAS/versionamento; comando repetido é idempotente; autorização atual é revalidada antes de replay.
- [x] Mutação, resultado idempotente, auditoria de sucesso e `ScheduleChanged` previsto compartilham transação. Falha em gravações obrigatórias reverte a alteração.
- [ ] `ScheduleChanged` é persistido na transação; invalidação real aguarda cache/consumer de AGD-013. Não declarar invalidação com base apenas na outbox.
- [x] UI autenticada lista/edita exceções e bloqueios, apresenta motivo/conflito e confirma estado persistido após reload.
- [x] V10 instala schema em banco vazio e atualiza V9; `validate`/rerun e privilégios runtime passam em PostgreSQL real.

## Decisões de produto e contrato

- Decisão explícita do usuário em 16/10/2026 registrada em ADR-0011 e nos documentos 01, 02, 03, 06 e `OPEN-QUESTIONS.md`: ausência mantém grade semanal; `CLOSED` fecha; `SPECIAL` substitui pela lista final que pode conter períodos mantidos e adicionais; remoção restaura a grade.
- Horários especiais: limites locais múltiplos de 15 independentes do slot, `[início,fim)`, adjacência permitida, overlap rejeitado, início<fim e sem meia-noite. `CLOSED` exige lista vazia e `SPECIAL` não vazia.
- Limite local inexistente ou ambíguo sem offset é rejeitado. Offset explícito pode selecionar ocorrência somente se válido na zona IANA do tenant. UI usa horário local e não escolhe offset.
- Bloqueios podem cruzar datas e transições de offset, persistem instantes UTC + zona, e exigem motivo na criação e cancelamento. Cancelamento lógico preserva ID, motivos, timestamp e versão incrementada. Não há restrição genérica entre bloqueios.
- Exceção única por tenant/data; PATCH pode alterar a data sujeita à unicidade. Listagens exigem intervalo local inclusivo máximo de 366 datas. Motivo da exceção é opcional; nenhum motivo livre entra em auditoria/outbox.
- Porta de conflito consulta reservas confirmadas. Sem appointments persistidos, adapter temporário documenta que não há fonte ainda; testes com adapter controlado são prova da infraestrutura, não da integração nem da corrida. AGD-015 precisa de proteção compartilhada no PostgreSQL/transação.
- `ScheduleChanged` usa payload allowlisted `{tenantId,version}`. Cache/consumer não existem: AGD-012 grava o evento atomicamente, mas invalidação real fica para AGD-013.
- O resultado idempotente conserva os campos necessários para reproduzir a resposta original, incluindo motivo de bloqueio/cancelamento; motivos não são copiados para auditoria/outbox. Retenção desses registros permanece sem política aprovada e não há expurgo automático.
- Retenção continua pendente; nenhum expurgo automático.

## Estratégia

1. Registrar decisões aprovadas em especificação, ADR-0011 e OpenAPI; obter timezone e associação autenticada do servidor.
2. Implementar domínio temporal de exceções e bloqueios com resolução `ZoneRules`; local inexistente/ambíguo sem offset inválido.
3. Criar V10 com integridade tenant/date, períodos especiais, instantes UTC, índices e grants mínimos; estender auditoria allowlist sem alterar V1–V9.
4. Implementar adapters JDBC, CAS, idempotência canônica, códigos de erro, auditoria e `ScheduleChanged` transacionais.
5. Preparar somente a porta de conflito de reserva; não criar tabelas/endpoints ou cache artificiais.
6. Entregar API/OpenAPI e UI para consultas, edição/restauração da exceção e criação/cancelamento de bloqueio com mensagens DST e recuperação CAS.
7. Provar migrations, grants, PostgreSQL concorrente, isolamento HTTP, replay/rollback e browser real com Keycloak/Postgres/Chrome.
8. Revisar com até dois agentes em leitura, corrigir achados, rodar gates e publicar branch/PR. Não fazer merge/deploy.

## Estratégia de migration e compatibilidade

- Migration V10 confirmada como próxima. Criar `schedule_exception`, `schedule_exception_period` e `blocked_period`; preservar V1–V9/checksums.
- FK tenant/data e unique `(tenant_id, local_date)`; FK composta tenant/exception dos períodos; exclusion GiST por exceção para impedir overlap; CHECK de quarter-hour e start<end.
- Bloqueios guardam UTC em `timestamptz`, `time_zone`, status/version, motivo original e cancelamento; índices de busca por tenant/instantes. Sem exclusion constraint entre bloqueios.
- Sem backfill presumido: não há evidência de dados antigos dessas entidades. Migração deve criar schema funcional, grants mínimos runtime e manter auditoria append-only.

## Validação e evidências esperadas

| Critério | Verificação | Evidência esperada |
|---|---|---|
| Regras temporais e estado | testes unitários de domínio | fechado vs especial vs ausente; limites; DST conforme decisão |
| Persistência/concorrência | PostgreSQL real/Testcontainers com conexões independentes | migration fresh/upgrade, CAS, unicidade e integridade concorrente |
| Conflito com reserva | porta + adapter de teste; futura AGD-015 | teste do ramo de rejeição/rollback apenas; integração e concorrência reais pendentes |
| Segurança/isolation | testes HTTP autenticados com duas associações/tenants | permitido no próprio tenant, rejeição não reveladora para tenant alheio, CSRF e associação revogada |
| Atomicidade | falhar auditoria/outbox deliberadamente | rollback de alteração, resultado idempotente e evento |
| Cache/evento | outbox existente | `ScheduleChanged` transacional; invalidação de cache pendente, pois não há cache/consumer atual |
| Interface | browser E2E com Keycloak real e PostgreSQL | criar/editar/fechar data, criar/cancelar bloqueio, reload e conflito visível |
| Gates | Maven, frontend, scanners, App CI e CodeQL | resultados ligados ao SHA publicado; homologação separada |

## Progresso e retomada

- [x] 16/10/2026 — main atualizada em `4ce3cb5871ae89d026ebf61eb77534ff89742cad`, branch própria criada e V10 confirmada.
- [x] 16/10/2026 — decisões temporais registradas em requisitos, contrato, ADR-0011 e `OPEN-QUESTIONS.md`.
- [x] 16/10/2026 — commit funcional `5dbd1a30b7c35efe9b2928a9e7ab7e647f890b23`: implementação backend/API/UI e V10; gates locais `spotless:check verify` passaram (36 unitários/arquiteturais + 64 integrações PostgreSQL), frontend (4 testes), OpenAPI e browser E2E OIDC real; resultados em `docs/development/VALIDATION.md`.
- [x] Publicado no PR [#7](https://github.com/LsaBarbosa/kronos_agendamento/pull/7); App CI e CodeQL push/PR passaram no head `969729b9ac4f455d0dd7c9a4ec7090590bb4a055` (links em `docs/development/VALIDATION.md`).
- [x] Revisões independentes em leitura concluídas; ajustes de OpenAPI/DTO, edição de data fora do intervalo, visualização local com zona e assertion de rollback idempotente foram aplicados. Pareceres não equivalem a aprovação humana.
- [ ] Prova de bloqueio concorrente com reservas e invalidação real de cache continuam indisponíveis por dependência de AGD-015/013; retenção de resultados idempotentes ainda depende de decisão.
- [ ] Integração concorrente com reservas, invalidação real, homologação, scan completo, retenção e integrações reais permanecem pendentes.

## Encerramento ou retomada

AGD-012 permanece parcial até comprovar os critérios aplicáveis. A porta de reserva não comprova conflito real nem concorrência, que dependem de AGD-015; `ScheduleChanged` não comprova invalidação de cache, que depende de AGD-013. Não habilitar rotas públicas nem implementar motor de disponibilidade nesta história.
