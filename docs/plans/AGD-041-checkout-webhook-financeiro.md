# AGD-041 — Checkout e webhook financeiro

## Objetivo e origem

- História P0 AGD-041, documento 06; contratos de assinatura/pagamento no documento 03, §§ 12 e 13; estados e fatos financeiros no documento 02, § 7; ADR-0003, ADR-0008 e ADR-0025.
- Implementar a fundação persistida de checkout e admissão autenticada de fatos financeiros sem expor cartão, confiar no navegador ou habilitar um fornecedor/preço não aprovado.
- Base: merge de AGD-040 em `d5bac636310780b699a2c471acbb0c3d50a2a580`; schema anterior V1–V31.

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
- Webhook limita corpo antes da leitura, autentica segundo protocolo real, persiste antes de confirmar sucesso e não revela correlação/tenant. Sem provider configurado, retorna indisponibilidade sem aceitar ou persistir payload.
- React nunca recebe dados de cartão. Em timeout preserva intenção e permite consultar/reconciliar a mesma operação; logout/troca invalida respostas antigas. Retorno do provider é apenas navegação, não confirmação.

## Migração e compatibilidade

- Próxima versão livre foi V32; V1–V31 permanecem imutáveis. V32 amplia `subscription_history` para o evento financeiro e cria operações de checkout e inbox de eventos.
- Constraints/FKs incluem tenant e impedem referência cruzada. Operação e inbox têm grants runtime de SELECT/INSERT/UPDATE necessários; histórico mantém SELECT/INSERT sem UPDATE/DELETE/TRUNCATE.
- Cobrir instalação limpa, upgrade V31→nova, Flyway validate/rerun, unicidade/dedupe, CAS e rollback com PostgreSQL real.

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
- [ ] Browser E2E em CI do head final — registrar após publicação; o ambiente local compartilhado tem PostgreSQL/Keycloak, mas não backend/frontend da aplicação.
- [ ] Preço/moeda/oferta, fornecedor, sandbox, credenciais gerenciadas, homologação HTTPS, prontidão legal/comercial e retenção continuam pendentes.

## Evidências locais

| Critério | Procedimento | Resultado observado |
|---|---|---|
| Migration limpa, upgrades e permissões | `TenantPersistenceIT` | Passou: 30 testes; inclui instalação limpa, upgrade até V32, `validate`/rerun e grants. |
| Correlação, dedupe e ordenação de eventos financeiros | `BillingWebhookIT` | Passou: 6 testes em PostgreSQL; confirmação só após evidência autenticada, evento pré-associação reconciliado, duplicata/out-of-order sem efeito duplicado, auditoria/histórico/outbox únicos, isolamento por tenant, rollback atômico e concorrência de checkouts limitada a uma operação aberta. |
| Segurança de rotas | `SecurityBoundaryTest` | Passou: 7 testes após adicionar a rota pública estreita do webhook e manter deny-all no restante. |
| Backend dirigido | Maven/JDK 21, Spotless, `SecurityBoundaryTest`, `TenantPersistenceIT`, `BillingWebhookIT` | `BUILD SUCCESS`; 7 testes unitários/segurança e 36 integrações PostgreSQL, zero falhas. |
| Frontend | `npm run format:check`, `npm run lint`, `npm test -- --run`, `npm run check` | Passou: lint/formato, 23 testes Vitest, TypeScript e build. |
| E2E navegador | `scripts/dev/test-keycloak-browser.mjs` | Sintaxe passou localmente; browser com Keycloak/PostgreSQL será evidenciado pelo job CI, sem operação financeira real. |
