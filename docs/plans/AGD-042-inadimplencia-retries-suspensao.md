# AGD-042 — Inadimplência, retries e suspensão

## Objetivo e origem

Implementar o ciclo tenant-scoped de vencimento, tolerância, tentativas lógicas D0/D+3/D+6 e suspensão, preservando o gate de novas reservas e os dados/compromissos existentes. Requisitos: documento 01 §§12.3–12.4, documento 02 §7, documento 03 §§12–13 e jobs, documento 06 AGD-042; ADR-0003, ADR-0008, ADR-0025 e ADR-0026.

## Estado da base

- PR #23 / AGD-041 integrado por merge commit `5be58533a84e16e07f598b6a5afc786c99bbd15b`.
- App CI pós-merge `39762729059` e CodeQL `39762729070`: todos os jobs/check-runs concluídos com sucesso, inclusive Testcontainers, scan da imagem, browser E2E e os dois analisadores CodeQL.
- Nova branch `agd-042-inadimplencia-retries-suspensao` parte dessa main.
- V1–V34 são imutáveis; primeira migration deste incremento é V35, sujeita à conferência final do remoto.

## Escopo

- Materializar `ACTIVE → PAST_DUE` quando `paidThroughAt` vencer, criar ciclo estável e três tentativas lógicas sem duplicação.
- Reconciliar pagamento confirmado e cancelar apenas tentativas ainda pendentes; eventos antigos/duplicados não rebaixam status.
- Materializar `PAST_DUE → SUSPENDED` por CAS quando a tolerância termina; a elegibilidade pública aplica o limite temporal mesmo com worker atrasado.
- Mostrar estado, vencimento, fim da tolerância e tentativas ao administrador autenticado, tenant-scoped e `no-store`.
- Persistir claims/resultado de transporte com identidade estável; sem integração/provider e autorização de cobrança válidos, nenhuma chamada financeira ocorre.

## Exclusões

Não selecionar/contratar provider, oferta, preço ou moeda; não provisionar segredos; não cobrar, renovar plano anual automaticamente, enviar notificações reais ou fazer deploy. Não alterar acesso a reservas existentes nem abrir a área administrativa de forma irrestrita. Sem política aprovada, não aplicar retenção ou expurgo.

## Modelo e transações previstos

- Ciclo lógico identificado por assinatura e instante de vencimento; tentativas únicas em D0, D+3 e D+6, cada qual com UUID/operationId estável. Retry técnico mantém a identidade da tentativa lógica e o idempotency key externo; resultado ambíguo não libera tentativa subsequente.
- `ACTIVE → PAST_DUE`, ciclo, agenda de tentativas, histórico, auditoria e outbox compartilham uma transação. Pagamento autenticado, cancelamento das tentativas pendentes e transição de assinatura são atômicos. Suspensão CAS, cancelamento do não iniciado, história, auditoria e outbox também são atômicos.
- Chamadas de provider ficam fora de transação. Claims curtos e fencing impedem worker antigo de gravar resultado; estado já iniciado/ambíguo preserva evidência após suspensão ou pagamento concorrente.
- Todas as transições que afetam aceitação de reservas compartilham o lock tenant-scoped utilizado pela agenda. Capturar Clock e reler assinatura depois de adquirir lock. O gate público permite `PAST_DUE` somente durante a tolerância; `SUSPENDED` bloqueia novas reservas, não leituras/cancelamentos autorizados de existentes.
- Identidade externa de tentativa inclui ciclo, sequência e tenant/assinatura no servidor; nenhum dado de cartão, payload bruto ou PII em audit/outbox/logs.

## Decisões ainda abertas e proposta

O backlog fixa sete dias e offsets D0/D+3/D+6, mas não declara igualdade exata no fim da tolerância nem política de recuperação após indisponibilidade. Proposta submetida ao usuário: suspender em `now >= graceEndsAt`; criar as tentativas lógicas previamente com `dueAt` determinístico e, após downtime, percorrer sequências vencidas em ordem, sem paralelismo e sem avançar enquanto uma tentativa estiver `OUTCOME_UNKNOWN`. A resposta é necessária antes de tratar esses limites como decisão aprovada; implementação mantém essa escolha destacada e os critérios temporais como pendentes até confirmação.

Escolha técnica reversível proposta: os offsets são instantes UTC a partir de `paidThroughAt` (D+3 = 72 horas; D+6 = 144 horas), pois o vencimento é um instante e não há zona de cobrança contratada. Isso não muda trial: tolerância de 7 dias começa exclusivamente no vencimento de assinatura paga.

## Critérios verificáveis

1. Vencimento cria um único ciclo `PAST_DUE` e tentativas lógicas D0/D+3/D+6.
2. Pagamento autenticado/duplicado/fora de ordem conclui o ciclo e não duplica efeito nem deixa retry pendente.
3. Transporte sem configuração/autorização não é invocado; falha ambígua conserva identidade e bloqueia tentativa financeira paralela.
4. Suspensão após tolerância usa CAS e fecha apenas novas reservas públicas, mesmo com worker atrasado.
5. Pagamento concorrente, tentativa em voo e suspensão preservam evidência e resolvem estado sem regressão.
6. Consulta administrativa isolada informa datas, política/estado e tentativas minimizadas após reload.
7. PostgreSQL: instalação limpa, upgrade V34→V35, Flyway validate/rerun, constraints e grants mínimos.

## Plano de validação

- Clock controlado nos instantes de vencimento, D0/D+3/D+6 e grace end.
- PostgreSQL/Testcontainers e transações independentes para unicidade, CAS, scheduler concorrente, restart, rollback, pagamento/suspensão e claim em voo.
- Provas do gate por caso de uso/HTTP, incluindo tolerância e suspensão; reservas existentes permanecem consultáveis/canceláveis.
- Frontend: loading/vazio/erro, tenant-context, sem PII e proteção contra resposta atrasada. Browser E2E Keycloak/PostgreSQL e reload do estado da inadimplência.
- Maven/Spotless JDK 21, frontend format/lint/Vitest/typecheck/build, OpenAPI parse, gates de CI e CodeQL.

## Evidências e limitações

Preencher com comandos, resultados, SHA testado e links remotos. Adapters controlados comprovam a composição e o fencing, não provedor, cobrança, liquidação nem webhook real. Retenção, provider, credenciais, sandbox, oferta/preço/moeda, termos e homologação HTTPS/staging seguem pendentes.

## Incremento implementado nesta branch

- V35 persiste o ciclo tenant-scoped e as identidades lógicas D0/D+3/D+6; há CAS para PAST_DUE/suspensão, cancelamento das tentativas ainda não iniciadas no pagamento/suspensão e leitura administrativa limitada. Nenhuma chamada ao provider é feita por esse núcleo.
- Política temporal de igualdade em `graceEndsAt` e catch-up de tentativas vencidas permanece proposta pendente de produto. A implementação usa o limite `now >= graceEndsAt` para o gate/materialização, e agenda sequências UTC em 0/72/144 horas; isso é evidência técnica do comportamento atual, não aprovação da decisão pendente.
- Tentativas exibidas são somente registros lógicos agendados. Ainda não existe worker de cobrança, retry técnico, claim/lease financeiro, reconciliação de transporte, aplicação de autorização de cobrança ou integração com provider. Portanto nenhum critério de cobrança efetiva/resultado ambíguo é declarado concluído.
- Browser E2E local não executado para preservar o Compose compartilhado. No SHA `05be0025bfc3548cf4a143721e3d6e4264af0000`, App CI push [39774534096](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39774534096) e PR [39774668291](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39774668291), CodeQL push [39774534066](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39774534066) e PR [39774668418](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39774668418) concluíram com sucesso; o job browser é sintético, não prova cobrança real.
- Pendem decisão de igualdade/catch-up, provider/sandbox/oferta/preço/moeda, autorização financeira, segredos, operação/homologação, retenção e gates legais/comerciais.
