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
- [x] Executar E2E Chrome/Keycloak/PostgreSQL em CI; as decisões de produto permanecem pendentes.
- [x] Publicar PR [#21](https://github.com/LsaBarbosa/kronos_agendamento/pull/21) como draft para revisão e resolução das decisões; não integrar nem fazer deploy.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Merge AGD-032 | PR #20, workflows/jobs/check-runs e `main` | Merge commit e CI pós-merge verde | Merge `f42118b059b1de4c5c34c14114e057c26ec0e805`; App CI/CodeQL pós-merge passaram (runs no STATUS) |
| Paginação >50 e timestamps empatados | `PublicAppointmentIT#noShowFactsPaginationWithTimestampTiesHasNoOmissionsOrDuplicates` | 57 fatos, 3 páginas, sem duplicação/omissão, metadados/asOf persistidos no cursor | Passou com JDK 21/PostgreSQL 16.13; corrigida fixture para respeitar `asOf` exclusivo e comparação UUID compatível com ordenação PostgreSQL |
| Snapshot durante correção | `PublicAppointmentIT#noShowFactsReadKeepsRepeatableReadSnapshotDuringConcurrentCorrection` | Duas conexões, repeatable-read e latch; primeira leitura coerente, nova leitura refletindo correção | Passou com JDK 21/PostgreSQL 16.13. Uma transação PostgreSQL mantém snapshot após contagem; correção transacional concorrente confirma; leitura da mesma conexão conserva NO_SHOW/count=1 e GET seguinte mostra COMPLETED/count=0. Não usa sleeps |
| Integração AGD-033 | Maven, frontend e E2E browser | Fluxo real de consulta, bloqueio neutro e desbloqueio | App CI push/PR no SHA `4490f3fa6b9ecc058d4762b0dd9d66f037f2d03b` passou, incluindo `oidc-browser-e2e`; ver links em STATUS/VALIDATION |

## Decisões pendentes

- Permanecem as quatro decisões em `docs/development/OPEN-QUESTIONS.md`: liberação após correção/envelhecimento; política ausente/removida com restrição ativa; redução retroativa do limite; e permissão de novos agendamentos sem política. A implementação não presume respostas finais.

## Follow-up P2 — associação imutável do desbloqueio

- Base confirmada: PR #21 aberto em draft, head remoto `ef600064dfc23fdbdaeb3fcf49d05a3f7f05de4c`. Alteração sem migration, V1–V28 preservadas.
- Intenção readonly captura `clientId`, `restrictionId`, body e `Idempotency-Key`; envio, retry e receipt usam exclusivamente essa identidade. Guard por ref bloqueia handlers no mesmo turno antes do render; estado React mantém controles desativados durante envio/incerteza. Rejeição definitiva descarta a intenção; resposta de transporte/5xx ou receipt inválido mantém a intenção para recuperação.
- As leituras de fatos são vinculadas à geração e ao cliente atualmente selecionado. Resposta, erro e `finally` de requisição obsoleta não atualizam estado de outro cliente.
- Nova regressão de navegador cria A e B restritos por fluxos controlados, mantém POST A pendente após commit, tenta seleção B, simula perda da resposta, repete URL/body/chave originais, valida receipt e efeitos únicos, libera controles e navega para B; depois libera uma resposta GET atrasada de A e verifica DOM B.
- Local: frontend format/lint, 23 Vitest, TypeScript/Vite build, sintaxe do script E2E e `git diff --check` passaram. E2E local não executado para preservar a stack persistente compartilhada: a inspeção autorizada encontrou somente Keycloak/PostgreSQL ativos, sem backend/frontend E2E; nenhum serviço ou banco foi reconstruído/recriado. Aguarda execução real no App CI com Chrome/Keycloak/PostgreSQL.
- Primeira execução App CI push/PR (`39464533849`, `39464544610`) falhou no wait pela presença do cliente B: busca de `E2E` retornou somente os primeiros 20 resultados, excluindo B. O roteiro agora busca pelo prefixo específico comum a A e B, preservando as asserções. CodeQL push/PR (`39464533599`, `39464544827`) passou. A repetição do E2E e verify após a correção está pendente.
- A segunda execução App CI push/PR em `5f2f27578ada37c9fb5ce47964d98e828bffa214` (`39465289652`, `39465297557`) repetiu a falha porque o prefixo ainda encontrava mais de 20 clientes históricos. CodeQL push/PR (`39465289630`, `39465297426`) passou; verify/frontend/security/secrets também. Fixture corrigida para nomes únicos A/B, com renomeação de A limitada ao banco E2E, e terceira execução pendente.
- A terceira execução (`fac26aae3326753d79ff4f2d783e91a76dd1dad6`; App CI `39466167338`, `39466174536`) ainda falhou na busca B. Investigação do fixture confirmou telefone repetido de visitante anterior, que fazia `findOrCreate` reusar cliente e nome antigo. Agora o telefone de B é exclusivo e a própria resposta API valida os dois IDs/nomes antes das assertions de DOM; quarta execução pendente. CodeQL push/PR (`39466167495`, `39466174666`) passou.
- A quarta execução (`d30b89b8320c377f1801d015b20865aeb130ef4d`; App CI `39466970848`, `39466978591`) passou pela busca A/B e recuperação/DOM de A. DOM de diagnóstico mostrou B selecionado, fatos de B e restrição ativa; o falso timeout vinha do predicado `waitUntil` retornar um elemento DOM não serializado como booleano. CodeQL (`39466970855`, `39466978545`) e demais jobs passaram. O quinto roteiro converte o predicado em booleano e mantém os asserts; validação pendente.
- No SHA `af7649fd53d1ce5c6122a9bcc38d852953f73812`, CodeQL e jobs não E2E passaram; App CI PR alcançou a navegação B, mas o assert constatou que o mock só registrou a primeira chamada: o wrapper era restaurado após simular a resposta perdida. App CI push divergiu antes, expirando numa espera anterior de detalhes da remarcação; causa não determinada, e o PR do mesmo SHA passou do ponto. O ajuste mantém o wrapper até registrar o retry; nova execução pendente.
- No SHA `89c48500dedda8b3ed1892d5892e7f5683e2c225`, CodeQL, verify, frontend, security e secrets passaram. Push e PR capturaram/validaram duas chamadas com mesma intenção e receipt; falharam após reload numa espera de B cujo predicado retornava HTMLElement sem booleano. Corrigido no head seguinte, CI pendente.
- As quatro questões acima não são alteradas. PR #21 permanece draft até CI dessa regressão; sem merge/deploy.

## Encerramento ou retomada
- E2E do SHA `73a2ba3e934a864290a26ce644438a484105eba9` provou o P2 e detectou ainda que a correção pendente de falta permanecia bloqueada mesmo no botão de retry. A UI agora libera exclusivamente o retry correspondente a `uncertainId`; format/lint, 23 Vitest, TypeScript/build e sintaxe E2E passaram localmente. E2E não foi executado localmente porque backend/frontend do app não estavam ativos; repetir no CI do head corrigido.
- No SHA `34686ad4ef59c4c4af680da21c90b7bb41574af6`, E2E confirmou o P2 do desbloqueio e falhou depois, aguardando o fato visual da correção de falta existente. O novo roteiro registra estado DOM/DB caso persista; CodeQL e demais jobs passaram.
- No SHA `275412f19c85fa44c248147dd045be5829c3c018`, E2E passou pelo caso P2 completo (A/retry/receipt/efeito único, B selecionado e fatos B preservados após GET tardio de A). Falha posterior foi o fluxo de correção já existente não voltar à seleção A. Roteiro agora navega de volta a A antes; novo CI pendente.
- No SHA `156d1cb231ba35cd4c3d4ad046692a55d9fe5b9f`, os E2E falharam na expressão de liberação da leitura atrasada: `await` não estava em função async no `Runtime.evaluate`. CodeQL, verify, frontend, security e secrets passaram; corrigido com IIFE async, aguardando CI.

- No SHA `07e4246c56a0e797abde4e174147edd65345a698`, App CI push/PR (`39470716093`, `39470725028`) validou retry/receipt e falhou na espera da resposta tardia A porque a condição retornava um elemento DOM em vez de booleano. CodeQL, verify, frontend, security e secrets passaram. Predicado corrigido no head seguinte; CI pendente.

- Validação local em 20/10/2026: `cd backend && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH && ./mvnw -B -ntp spotless:check verify` passou (`BUILD SUCCESS`, 78 unitários/arquiteturais + 220 integrações PostgreSQL 16.13/Testcontainers), incluindo paginação/snapshot concorrente AGD-032, ativação concorrente, rollback de ativação/desbloqueio, migração e casos AGD-033. `TenantPersistenceIT` (27 testes) cobriu instalação limpa, V27→V28, Flyway validate/rerun e privilégios runtime. Frontend `npm run format`, `npm run lint`, `npm test -- --run` (23), `npm run check` passou; sintaxe E2E passou. Após a regressão de 404 definitivo, format/lint/23 Vitest/TypeScript/build e sintaxe E2E passaram novamente. O E2E não foi executado localmente para preservar o Compose compartilhado.
- Browser E2E real com PostgreSQL, Keycloak e Chrome passou em App CI push [39449251503](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449251503) e PR [39449260846](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449260846), no SHA `4490f3fa6b9ecc058d4762b0dd9d66f037f2d03b`. A jornada confirmou consulta explicável, bloqueio público neutro, ausência de nova reserva, desbloqueio com motivo após resposta perdida, retry da mesma intenção, receipt/efeitos únicos e DOM persistido após reload.
- Correções do roteiro: no SHA `6a4dfa4`, foi encontrado CTA esperado depois de a ação explícita anterior já ter encerrado a intenção; no `b5831f8`, a recusa HTTP 404 de nova reserva restrita deixava uma intenção sem receipt no navegador e o E2E expirava ao voltar à página pública. A UI agora limpa somente a intenção dessa rejeição definitiva, preserva uma intenção concorrente de outra aba e mantém 404 recebido ao recuperar uma operação incerta no estado incerto. O E2E verifica que a chave foi removida após a rejeição e Vitest diferencia 404 definitivo de 503 ambíguo.
- No SHA funcional validado, App CI push/PR [39449251503](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449251503)/[39449260846](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449260846) e CodeQL push/PR [39449251435](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449251435)/[39449260569](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39449260569) concluíram todos os jobs/check-runs com sucesso. O commit documental `b4fbac4374f329198f05f648c6d17e92271a93a3` também passou App CI/CodeQL push e PR, com todos os jobs/check-runs concluídos.
- O trabalho continua parcial: quatro decisões de produto e gates legais/comerciais/operacionais permanecem abertos; não declarar prontidão de produção.
- PR novo permanece aberto, sem merge/deploy.
