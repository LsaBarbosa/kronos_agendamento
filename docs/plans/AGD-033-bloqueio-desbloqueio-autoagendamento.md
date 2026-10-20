# AGD-033 — Bloqueio e desbloqueio de autoagendamento

## Identificação e objetivo

- Implementar a restrição tenant-scoped derivada da política/fatos de AGD-032, proteger novas reservas públicas e permitir desbloqueio administrativo explícito.
- Base integrada: main merge commit `f42118b059b1de4c5c34c14114e057c26ec0e805`; PR #20 (`6b4a788c2d5dc34364115be98d6a56c15bb7d286`) integrado.
- Requisitos: documento 01 §6.4; documento 02 §§4.6/6; documento 03 §8.4 e eventos; documento 06 AGD-032/033 e E2E-06/07. História de reservas e trilha transacional nos documentos 02–04.

## Estado e dependências

- AGD-032 persiste a política opcional 1–5 e os fatos/correções explicáveis; os testes faltantes de paginação/snapshot foram acrescentados nesta continuação.
- A porta `ClientBookingRestrictionQuery` agora usa PostgreSQL. Política `NOT_CONFIGURED` não é convertida em `CLEAR`; restrição persistida é consultada independentemente da política.
- Criação pública e administrativa compartilham `TenantAgendaLock`; operações de reserva usam lock por tenant antes da decisão final. `NO_SHOW` e correção são estados/histórias transacionais com idempotência, auditoria e outbox.
- AGD-024/025 autentica cliente para consulta/cancelamento. Restrição é somente para novo autoagendamento: não revoga acesso à área nem cancelamento próprio. Criação administrativa permanece permitida.
- Questões de produto permanecem abertas em `OPEN-QUESTIONS.md`: liberação quando a contagem cai/expira; política removida com restrição ativa; redução retroativa de limite; e se autoagendamento exige política configurada. Não automatizar reconciliação nem converter estado ausente em `CLEAR` sem decisão.

## Escopo e exclusões

- Avaliação explicável e tenant-scoped de contagem, limite e restrição materializada.
- Materialização atômica quando uma nova falta atinge política configurada; operação de desbloqueio administrativo com motivo, CAS/idempotência e histórico/auditoria/outbox.
- Consulta administrativa e UI de fatos/estado/ação de desbloqueio; negação pública neutra antes de confirmar reserva.
- Fora: cobrança, fornecedores, retenção, envio externo, bloqueio de consulta/cancelamento do cliente e políticas/defaults não aprovados.

## Critérios verificáveis

1. Política ausente é `NOT_CONFIGURED`, não `CLEAR`; fatos continuam consultáveis. Restrição persistida é consultada independentemente da política atual.
2. Ao atingir o limite configurado em uma nova transição `NO_SHOW`, cria no máximo uma restrição ativa por tenant/cliente; reprocessamento do mesmo fato não duplica estado/histórico/efeitos.
3. A decisão persiste limite/versão, contagem, `asOf` e fato causal, sem PII excedente.
4. A criação pública revalida a restrição no servidor sob lock por tenant antes de gravar cliente/reserva; resposta neutra não divulga faltas. Replay de reserva confirmada não cria outra reserva devido a restrição posterior, após revalidar os gates aplicáveis.
5. Reserva administrativa continua possível para cliente restrito, sem desbloquear nem ampliar overrides temporais.
6. Desbloqueio exige associação atual, motivo, versão/chave e grava estado/história/auditoria/receipt/outbox atomicamente. Fatos permanecem.
7. Correção da falta reavalia a contagem; não remove automaticamente restrições ativas nesta implementação. A semântica final de liberação/reconciliação e de política ausente permanece pendente de decisão.
8. E2E com Keycloak/PostgreSQL deve mostrar consulta, bloqueio neutro, desbloqueio e persistência; fixtures continuam restritas ao teste.

## Decisões técnicas e limites transacionais

- Migration funcional V28 adiciona restrições/history com FKs tenant-scoped, uma restrição ativa por cliente/tenant, fato causal único, grants mínimos e sem exclusão runtime; preservar V1–V27.
- Reutilizar o lock por tenant já adquirido por confirmação pública/administrativa, bloqueios e transições; a policy replace deve aderir ao mesmo lock se sua alteração participar da decisão. Não chamar rede dentro da transação.
- O status de restrição é materializado e audível, mas a contagem continua derivada dos fatos sob ADR-0023. Sem decisão de produto, não há job de liberação/reconciliação automática.
- Eventos de domínio/outbox têm payload mínimo (tenant, client, restriction, policy version, count/asOf, operação); motivos livres permanecem somente em fontes autorizadas.
- Sem política configurada não há nova ativação automática. Preservação de bloqueio já ativo após remover política aguarda decisão; não traduzir ausência em liberação.

## Estratégia de teste

- PostgreSQL/Testcontainers: contagem 1–5/ausente, isolamento, dedupe, lock concorrente entre `NO_SHOW` e reserva pública, replay, CAS, rollback, desbloqueio, nova falta e grants.
- Consulta sob correção concorrente, janela móvel, fatos e limites conforme ADR-0023.
- Frontend/Vitest: explicação, motivo obrigatório, estados de conflito/sessão e atualização sem expor fatos na UI pública.
- Browser E2E Keycloak/PostgreSQL: consulta administrativa, mensagem pública neutra, desbloqueio com motivo e persistência após reload; elegibilidade de publicação apenas via fixture de teste restrita.

## Progresso

- [x] Confirmar PR #20 e sincronizar branch a partir de main integrada.
- [x] Completar regressões de paginação (>50, empates) e leitura durante correção concorrente de AGD-032.
- [ ] Obter decisões sobre liberação, remoção/ausência da política, redução retroativa do limite e novas reservas sem política.
- [x] Implementar persistência V28, integração transacional, API/OpenAPI e UI inicial.
- [x] Validar backend, frontend e PostgreSQL; o script browser passa em `node --check`.
- [ ] Executar E2E Chrome/Keycloak/PostgreSQL em CI; manter as decisões de produto como pendências.
- [x] Publicar PR [#21](https://github.com/LsaBarbosa/kronos_agendamento/pull/21) como draft para revisão e resolução das decisões; não integrar nem fazer deploy.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Merge AGD-032 | PR #20, workflows/jobs/check-runs e `main` | Merge commit e CI pós-merge verde | Merge `f42118b059b1de4c5c34c14114e057c26ec0e805`; App CI/CodeQL pós-merge passaram (runs no STATUS) |
| Paginação >50 e timestamps empatados | `PublicAppointmentIT#noShowFactsPaginationWithTimestampTiesHasNoOmissionsOrDuplicates` | 57 fatos, 3 páginas, sem duplicação/omissão, metadados/asOf persistidos no cursor | Passou com JDK 21/PostgreSQL 16.13; corrigida fixture para respeitar `asOf` exclusivo e comparação UUID compatível com ordenação PostgreSQL |
| Snapshot durante correção | `PublicAppointmentIT#noShowFactsReadKeepsRepeatableReadSnapshotDuringConcurrentCorrection` | Duas conexões, repeatable-read e latch; primeira leitura coerente, nova leitura refletindo correção | Passou com JDK 21/PostgreSQL 16.13. Uma transação PostgreSQL mantém snapshot após contagem; correção transacional concorrente confirma; leitura da mesma conexão conserva NO_SHOW/count=1 e GET seguinte mostra COMPLETED/count=0. Não usa sleeps |
| Integração AGD-033 | Maven, frontend e E2E browser | Todos os critérios aplicáveis demonstrados | Pendente |

## Decisões pendentes

- Ver `docs/development/OPEN-QUESTIONS.md`; as escolhas de transição automática alteram autorização/efeito público e não serão inferidas pelo plano.

## Encerramento ou retomada

- Validação local em 20/10/2026: `cd backend && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH && ./mvnw -B -ntp spotless:check verify` passou (`BUILD SUCCESS`, 78 unitários/arquiteturais + 220 integrações PostgreSQL 16.13/Testcontainers), incluindo paginação/snapshot concorrente AGD-032, ativação concorrente, rollback de ativação/desbloqueio, migração e casos AGD-033. `TenantPersistenceIT` foi executado na suíte (27 testes), incluindo instalação limpa, V27→V28, Flyway validate/rerun e privilégios runtime. Frontend `npm run format`, `npm run lint`, `npm test -- --run` (23), `npm run check` passou; sintaxe E2E `node --check scripts/dev/test-keycloak-browser.mjs` passou. O E2E browser real ainda depende do job remoto; não foi executado localmente para preservar Compose compartilhado.
- O trabalho continua parcial; não declarar AGD-033 concluída sem CI final, E2E, decisões resolvidas e evidência operacional aplicável.
- PR novo permanece aberto, sem merge/deploy.
