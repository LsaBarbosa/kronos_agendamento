# AGD-041 — Checkout e webhook financeiro

## Objetivo e origem

- História P0 AGD-041, documento 06; contratos de assinatura/pagamento no documento 03, §§ 12 e 13; estados e fatos financeiros no documento 02, § 7; ADR-0003, ADR-0008 e ADR-0025.
- Implementar a fundação persistida de checkout e admissão autenticada de fatos financeiros sem expor cartão, confiar no navegador ou habilitar um fornecedor/preço não aprovado.
- Base: merge de AGD-040 em `d5bac636310780b699a2c471acbb0c3d50a2a580`; schema anterior V1–V31.

**Atualização do schema:** V32 implementa checkout/inbox; V33 acrescenta o grant mínimo requerido pela ativação financeira de uma assinatura trial existente; V34 exige estado de assinatura válido com expressão `IS TRUE` e falha fechada após preflight somente leitura.

## Escopo

- Porta server-side para catálogo de ofertas aprovado, criação/consulta de checkout e autenticador de eventos do fornecedor.
- Registros tenant-scoped de operação/tentativa e inbox de eventos, com identidade lógica durável, snapshot imutável dos termos, deduplicação, ordenação monotônica do adapter e reconciliação após resultado ambíguo.
- Confirmação de assinatura somente após evento autenticado e correlacionado a uma operação persistida; efeitos locais e histórico numa transação PostgreSQL.
- API e experiência administrativa para consultar ofertas/estado, iniciar checkout quando elegível, acompanhar resultado incerto e atualizar o estado após evidência autoritativa.
- Adapters sintéticos exclusivamente nos testes/E2E. Produção sem catálogo de preço aprovado, transport e autenticador configurados falha fechada e não cria checkout nem aceita evento.

## Exclusões e pendências

- Nenhum fornecedor, sandbox, preço, moeda, credencial, template comercial ou operação financeira real é aprovado por esta história.
- Sem checkout coletando cartão; dados completos de cartão não entram na API, logs, persistência nem telemetria.
- Renovação, tentativas D0/D+3/D+6, inadimplência/tolerância de sete dias, suspensão por falha e cancelamento/reativação fora do efeito inicial pertencem a AGD-042/043. Chargeback e estorno permanecem fatos separados conforme contrato.
- Sem retenção/expurgo novo, cobrança ou consulta real a fornecedor; sem alegar confirmação por redirecionamento do navegador.

## Critérios verificáveis

1. Requisições administrativas identificam o tenant/ator pela associação OIDC atual; payload só informa plano e versão, nunca preço/moeda/tenant/paymentConfirmed.
2. Checkout só começa se existir uma oferta completa carregada do servidor e um provider adapter configurado. Sem qualquer um, não há chamada externa nem operação financeira persistida.
3. Repetição/concor­rência da mesma intenção retorna a operação original; payload incompatível não é mascarado; timeout mantém identidade e resultado incerto, sem nova cobrança cega.
4. Evento não autenticado ou sem correlação persistida não altera assinatura. Evento autenticado é deduplicado por identidade de provedor/evento; sequência mais antiga não reverte fato mais novo.
5. Promoção para estado comercial pago exige evidência autoritativa autenticada, compatibilidade de operação, plano, valor/moeda e tenant derivados do vínculo server-side.
6. Histórico de pagamento/assinatura, auditoria de sucesso, estado e receipt local são atômicos; a falha de escrita reverte todos os efeitos locais.
7. A interface separa operação criada, checkout disponível, confirmação comprovada e resultado incerto; logout/troca de contexto descarta respostas privadas atrasadas.

## Desenho e consistência

- `PaymentOfferCatalog` resolve no servidor plano, preço inteiro em unidade menor, moeda, frequência e versão dos termos. A implementação normal não contém ofertas enquanto produto não aprovar valor/moeda; nenhuma oferta é construída a partir do browser.
- `HostedCheckoutProvider` recebe somente operação/operaçãoId, snapshot de oferta e URL de retorno allowlisted; é chamado fora da transação. A referência/URL de checkout é persistida minimamente e protegida. Dados de cartão permanecem integralmente no checkout hospedado/tokenizador do provider.
- Operação é criada idempotentemente antes da chamada externa. O estado persiste `REQUESTED`, `CHECKOUT_READY`, `OUTCOME_UNKNOWN` ou estado terminal. Retry consulta/reconcilia a mesma identidade; não cria nova operação nem refaz chamada ambígua sem suporte explícito de idempotência/consulta.
- `PaymentWebhookAuthenticator` entende somente o protocolo do provider configurado; não existe HMAC/protocolo genérico aceito em produção. Primeiro autentica/normaliza, depois persiste inbox/dedupe e processa por operação/ref em dados já vinculados no servidor. Nenhum `tenantId` do payload autoriza efeitos.
- Cada evento normalizado carrega sequência/versão monotônica fornecida pelo adapter, identidade do provider event, referência externa e estado financeiro allowlisted. A transação bloqueia a operação/assinatura, insere evento único, ignora sequência já aplicada e atualiza assinatura/histórico/receipt somente se o fato for mais novo e consistente.
- A assinatura `ACTIVE` só pode resultar de pagamento comprovado; sua elegibilidade de reserva usa o status persistido. Estados de cobrança atrasada e tolerância continuam AGD-042/043.
- A confirmação de pagamento grava auditoria minimizada `SUBSCRIPTION_PAYMENT_CONFIRMED` na mesma transação do CAS da assinatura, histórico e `SubscriptionReactivated`; detalhes limitados às versões, sem preço, referência externa ou payload.
- Nenhuma chamada externa dentro de transação; timeout externo permanece explícito e recuperável. Sem ferramenta de reconciliação/idempotência aprovada, não repetir a chamada.

## Contratos/API e interface

- Manter `GET /api/v1/admin/subscription`; acrescentar `POST /api/v1/admin/subscription/checkout`, `GET /api/v1/admin/subscription/checkout/{operationId}`, `GET /api/v1/admin/subscription/payment-attempts` se já coberto pelo contrato, e `POST /api/v1/webhooks/payments/{provider}` somente atrás de autenticador configurado.
- Checkout usa sessão OIDC, associação atual, CSRF, `Idempotency-Key` e versão esperada. Respostas privadas são `no-store`; valores do pedido são exclusivamente do servidor.
- A intenção do navegador é namespaceada pelo UUID de contexto retornado pelo servidor e validada como precondição em checkout/recovery; o servidor compara com a associação autenticada, sem aceitar o cabeçalho como autoridade. Fases são PREPARED/SENDING/UNCERTAIN/KNOWN; só seleção preparada ou rejeição estruturada definitiva pode ser descartada.
- Webhook limita corpo antes da leitura, autentica segundo protocolo real, persiste antes de confirmar sucesso e não revela correlação/tenant. Sem provider configurado, retorna indisponibilidade sem aceitar ou persistir payload.
- React nunca recebe dados de cartão. Em timeout preserva intenção e permite consultar/reconciliar a mesma operação; logout/troca invalida respostas antigas. Retorno do provider é apenas navegação, não confirmação.

## Migração e compatibilidade

- V32 ampliou `subscription_history` para o evento financeiro e criou operações de checkout/inbox; V33 concede UPDATE somente em `subscription.plan_code`/`paid_through_at` ao runtime; V34 substitui a constraint de estado com preflight que não altera dados. V1–V33 permanecem imutáveis.
- Preflight operacional somente leitura antes do upgrade: consultar `subscription` com a expressão de estado de V32 encapsulada em `IS NOT TRUE`; qualquer linha retornada bloqueia a migração e requer investigação, sem reparo automático.
- Constraints/FKs incluem tenant e impedem referência cruzada. Operação e inbox têm grants runtime de SELECT/INSERT/UPDATE necessários; histórico mantém SELECT/INSERT sem UPDATE/DELETE/TRUNCATE.
- Cobrir instalação limpa, upgrade V33→V34, Flyway validate/rerun, unicidade/dedupe, CAS e rollback com PostgreSQL real.

## Estratégia de testes/evidências

- JUnit unitário para canonicalização, transições/ordenação e classificação de resultados.
- PostgreSQL/Testcontainers com transações/conexões distintas: isolamento, mesmo checkout concorrente, replay, rollback, evidência inválida, dedupe, fora de ordem e conflito de valor/moeda/operação.
- Regressões de timeout após aceite, reconciliação sem segunda cobrança lógica, falha após commit e gate comercial na confirmação.
- Frontend Vitest/format/lint/typecheck/build; browser E2E com Keycloak/PostgreSQL e adapter sintético restrito ao cenário de teste para iniciar checkout, resposta incerta/recovery e estado só após webhook autenticado.
- Nenhuma prova local/E2E com adapter será descrita como sandbox ou integração real. CI push/PR em SHA final precisa ter workflows, jobs e check-runs concluídos.

## Provedor: pesquisa e recomendação não vinculante

- Para operação primariamente brasileira, avaliar Mercado Pago Checkout Pro como candidato por fornecer checkout hospedado, fluxo de criação de ordem com chave idempotente e webhooks com verificação de assinatura HMAC documentada oficialmente: [criação de ordem Checkout Pro](https://www.mercadopago.com.br/developers/en/docs/checkout-pro-orders/create-order), [notificações e assinatura](https://www.mercadopago.com.br/developers/en/docs/checkout-pro-orders/notifications?scope=prod).
- Comparar com Stripe Checkout hospedado, idempotência de requests e eventos de webhook: [Checkout hosted](https://docs.stripe.com/payments/checkout/quickstarts), [idempotent requests](https://docs.stripe.com/api/idempotent_requests), [webhooks](https://docs.stripe.com/webhooks).
- Recomendação: manter portas neutras e fazer uma prova sandbox após produto/operação confirmar entidade recebedora, países, liquidação, assinatura recorrente, taxas, moeda/tributação, política de reembolso/chargeback e suporte de consulta/idempotência. A documentação técnica não decide elegibilidade comercial nem aprova fornecedor/preço.

## Progresso

- [x] PR #22 AGD-040 integrado por merge commit `d5bac636310780b699a2c471acbb0c3d50a2a580`; App CI/CodeQL pós-merge completos, incluindo E2E e todos os check-runs (evidência em STATUS/VALIDATION).
- [x] Núcleo checkout/webhook, adapters de produção fail-closed, painel de recuperação e fixtures restritas ao E2E implementados; sem oferta/provider real, a produção não inicia checkout nem admite evento.
- [x] V32 aplicada em instalação limpa e upgrade; `TenantPersistenceIT` (30) e `BillingWebhookIT` (6) passaram em PostgreSQL 16.13/Testcontainers, incluindo concorrência de checkouts; Flyway validate/rerun e grants verificados.
- [x] V33 validada em instalação limpa e upgrade V32→V33; `TenantPersistenceIT` (31) e `BillingWebhookIT` (7) passaram localmente, incluindo confirmação de assinatura TRIALING sob usuário runtime e grants por coluna, Flyway validate/rerun.
- [x] V34, intenção vinculada ao contexto e reconciliação do estado pós-inbox implementados; validação local registrada em `docs/development/VALIDATION.md`; CI desta rodada depende da publicação e execução no SHA final.
- [x] Browser E2E mantém a intenção preparada durante logout enquanto a busca CSRF fica pendente; confirma zero POST e body/chave preservados após autenticação. A resposta do primeiro POST é substituída por HTTP 408 após commit, e retry usa o mesmo contexto/chave/body.
- [x] A primeira execução App CI push no SHA `798570b8a88718d4dbd38e8ec8e2cded174f6ae1` encontrou falha de compilação exclusiva da fixture E2E (`IOException` não lançada). Catch corrigido e compilação Docker `INCLUDE_E2E_FIXTURES=true` passou; aguardando novo CI no SHA corrigido.
- [x] Follow-up P2: o E2E conserva intenção e namespace do tenant, bloqueia descarte durante estado incerto, e verifica 408 após commit/retry idêntico. No head `e24c8f2`, a API retornou 408 simulado como esperado; a espera do E2E usava texto de incerteza pertencente a outro fluxo. Expectativa textual alinhada ao alerta de checkout (`Resultado ainda incerto.`), sem reduzir assertions. O novo head ainda precisa concluir App CI/CodeQL push e PR.
- [x] App CI no SHA `c6e029525e92e2e6ae0160fee6e0e97899aaa134` revelou que o GET pós-reload reconciliava estado, mas descartava o URL hospedado retornado pelo provider. `GetAdminCheckoutOperation` agora inclui o URL recuperado somente para operação autorizada e `CHECKOUT_READY`; testes backend dirigidos passaram. Aguardar E2E no próximo head.
- [x] O App CI PR do mesmo SHA também encontrou corrida no próprio roteiro: aguardava somente o cabeçalho da lista antes de clicar no botão da operação. O E2E agora aguarda o botão exato aparecer; a espera é por estado do DOM, sem atraso arbitrário.
- [x] Em `9aee9d0f8b1bd329f54b94b8a201253053fee62a`, App CI PR alcançou o evento sintético, mas o autenticador respondeu 401; binding por `application-e2e.yml` e leitura direta do ambiente não resolveram a falha. A causa foi identificada depois como privilégio insuficiente no PostgreSQL (SQLState `42501`); a correção efetiva é V33, não a hipótese de binding.
- [x] Browser E2E em CI com V33 no SHA `fa3a846a8ad9fd5f1b7157afb06a0399173cb89a`: App CI push [39670232442](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39670232442) e PR [39670236977](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39670236977), todos os 13 jobs concluídos com sucesso, inclusive `oidc-browser-e2e`; CodeQL push/PR [39670232374](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39670232374)/[39670237020](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39670237020), ambos os analisadores concluídos com sucesso. A execução é sintética, não sandbox financeiro.
- [x] O cenário legado de remarcação falhou em uma execução push anterior, mas passou nos jobs browser push e PR do SHA acima. A causa histórica da divergência continua desconhecida; não foi atribuída à infraestrutura nem explicada pelo rerun verde.
- [ ] E2E browser local — não executado para preservar o Compose compartilhado, que não inclui backend/frontend da aplicação.
- [ ] Preço/moeda/oferta, fornecedor, sandbox, credenciais gerenciadas, homologação HTTPS, prontidão legal/comercial e retenção continuam pendentes.

**Fechamento desta correção:** o SHA funcional `fa3a846a8ad9fd5f1b7157afb06a0399173cb89a` passou App CI push/PR e CodeQL push/PR, com todos os jobs/check-runs concluídos; os App CI executaram também o browser E2E Chrome/Keycloak/PostgreSQL com adapter sintético. A falha histórica de remarcação observada em execução push anterior continua de causa desconhecida apesar dos jobs posteriores verdes. Isso não comprova sandbox, pagamento ou integração real de fornecedor.

### Investigação posterior do 401 (21/10/2026)

No head `260c0466b8ff2fbe4c0d661def9636141b4a4f45`, o log E2E seguro identificou `signature_mismatch`, sem indicar se a origem está na chave efetiva ou nos bytes assinados. O patch atual neutraliza um possível override de ambiente do runner para Compose e registra somente presença/ausência; essa hipótese ainda não foi comprovada. Exigir App CI push e PR concluídos antes de considerar a regressão resolvida.

No SHA `c4bf7111c5af936b3e3c1a721e3a9b297b9e711c`, App CI push/PR [39650076539](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39650076539)/[39650089619](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39650089619) voltou a falhar no webhook sintético com HTTP 401, sem logs do backend no artefato. O delta atual elimina duas fontes de configuração independentes: gera uma chave descartável em `.env.e2e` (ignorado, modo 600), compartilhada entre container e script Node; os logs e2e reportam somente categoria fixa de rejeição, sem valores sensíveis. Isso testa uma hipótese de configuração, não demonstra a causa. Local: `spotless:check`, `SecurityBoundaryTest` 7, `BillingWebhookIT` 6/PostgreSQL, sintaxe JS/shell, Compose config e diff check passaram. Docker build e browser local não executados porque o sandbox nega acesso ao socket. CI push/PR do novo SHA necessário.

O App CI PR no head `45c08280e0a6a561b2f1548f4c0dd0bfe185a51a` alcançou o webhook válido, mas recebeu 401 ([run](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39647739238)); o App CI push falhou antes no cenário legado de remarcação ([run](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39647731871), causa desconhecida). CodeQL push/PR passou ([39647731761](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39647731761), [39647739082](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39647739082)). Binding via `application-e2e.yml` e leitura direta do ambiente foram tentativas que não corrigiram o 401: no head `234c0e8b72c592889f126307cdd42499e64d6a9e`, App CI push/PR [39648968841](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39648968841)/[39648979430](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39648979430) falharam em `oidc-browser-e2e`, embora `verify`, frontend, security, scanners e CodeQL tenham passado. O artefato disponível não inclui log do backend; a causa exata segue indeterminada. Ajuste em validação faz `isConfigured()` do adapter E2E verificar se há segredo de comprimento mínimo, diferenciando falta de configuração (503) de rejeição de assinatura/evento (401), sem expor valor. Novo CI deve determinar se o ambiente estava ausente ou se a assinatura/evento diverge. Sem alterações no protocolo/provider de produção.

**Atualização após CI no head `46f45862823bc26678d81f98325e0afa4e53c19a`:** App CI push/PR [39652680515](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39652680515)/[39652688563](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39652688563) falhou no `oidc-browser-e2e` com HTTP 401 e `signature_mismatch`; verify, frontend, security, secret scans e CodeQL push/PR [39652680546](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39652680546)/[39652688161](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39652688161) passaram. O step não emitiu o aviso de override, então não atribuímos o erro à hipótese de ambiente do host. No próximo head, diagnóstico restrito compara comprimento em bytes e SHA-256 do corpo sintético Node/servidor; nenhuma chave, assinatura ou payload será registrada. Causa permanece desconhecida até o CI produzir essa comparação.

**Atualização do diagnóstico no head `c3e7a7915662203a78ccfb0008a412e18371c2c8`:** App CI push/PR falhou no browser com 401 ([39654969360](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39654969360), [39654979376](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39654979376)); CodeQL e demais jobs passaram ([39654969255](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39654969255), [39654979145](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39654979145)). O dado do servidor com header de 14 caracteres/269 bytes é da prova intencionalmente inválida, não da confirmação de 274 bytes observada no Node. Não há ainda prova de mismatch HMAC na solicitação válida. O próximo delta inclui log de entrada do adapter e resposta de erro sanitizada para distinguir caminhos; causa continua desconhecida.

**Atualização de CI no head `aded5e4a10a3312bd2c496604d28e41ce29a914f`:** App CI push/PR [39656524665](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39656524665)/[39656531510](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39656531510) falhou no browser com `AUTHENTICATION_REQUIRED`; demais jobs e CodeQL push/PR [39656524664](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39656524664)/[39656531934](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39656531934) passaram. A confirmação chegou ao adapter E2E; bytes/hashes Node e servidor coincidem (274 bytes), sem rejeição HMAC. O 401 ainda não tem causa identificada. O diagnóstico atual adiciona marcador de autenticação concluída e trace ID de erro, sem segredo ou payload.

## Evidências locais

**Diagnóstico após `9720081`:** a confirmação válida autentica; o evento não fica na inbox (`0`) e o servlet encaminha uma exceção SQL (`PSQLException`) para error dispatch, que o Spring Security converte em `AUTHENTICATION_REQUIRED` 401. O log atual só acrescenta SQLState e código de erro numérico, sem texto ou dados de evento, para identificar a classe de falha no próximo CI. App CI push/PR falhou no browser; verify e demais jobs passaram; CodeQL push/PR passou. Causa SQL específica ainda não comprovada.

**Novo passo diagnóstico:** o E2E no head `24682a1` mediu zero linhas na inbox para o evento válido, com 401 no path original e sem redirect. A autenticação E2E havia concluído, então a falha ocorre antes do commit. O logger identificou `dispatch=ERROR`, `error_target=true`, `InsufficientAuthenticationException`: o erro de autorização de dispatch mascara uma exceção anterior, cuja classe ainda será registrada em segurança no próximo CI. Localmente Spotless, `SecurityBoundaryTest` e `BillingWebhookIT` com PostgreSQL/Testcontainers passaram. Nenhum gate ou resposta financeira foi relaxado.

**Resultado do diagnóstico no head `24682a100e3550e525177b8e8ef00b52293b2139`:** App CI push [39658832634](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39658832634) e PR [39658841754](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39658841754) falharam somente no browser E2E. CodeQL push/PR [39658832318](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39658832318)/[39658841691](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39658841691) e demais jobs passaram. O request válido foi autenticado, mas retornou 401 no endpoint original sem redirect e a inbox permaneceu vazia (`0`); o fluxo falha antes do commit. Causa ainda desconhecida. Novo log de segurança distingue dispatcher e classe da exceção, sem dados do request.

**Correção do privilégio ausente no head `eb825710187b1102d62258d6b8ee509c791b01e4`:** App CI push/PR [39663873646](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39663873646)/[39663886158](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39663886158) falhou no browser; log sanitizado revelou SQLState `42501`, após autenticação e antes da inbox. `activateSubscription` atualiza `plan_code` e `paid_through_at` junto a `status/version`, mas o grant V30 não cobria as duas colunas financeiras. V33 adiciona UPDATE somente nelas, mantendo datas de trial protegidas. Teste PostgreSQL `paymentConfirmationUpdatesTrialSubscriptionUsingRuntimeRole` confirmou a transição trial→ACTIVE com `agendamento_app`; `TenantPersistenceIT` cobre banco limpo, V32→V33, validate/rerun e privilégios por coluna. Local: Spotless/Maven JDK 21 `BUILD SUCCESS`, 79 unitários/arquiteturais e 38 integrações PostgreSQL 16.13/Testcontainers. A execução CI do SHA de diagnóstico não contém V33; novos checks são necessários. Sem checkout/pagamento real.

**Atualização diagnóstica do head `dada554b45cb524ecd1a54d124251dd620ecf031`:** App CI push [39657662007](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39657662007) e PR [39657671131](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39657671131) falharam no browser, enquanto os demais jobs e CodeQL push/PR [39657662029](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39657662029)/[39657671486](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39657671486) passaram. O evento válido foi autenticado no adapter E2E; o HTTP 401 `AUTHENTICATION_REQUIRED` compartilha seu trace. Para distinguir erro antes/depois da persistência e eventual dispatch, o roteiro agora consulta somente a contagem de inbox para o event ID sintético e registra apenas o path final e flag de redirect. Nada identifica a causa antes do CI seguinte.

| Critério | Procedimento | Resultado observado |
|---|---|---|
| Migration limpa, upgrades e permissões | `TenantPersistenceIT` | Passou: 30 testes; inclui instalação limpa, upgrade até V32, `validate`/rerun e grants. |
| Correlação, dedupe e ordenação de eventos financeiros | `BillingWebhookIT` | Passou: 6 testes em PostgreSQL; confirmação só após evidência autenticada, evento pré-associação reconciliado, duplicata/out-of-order sem efeito duplicado, auditoria/histórico/outbox únicos, isolamento por tenant, rollback atômico e concorrência de checkouts limitada a uma operação aberta. |
| Segurança de rotas | `SecurityBoundaryTest` | Passou: 7 testes após adicionar a rota pública estreita do webhook e manter deny-all no restante. |
| Backend dirigido | Maven/JDK 21, Spotless, `SecurityBoundaryTest`, `TenantPersistenceIT`, `BillingWebhookIT` | `BUILD SUCCESS`; 7 testes unitários/segurança e 36 integrações PostgreSQL, zero falhas. |
| Frontend | `npm run format:check`, `npm run lint`, `npm test -- --run`, `npm run check` | Passou: lint/formato, 23 testes Vitest, TypeScript e build. |
| E2E navegador | `scripts/dev/test-keycloak-browser.mjs` | Sintaxe passou localmente; browser com Keycloak/PostgreSQL será evidenciado pelo job CI, sem operação financeira real. |
