# AGD-043 — Reativação e renovação

## Identificação e objetivo

- Incremento dependente de AGD-042; branch `agd-043-reativacao-renovacao`, baseada no head `1d434a83ba63b4e247b0ca675c5db1a2b13c600e` do PR #24.
- Tornar a confirmação financeira de renovação parte do ciclo de pagamento já persistido, reativar sem duplicar efeitos e materializar intenção deduplicada para aviso anual sete dias antes.
- Referências: `docs/spec/app-agendamento-01-especificacao-mvp.md` §§12.1, 12.4–12.5; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` §4.7 e transições; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção Assinatura e AGD-041/042; documento 06 AGD-043; ADR-0026/0027.

## Estado e dependências

- AGD-041 já autentica eventos financeiros, registra inbox, correlaciona operações de checkout e atualiza estado/histórico/auditoria/outbox numa transação.
- AGD-042 já fornece ciclo de inadimplência, tentativas lógicas D0/D+3/D+6, identidade externa estável, claim/lease/fencing e autorização local revalidada sob lock. Uma tentativa só é iniciada com autorização e transporte presentes.
- A confirmação de webhook atualmente correlaciona apenas `billing_checkout_operation`; o resultado aceito de uma tentativa Dunning não tem caminho autoritativo de liquidação correlacionado.
- V1–V38 permanecem imutáveis desde os heads publicados. Esta branch acrescenta V39 (extensão monotônica do período e outbox `SubscriptionPeriodExtended`) e V40 (plano comercial imutável por tentativa). V37 é a migration funcional inicial desta branch: vínculo entre attempt e inbox, snapshot contratual imutável por tentativa, intenção anual, preflight de legado e privilégios de correlação mínimos. V38 acrescenta o motivo terminal de período expirado e a disposição explícita de evidência de pagamento vencida.
- Revisão do preflight V37: a tabela contratual é reconstruída somente a partir de `PAYMENT_CONFIRMED` ligado a operação `CONFIRMED`; os termos da tentativa só são copiados quando `recorded_at <= cycle.created_at`. Tentativa já iniciada sem evidência temporal suficiente faz a migração falhar. A migração não usa o contrato corrente como prova retroativa.
- Fornecedor, preço/moeda/oferta, autorização recorrente confiável, credenciais gerenciadas e homologação permanecem ausentes; produção continua fail-closed.

## Escopo

- Correlacionar evento autenticado a uma tentativa Dunning aceita pela referência/provedor/conta, validar snapshot financeiro do servidor, deduplicar/ordenar e aplicar pagamento sob o lock de tenant existente.
- Preservar retry técnico e identidade lógica do attempt; pagamento sela ciclo, cancela só o que ainda não começou e reativa imediatamente sem repetir versão/histórico/auditoria/outbox.
- Restringir renovação automática ao mandato mensal confiável; renovação anual usa porta distinta de autorização correspondente ao período e falha fechada sem essa evidência.
- Materializar intenção anual deduplicada em `paidThroughAt - 168h` UTC. Persistir `SCHEDULED` antes do prazo e promover a `READY` no/apos o instante programado somente enquanto `now < paidThroughAt` e o período for corrente. Em `now >= paidThroughAt`, terminar como `OBSOLETE/PAID_PERIOD_EXPIRED`; mudança de período obsoleta a anterior, sem acumular períodos passados. Sem transporte configurado, não enviar nem registrar entrega.
- Exibir contrato, estado de autorização, intenção anual e tentativas existentes na área administrativa.

## Exclusões

- Nenhum fornecedor, sandbox, oferta, preço, moeda, termos, mandato ou autorização comercial é aprovado; sem cobrança ou notificação real.
- Não criar outra máquina de cobrança: Dunning de AGD-042 continua como autoridade de tentativas e tolerância.
- Sem mudança no TTL, trial, tolerância ou política de AGD-042; sem AGD-044, cobrança manual adicional ou integração WhatsApp.
- Não inferir datas de vigência. O `paidThroughAt` recebido em evento autenticado permanece dado autoritativo do contrato do provedor; a regra comercial de sua âncora/duração e do pagamento antecipado/atrasado continua pendente.

## Critérios verificáveis

| Critério | Evidência esperada |
|---|---|
| Pagamento de ciclo Dunning reativa e fecha retries uma vez | PostgreSQL/Testcontainers: inbox vinculada ao attempt; status/período/histórico/auditoria/outbox e receipt consistentes; reentrega não repete efeitos |
| Eventos antigos/duplicados não sobrescrevem período mais recente | eventos concorrentes/fora de ordem, comparação monotônica de `paidThroughAt` e sequência |
| Mensal só inicia chamada com mandato mensal atual | Clock + locks; autorização revogada/ausente nega antes do transporte |
| Anual não reutiliza mandato mensal | porta específica de autorização anual por período; ausência falha fechada |
| Aviso anual é persistido antes do prazo, promovido na janela e terminalizado na expiração | PostgreSQL/Clock: instante programado, atraso antes do vencimento, igualdade/depois, mudança de período, concorrência e motivo persistido |
| Pagamento confirmado para período já expirado não reabre acesso | PostgreSQL: encerra somente ciclo correlacionado, preserva assinatura sem ACTIVE e não gera `SubscriptionReactivated` |
| UI comunica estado sem afirmar pagamento/entrega | testes React e browser E2E sintético, reload e isolamento do contexto |
| Produção sem integrações continua sem efeitos externos | teste de ausência de transport/verifier e E2E isolado |
| V37–V40 e privilégios são seguros | instalação limpa, V36/V38/V39→V40, Flyway validate/rerun, preflight/grants, extensão monotônica e estados terminais |

## Decisões técnicas e limites

- Um ciclo Dunning existente representa a cobrança lógica associada ao vencimento; tentativas D0/D+3/D+6 não criam uma segunda assinatura nem outro ledger.
- `billing_event_inbox` aceitará vínculo exclusivo com operação de checkout ou attempt Dunning; correspondência exige provider, account, referência, valor e moeda persistidos no servidor. Uma evidência sem vínculo permanece `UNLINKED`; não altera estado.
- O evento confirmado adquire o lock de referência e depois `TenantAgendaLock`, seguido de locks de linha; resultado do transporte usa a mesma ordem `referência → tenant → attempt`, evitando inversão de locks na corrida de suspensão/settlement. Usa `READ_COMMITTED` e sequência monotônica; uma referência de ciclo já concluída não pode regredir a assinatura.
- Pagamento confirmado poderá ser registrado mesmo quando o ciclo foi suspenso, preservando a regra AGD-042 de evidência de chamada já iniciada. Eventos de período não mais recente são registrados como `OLDER` e não alteram estado/período.
- O aviso anual usa uma intenção independente da tabela de notificações de reservas: essa tabela exige destinatário/contrato de appointment e não se aplica à comunicação comercial. Uma tabela mínima de intents não implica transporte nem entrega.
- **Decisão aprovada pelo usuário nesta instrução em 23/10/2026:** agenda UTC `paidThroughAt - 168h`; recuperação atrasada só antes do vencimento e no período corrente; em igualdade/depois, terminal `PAID_PERIOD_EXPIRED`; período diferente obsoleta a intenção anterior, com dedupe por assinatura/período e sem backlog histórico. Não há template ou destinatário/fornecedor aprovado.
- Evidência financeira autenticada cujo novo `paidThroughAt` já venceu encerra apenas seu ciclo correlacionado e é registrada como `EXPIRED_PERIOD`; não reativa nem fecha um ciclo mais recente. A âncora/duração comercial não é calculada localmente.
- Nenhuma transação de banco abrange uma chamada externa. Resultado ambíguo permanece no mesmo attempt e exige reconciliação suportada antes de nova cobrança.
- O snapshot em `billing_subscription_contract` representa os termos correntes para novos ciclos. Ao materializar ciclo, cada tentativa recebe cópia própria e imutável de valor, moeda, `termsVersion` e `offerVersion`; claim/autorização/webhook usam essa cópia mesmo que checkout posterior atualize o contrato corrente.
- A V37 preenche tentativas legadas somente quando há evidência de termos confirmados registrados até a criação do ciclo. Tentativa legada já iniciada sem esse vínculo faz a migration falhar em transação; não há preço inventado nem correção automática. Operação deve inspecionar/reconciliar antes do upgrade.
- O E2E browser prepara uma assinatura anual em fixture isolada e aguarda o worker real do processo de teste materializar a intenção corrente; então verifica a linha `READY`, a projeção administrativa e o reload. Isso prova a composição do worker com banco/UI no ambiente sintético, não pagamento/webhook real, fornecedor ou entrega.

## Decisões de produto pendentes

- Âncora e duração comercial de período mensal/anual, incluindo pagamento antecipado/atrasado. Proposta a deliberar: aceitar somente o `paidThroughAt` autenticado do provedor e não calculá-lo localmente; exige contrato explícito do provedor e aprovação comercial.
- Semântica de mensal/anual quando o pagamento confirmado chega depois de período mais recente: este incremento protege monotonicidade e não substitui período por evidência mais antiga; regras de crédito/prorrogação permanecem pendentes.
- Canal, destinatário, template, fornecedor e operação de reconciliação de envio ambíguo quando houver integração aprovada.
- Autorização anual por renovação e autoridade que pode concedê-la. A porta permanece indisponível; a UI não oferece consentimento simulado.

## Estratégia de execução e testes

1. Acrescentar porta de autorização anual, bloquear uso de mandato mensal para plano anual e adicionar correlação de attempt Dunning em webhook.
2. Criar V37 com preflight, constraints completas, FK tenant-scoped, unicidade, snapshots por attempt e grants mínimos (incluindo revogação de UPDATE amplo herdado na inbox); V38 amplia apenas estados explicáveis e disposição de período expirado.
3. Persistir, promover e terminalizar intents anuais com Clock e lock tenant-scoped; apresentar na consulta administrativa; sem adaptador de entrega em produção.
4. Testar eventos, ordenação, reativação, autorizações, retries, aviso, tenant isolation, rollback e worker races com PostgreSQL real/conexões independentes.
5. Atualizar OpenAPI, especificação, ADR, STATUS, VALIDATION e TRACEABILITY; revisar achados de requisito/segurança independentemente quando houver revisores disponíveis.

## Progresso

### Consolidação de revisão — 23/10/2026

- Checkout novo é bloqueado enquanto qualquer tentativa financeira Dunning da assinatura estiver iniciada, aceita ou sem resultado resolvido, mesmo se seu ciclo foi encerrado. O worker também impede outro ciclo de iniciar cobrança lógica enquanto essa tentativa continuar pendente.
- Checkout e Dunning só ativam/estendem quando o período autenticado ainda está vigente (`paidThroughAt > Clock.instant()`), depois dos locks e da releitura atuais. Evidência vencida fica explicada como `EXPIRED_PERIOD` e não concede acesso. Evidência mais recente pode estender assinatura ativa sem substituir plano/termos correntes nem reduzir o período.
- V40 persiste o plano da tentativa junto aos termos financeiros. Claim, autorização e reconciliação usam esse snapshot. Preflight deriva contrato somente de checkout confirmado com termos coincidentes e attempt somente de snapshot cujo vínculo anteceda o ciclo; legado sem prova falha sem reescrita.
- A linhagem V40 inclui fato `PAYMENT_CONFIRMED` ligado ao checkout que forneceu os termos; contrato corrente não é reconstruído de checkout sem histórico. Alterações futuras passam por trigger que exige operação confirmada e evidência correlacionada ainda `PENDING`; UPDATE runtime foi reduzido às colunas contratuais necessárias. O trigger usa o schema de origem da linha, inclusive em migrações isoladas.
- Checkout e claim Dunning são mutuamente exclusivos sob lock tenant-scoped. O teste usa threads/latches e verifica que apenas um lado persiste uma nova intenção lógica; não é uma verificação sequencial disfarçada de corrida.
- V39 exige correspondência entre `aggregate_version` e versão do payload allowlisted de `SubscriptionPeriodExtended`. A inbox financeira mantém grants somente nas colunas de correlação; nenhum UPDATE amplo de payload foi acrescentado.
- Esta revisão não habilita fonte/autorização financeira, transporte, notificações, oferta, preço ou moeda reais. A imagem Keycloak e seu gate de segurança permanecem intocados.

### Follow-up de revisão final — 23/10/2026

- O revisor de persistência apontou que o preflight V40 podia inferir o plano a partir de checkout confirmado/termos sem exigir o fato histórico correlacionado. V40 foi reforçada para exigir `PAYMENT_CONFIRMED` ligado ao `source_operation_id`, tenant/subscription corretos e timestamp não posterior ao registro do contrato/ciclo. Legado sem prova falha sem alteração.
- O mesmo revisor apontou que o UPDATE amplo de `billing_subscription_contract` permitiria mutar qualquer campo. V40 agora revoga UPDATE de tabela e concede somente colunas contratuais; um trigger exige origem confirmada e evidência autenticada correlacionada. Os testes verificam grant e rejeição de mudança de plano sem evidência.
- O revisor de pagamentos apontou que a antiga prova checkout/Dunning era sequencial. Foi substituída/acrescida regressão concorrente com duas tarefas e latch/barreira para iniciar simultaneamente; testa que só checkout aberto ou claim Dunning pode vencer. Pagamento/suspensão e tentativa iniciada permanecem cobertos.
- Um primeiro verify direcionado revelou o trigger sem resolução de relações no schema de teste; a exceção era capturada como resultado incerto na recuperação de checkout. O trigger passou a qualificar as tabelas com `TG_TABLE_SCHEMA`; a regressão de recuperação de inbox pré-associada e os testes de schema isolado foram repetidos. Outra falha era uma fixture que mutava `plan_code` diretamente; a fixture agora representa revisão contratual por nova operação/evidência sintética e preserva o assert do snapshot antigo.
- A revisão final sequencial confirmou os caminhos de pagamento, ordem de locks, preflight, trigger/grants e testes. A capacidade de subagentes estava esgotada para uma nova rodada independente; as revisões independentes anteriores ocorreram antes destas correções, portanto não são apresentadas como revisão independente do delta final nem como aprovação humana.

- [x] Base remota confirmada: PR #24 draft aberto, head `1d434a83ba63b4e247b0ca675c5db1a2b13c600e`; branch de trabalho derivada desse head.
- [x] Mapeados GAPs: inbox não correlaciona attempts Dunning; a autorização genérica atual pode aceitar plano anual; intenção anual ainda não existe.
- [x] Implementação backend/migration e testes PostgreSQL direcionados, incluindo snapshot por tentativa e grants inbox restritos.
- [x] Interface/admin e roteiro E2E sintético atualizado para aguardar a materialização pelo worker; a assinatura-base segue sendo fixture de teste e não há envio.
- [x] Backend completo com JDK 21 (80 unitários/arquiteturais e 283 integrações PostgreSQL); frontend format/lint/Vitest/typecheck/build; sintaxe do browser E2E.
- [x] Revisão independente em leitura por dois revisores e revisão sequencial final; achados concretos corrigidos.
- [x] Browser E2E no evento PR passou e demonstrou a materialização pelo worker, DOM e reload. No evento push, o mesmo roteiro passou pelo cenário anual, mas o job OIDC atingiu 25 min e foi cancelado antes do marcador final; a causa posterior ao cenário anual permanece desconhecida. App CI/security falhou apenas pelo scan Keycloak conhecido; CodeQL push/PR passou.

## Achados da revisão independente e resolução

- **P1 — validação de pagamento com contrato mutável:** um checkout posterior podia substituir o valor/moeda corrente e fazer evidência autenticada de um attempt já iniciado ser classificada como `MISMATCHED`. Resolvido no mesmo incremento: snapshot financeiro persistido por attempt e usado por claim, autorização e reconciliação; teste PostgreSQL altera o contrato corrente após `ACCEPTED` e comprova evento original `APPLIED`, uma reativação e um único fato histórico.
- **P2 — privilégio de inbox:** grant de tabela `UPDATE` de V32 continuava efetivo apesar do grant parcial de V37. Resolvido em V37 por `REVOKE UPDATE` de tabela seguido de grant somente nas colunas de associação/correlação. `TenantPersistenceIT` comprova `has_table_privilege(..., 'UPDATE') = false`, colunas de correlação permitidas e payload financeiro sem UPDATE.
- **P2 — alcance do browser E2E:** a versão anterior semeava uma intenção; nesta rodada o script foi alterado para aguardar o worker de AGD-042 persistir a intenção do período sintético, depois verificar DOM/reload. A preparação do estado da assinatura ainda é fixture e não prova webhook/renovação real. Nenhum envio é executado.
- **P2 — aviso vencido sem estado terminal:** resolvido nesta rodada. A intenção é persistida antes do prazo, fica `READY` somente antes do fim do período e termina como `OBSOLETE/PAID_PERIOD_EXPIRED` em `now >= paidThroughAt`.
- **P2 — inversão de locks no resultado da tentativa:** resolvido nesta rodada; resultado externo bloqueia primeiro a identidade provider/account/reference, depois o tenant e somente então atualiza attempt, consistente com a reconciliação da inbox.
- **P2 — evidência confirmada com período já expirado:** resolvido nesta rodada sem calcular extensão comercial: o ciclo do attempt correlacionado é encerrado e evidência recebe `EXPIRED_PERIOD`, mas assinatura não é reativada e nenhum evento `SubscriptionReactivated` é produzido.
- Revisões feitas em leitura por dois revisores independentes (requisitos/estados e persistência/segurança). Não substituem aprovação humana no GitHub.

### Follow-up de consolidação — 23/10/2026

- Aplicadas as decisões aprovadas para o aviso anual: UTC `paidThroughAt - 168h`; atraso somente no período corrente antes do vencimento; terminal `PAID_PERIOD_EXPIRED` em `now >= paidThroughAt`; mudança de período obsoleta a intenção anterior e não acumula períodos; tentativa iniciada preserva identidade/evidência e resultado ambíguo não é entrega nem autorização de duplicação. Canal, destinatário, template e fornecedor continuam pendentes.
- V38 adiciona somente estados explicáveis (`PAID_PERIOD_EXPIRED`, `EXPIRED_PERIOD`) e suas constraints; V1–V37 permanecem imutáveis e não há backfill de termos comerciais.
- Revisão leu pagamentos, CAS/locks, autorização, preflight V37, constraints/grants e aviso. Dois revisores independentes apontaram: (a) transição terminal do aviso ausente; (b) inversão entre lock de tenant e atualização da tentativa, além do risco de reativar com período já vencido. As correções estão cobertas por PostgreSQL. Revisões não equivalem a aprovação humana.
- A regressão de resultado concorrente usa ordem `provider/account/reference → tenant → attempt`; pagamento autenticado de período que já venceu liquida somente o próprio ciclo, marca a evidência `EXPIRED_PERIOD` e não altera/reativa assinatura.
- O browser E2E passou a aguardar o worker persistido criar a intenção anual para uma assinatura anual preparada por fixture isolada; verifica `READY`, texto “não enviada” e reload. Isso comprova worker+PostgreSQL+UI no ambiente sintético, não webhook/renovação real nem entrega.
- CI no SHA `943bd3d37e5c9032d936aba10930765800afe6eb`: App CI PR [39933700761](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39933700761) teve verify/frontend/browser E2E/9 secrets verdes e security falhou no scan Keycloak conhecido; App CI push [39933695446](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39933695446) passou verify/frontend/secrets, mas o browser foi cancelado pelo timeout de 25 min depois de logar o cenário anual completo; a causa posterior não foi demonstrada. CodeQL push/PR [39933695468](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39933695468)/[39933700815](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39933700815) passou. O gate não está integralmente verde; não houve rerun.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Correlação de pagamento com renewal attempt | `./mvnw -B -ntp spotless:check '-Dit.test=SubscriptionTrialIT,BillingWebhookIT' verify` | Testcontainers: evento autenticado vincula, liquida e deduplica com termos imutáveis da tentativa | Classes direcionadas passaram: 80 unitários/arquiteturais + 37 integrações PostgreSQL; o caso de contrato atualizado prova um único período/histórico e `APPLIED` |
| Autorização mensal/anual | `SubscriptionTrialIT` com Clock | Mandato mensal distinto da autorização anual; default fail-closed | Cobertura direcionada passou dentro das 23 integrações da classe; porta anual indisponível sem fonte configurada |
| Aviso anual e obsolescência | PostgreSQL/Testcontainers e E2E | Uma intenção por período, sem alegação de envio | `SubscriptionTrialIT` comprova materialização/deduplicação/obsolescência. No browser, a assinatura é fixture isolada, mas o worker do processo de teste cria a intenção; DOM/reload comprovam a composição sintética, não pagamento/webhook ou entrega real |
| Schema V38 | `TenantPersistenceIT` | Fresh, V37→V38, validate/rerun/grants mínimos; V37 falha fechado para legado sem prova histórica | 39 integrações PostgreSQL passaram, incluindo upgrade, validate/rerun, grant por coluna e preflight fail-closed |
| Corridas temporais de AGD-042 | `BillingWebhookIT` | Igualdade e espera do lock não iniciam envio; instante anterior permite uma chamada | 14 integrações passaram; fixtures incluem snapshot/conta/ref e isolam ciclo de outros tenants sem reduzir estado/transport assertions |
| Suíte completa backend | `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify`, JDK 21 | Todos os testes unitários e Testcontainers passam | Passou: 80 unitários/arquiteturais e 283 integrações PostgreSQL; 0 falhas, erros ou skips |
| CI sobre base PR #24 | App CI/CodeQL push e PR | Jobs concluídos; falha de segurança herdada do pin Keycloak explicitamente separada | No SHA `b293ec2`, CodeQL push/PR passou. App CI PR passou verify/frontend/E2E/secrets; push passou nesses jobs exceto E2E, que expirou em duas tentativas seletivas em pontos diferentes sem erro final explicativo. Security push/PR falhou no scan Keycloak herdado. Causa do E2E push permanece indeterminada. O fixture sem `provider_account_id` do SHA inicial foi corrigido. Links em VALIDATION; scan de imagem não aprovado |

## Retomada

Branch `agd-043-reativacao-renovacao` é dependente do PR #24. O PR deve ter base `agd-042-inadimplencia-retries-suspensao`, permanecer draft e conter somente commits AGD-043 acima do head base. Não fazer merge, deploy ou operação externa.

### Evidência remota do head funcional `3ddd3c3ef7b256227ebcbca81f3c0387955e9daa`

- App CI PR [39948517169](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39948517169) e push [39948521812](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39948521812) concluíram todos os jobs. Verify, frontend, browser OIDC E2E e secret scans passaram; o único job falho foi `security`, exclusivamente no scan conhecido da imagem Keycloak.
- CodeQL PR [39948517156](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39948517156) e push [39948521782](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39948521782) passaram com ambos os analisadores; check agregado [120397258903](https://github.com/LsaBarbosa/kronos_agendamento/runs/120397258903) também passou.
- Os E2Es de PR/push passaram em 3m54s/3m58s. São testes isolados com dados sintéticos: observam a intenção persistida pelo worker e a interface após reload; não comprovam cobrança, autorização comercial ou aviso real.
- O commit de atualização desta evidência altera somente documentação; verificar também os workflows que forem disparados para seu SHA.
