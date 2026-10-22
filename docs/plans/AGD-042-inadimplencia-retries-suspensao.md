# AGD-042 — Inadimplência, retries e suspensão

## Objetivo e origem

Implementar o ciclo tenant-scoped de vencimento, tolerância, tentativas lógicas D0/D+3/D+6 e suspensão, preservando o gate de novas reservas e os dados/compromissos existentes. Requisitos: documento 01 §§12.3–12.4, documento 02 §7, documento 03 §§12–13 e jobs, documento 06 AGD-042; ADR-0003, ADR-0008, ADR-0025 e ADR-0026.

## Estado da base

- PR #23 / AGD-041 integrado por merge commit `5be58533a84e16e07f598b6a5afc786c99bbd15b`.
- App CI pós-merge `39762729059` e CodeQL `39762729070`: todos os jobs/check-runs concluídos com sucesso, inclusive Testcontainers, scan da imagem, browser E2E e os dois analisadores CodeQL.
- Nova branch `agd-042-inadimplencia-retries-suspensao` parte dessa main.
- V1–V35 são imutáveis; V36 acrescenta identidade de transporte estável, marca de início, lease/fencing, retry técnico e motivo de recuperação tardia.

## Escopo

- Materializar `ACTIVE → PAST_DUE` quando `paidThroughAt` vencer, criar ciclo estável e três tentativas lógicas sem duplicação.
- Reconciliar pagamento confirmado e cancelar apenas tentativas ainda pendentes; eventos antigos/duplicados não rebaixam status.
- Materializar `PAST_DUE → SUSPENDED` por CAS quando `now >= graceEndsAt`; a elegibilidade pública aplica o limite temporal mesmo com worker atrasado. A tolerância é exatamente 168 horas desde `paidThroughAt`.
- Mostrar estado, vencimento, fim da tolerância e tentativas ao administrador autenticado, tenant-scoped e `no-store`.
- Expor métricas agregadas de backlog/vencimento/idade, resultado desconhecido, aceite de transporte, recusas, falhas finais, recuperação tardia e tentativas técnicas; sem tenant/PII como labels.
- Processar tentativas lógicas persistidas com claim individual, lease/fencing, resultado/reconciliação e retries técnicos seguros; identidade externa é estável por tentativa lógica. Sem integração/provider e autorização de cobrança válidos, nenhuma chamada financeira ocorre e nenhum transporte é contabilizado como executado.

## Exclusões

Não selecionar/contratar provider, oferta, preço ou moeda; não provisionar segredos; não cobrar, renovar plano anual automaticamente, enviar notificações reais ou fazer deploy. Não alterar acesso a reservas existentes nem abrir a área administrativa de forma irrestrita. Sem política aprovada, não aplicar retenção ou expurgo.

## Modelo e transações previstos

- Ciclo lógico identificado por assinatura e instante de vencimento; tolerância de 168 horas desde `paidThroughAt`; tentativas únicas em D0, D+3 e D+6 nos offsets UTC de 0/72/144 horas, cada qual com UUID/operationId e chave externa estáveis. Retry técnico mantém a identidade da tentativa lógica e a chave externa.
- Recuperação tardia durante tolerância: dentre tentativas lógicas vencidas nunca iniciadas, somente a mais recente permanece elegível; as anteriores são registradas `SKIPPED_LATE_RECOVERY` com motivo. Datas futuras não mudam e não se cria nova tentativa nem novo ciclo. Uma tentativa iniciada/em voo/aceita/ambígua nunca é pulada; resultado desconhecido deve ser reconciliado antes de outra cobrança lógica.
- Em `now >= graceEndsAt`, nenhuma nova tentativa de transporte pode ser autorizada. Chamadas iniciadas anteriormente continuam aptas a persistir evidência/resultado, inclusive pagamento que chegue após suspensão.
- Tentativas são estados do pipeline e não prova de pagamento: `SCHEDULED`, `BLOCKED_CONFIGURATION`, `CLAIMED`, `SUBMITTING`, `RETRYABLE_NO_EFFECT`, `OUTCOME_UNKNOWN`, `RECONCILING`, `ACCEPTED`, `DECLINED`, `FAILED_FINAL`, `SKIPPED_LATE_RECOVERY` e `CANCELLED`. `ACCEPTED` significa aceite do transporte, não liquidação.
- A chamada externa usa uma identidade persistida estável derivada/associada ao `attempt_id`. Falha transitória só libera retry técnico quando a resposta prova ausência de efeito; timeout e crash após autorização tornam a tentativa ambígua e não abrem cobrança paralela. Consulta de reconciliação sem suporte real mantém o estado bloqueado para operação.
- `ACTIVE → PAST_DUE`, ciclo, agenda de tentativas, histórico, auditoria e outbox compartilham uma transação. Pagamento autenticado, cancelamento das tentativas pendentes e transição de assinatura são atômicos. Suspensão CAS, cancelamento do não iniciado, história, auditoria e outbox também são atômicos.
- Chamadas de provider ficam fora de transação. Claim de uma tentativa por vez, lease curta e fencing impedem worker obsoleto de autorizar ou gravar sobre claim novo. Uma claim expirada antes do início pode ser recuperada; lease expirada após início é `OUTCOME_UNKNOWN`, nunca retry cego. Persistir o início da tentativa define a autorização e ocorre na transação sob lock tenant-scoped antes da chamada.
- Antes do início, reler sob o lock agenda/ciclo/assinatura/tentativa/lease e instante; confirmar tolerância vigente, ciclo aberto, plano mensal e autorização recorrente server-side. A autorização não é obtida do navegador nem do checkout. Plano anual não é cobrado sem autorização específica. O adapter de autorização/transporte/reconciliação padrão é fechado e não configurado.
- Todas as transições que afetam aceitação de reservas compartilham o lock tenant-scoped utilizado pela agenda. Capturar Clock e reler assinatura depois de adquirir lock. O gate público permite `PAST_DUE` somente durante a tolerância; `SUSPENDED` bloqueia novas reservas, não leituras/cancelamentos autorizados de existentes.
- Identidade externa de tentativa inclui ciclo, sequência e tenant/assinatura no servidor; nenhum dado de cartão, payload bruto ou PII em audit/outbox/logs.

## Decisões de produto aprovadas nesta instrução

- **Aprovado pelo usuário em 22/10/2026:** tolerância acaba em `now >= graceEndsAt`; sete dias = 168 horas desde o vencimento pago; D0/D+3/D+6 = 0/72/144 horas UTC.
- **Aprovado pelo usuário em 22/10/2026:** após downtime, durante tolerância, somente a tentativa vencida mais recente ainda não iniciada pode ser executada. As anteriores nunca iniciadas são `SKIPPED_LATE_RECOVERY` com motivo explícito. Tentativas futuras mantêm vencimento original; não há novas tentativas/ciclos.
- **Aprovado pelo usuário em 22/10/2026:** tentativa iniciada, em voo ou desconhecida precisa de reconciliação antes da autorização de outra cobrança lógica e nunca pode ser pulada. No fim da tolerância, não iniciar novo transporte; preservar/reconciliar chamadas já iniciadas, mesmo após suspensão.
- Estas decisões não ampliam os 14 dias de trial. Continuam pendentes provider/sandbox, oferta/preço/moeda, autorização recorrente, segredos, retenção, operação/homologação e gates legais/comerciais.

## Critérios verificáveis

1. Vencimento cria um único ciclo `PAST_DUE` e tentativas lógicas D0/D+3/D+6.
2. Pagamento autenticado/duplicado/fora de ordem conclui o ciclo e não duplica efeito nem deixa retry pendente.
3. Transporte sem configuração/autorização não é invocado nem contado como execução; resultados definitivos, sem efeito e ambíguos têm estados distintos; ambiguidade conserva identidade e bloqueia tentativa financeira paralela.
4. Suspensão após tolerância usa CAS e fecha apenas novas reservas públicas, mesmo com worker atrasado.
5. Pagamento concorrente, tentativa em voo e suspensão preservam evidência e resolvem estado sem regressão.
6. Consulta administrativa isolada informa datas, política/estado e tentativas minimizadas após reload.
7. PostgreSQL: instalação limpa, upgrade V34→V35→V36, Flyway validate/rerun, constraints e grants mínimos.
8. Workers concorrentes, lease/fencing/restart, late recovery, retry técnico com a mesma identidade, reconciliação e preservação de resultado de chamada iniciada após suspensão.

## Plano de validação

- Clock controlado nos instantes de vencimento, D0/D+3/D+6 e grace end.
- PostgreSQL/Testcontainers e transações independentes para unicidade, CAS, scheduler concorrente, restart, rollback, pagamento/suspensão e claim em voo.
- Provas do gate por caso de uso/HTTP, incluindo tolerância e suspensão; reservas existentes permanecem consultáveis/canceláveis.
- Frontend: loading/vazio/erro, tenant-context, sem PII e proteção contra resposta atrasada. Browser E2E Keycloak/PostgreSQL e reload do estado da inadimplência.
- Maven/Spotless JDK 21, frontend format/lint/Vitest/typecheck/build, OpenAPI parse, gates de CI e CodeQL.

## Evidências e limitações

Validação local no working tree do head-base `2469a362b51391181b3b972c7263d08756f380f6` mais este incremento (V1–V35 preservadas, V36 nova):

- `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` — `BUILD SUCCESS`, 80 unitários/arquiteturais + 266 integrações PostgreSQL 16.13/Testcontainers. Abrange V36 clean/upgrade, validate/rerun e grants; catch-up, claims/lease/fencing, ausência de adapter, identidade estável, resultado ambíguo, corrida autorização/send com pagamento e suspensão e persistência de resultado em voo após suspensão. Lease expirada é recuperada por outro ciclo de varredura do worker; não se simulou kill/restart de processo.
- `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check '-Dit.test=BillingWebhookIT,SubscriptionTrialIT,TenantPersistenceIT' verify` — `BUILD SUCCESS`, 64 integrações direcionadas.
- Frontend: format, lint, Vitest (23), TypeScript/build; `node --check` do roteiro browser e Prettier OpenAPI passaram. `git diff --check` passou.
- E2E local não executado: Compose local compartilhado/persistente não contém backend/frontend e foi preservado. Novo `oidc-browser-e2e` sintético no GitHub ainda precisa concluir no SHA publicado; isso não é cobrança real.
- A primeira suíte completa detectou SQL inválido na agregação de métricas; foi corrigido e a execução integral posterior passou. A falha antiga do E2E AGD-022 segue com causa desconhecida.

Adapters controlados comprovam composição, não provider, cobrança, liquidação, mandato ou webhook real. Provider/sandbox, credenciais e autorização recorrente reais, oferta/preço/moeda, templates/termos aplicáveis, retenção e homologação HTTPS/staging continuam pendentes. PR #24 permanece draft.

## Incremento implementado nesta branch

- V35 persiste o ciclo tenant-scoped e as identidades lógicas D0/D+3/D+6; há CAS para PAST_DUE/suspensão, cancelamento das tentativas ainda não iniciadas no pagamento/suspensão e leitura administrativa limitada. Nenhuma chamada ao provider é feita por esse núcleo.
- Decisões temporais e de late recovery acima foram aprovadas nesta rodada. O worker reivindica uma tentativa por vez, persiste autorização de envio antes da chamada, mantém chamadas externas fora da transação e grava resultados com fencing; retry técnico usa a mesma identidade externa. Lease expirada antes do início volta a ser elegível; depois do início vira ambígua e exige reconciliação. Pagamento/suspensão impedem novos inícios, sem descartar resultado de chamada que já começou.
- V36 falha explicitamente se o intervalo legado de tolerância não corresponder a 168 horas. Preflight de leitura: `SELECT subscription_id, tenant_id, status, paid_through_at, grace_ends_at FROM agendamento.subscription WHERE status IN ('PAST_DUE','SUSPENDED') AND grace_ends_at IS DISTINCT FROM paid_through_at + INTERVAL '168 hours';` e `SELECT cycle_id, tenant_id, due_at, grace_ends_at FROM agendamento.billing_dunning_cycle WHERE grace_ends_at IS DISTINCT FROM due_at + INTERVAL '168 hours';`. Corrigir dados exige decisão/procedimento separado; a migration não os altera.
- Métricas Micrometer usam agregados globais de estado e atraso (`agd.subscriptions.dunning.*`), sem dimensões tenant, operação, provedor ou PII; valores são atualizados pelo worker e a partir de leitura PostgreSQL.
- A execução financeira real continua bloqueada por ausência de provider, autorização/mandato recorrente confiável, configuração de oferta/segredos e homologação. Adapters de teste/E2E são sintéticos e não comprovam cobrança.
- Browser E2E local não executado para preservar o Compose compartilhado sem backend/frontend. O roteiro do CI usa estado sintético isolado e valida painel após reload; isso não prova provider, autorização recorrente nem cobrança real.
- No SHA documental `4ef72bf0724edcaca5f45deeddd4dfc5a5f27eea`, App CI push e CodeQL push/PR passaram; App CI PR falhou somente no replay E2E da remarcação (409), embora o browser E2E de push no mesmo SHA tenha passado. A falha coincide com a intermitência preexistente de AGD-022, cuja causa permanece desconhecida; não inferir solução nem infraestrutura.
- No SHA `c438d69a06ef52f0b64724af6e7d42ffba158f5d`, App CI push/PR e CodeQL push/PR [39778276422](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39778276422), [39778284802](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39778284802), [39778276374](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39778276374) e [39778284806](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39778284806) concluíram com sucesso, inclusive browser. Esse novo resultado não determina a causa da falha anterior, ainda desconhecida.
- Pendem provider/sandbox/oferta/preço/moeda, autorização financeira real, segredos, operação/homologação, retenção e gates legais/comerciais. A falha intermitente de remarcação AGD-022 segue com causa desconhecida.
