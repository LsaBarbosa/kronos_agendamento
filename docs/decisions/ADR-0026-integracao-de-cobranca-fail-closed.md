# ADR-0026 — Integração financeira por portas e fail-closed

- **Status:** decisão técnica do incremento AGD-041; provedor, sandbox, oferta/preço/moeda e operação real continuam pendentes.
- **Data:** 21/10/2026.
- **Referências:** AGD-041, documentos 01 §12, 02 §7, 03 §§12–13 e 06; ADR-0003, ADR-0008 e ADR-0025.

## Contexto

Os contratos exigem checkout sem dados completos de cartão na plataforma, pagamento confirmado somente por evidência autoritativa, deduplicação de eventos e proteção contra eventos fora de ordem. A base apresenta mensal/anual, mas não há preço/moeda aprovados, conta recebedora, provider, sandbox ou segredo configurados. ADR-0003 proíbe inferir essas decisões da infraestrutura.

## Decisão técnica

- Separar catálogo de ofertas, criação/consulta do checkout hospedado e autenticação de webhook em portas server-side. Não aceitar preço, moeda, tenant, `paymentConfirmed` ou estado comercial do browser.
- Adapter de produção sem configuração é indisponível e fail-closed. Sem oferta aprovada e provider configurado, o comando não cria tentativa persistida e não chama um serviço externo. Adapters sintéticos ficam exclusivamente nos testes/E2E e não são empacotados como provider de produção.
- Criar a identidade persistida da tentativa antes do efeito externo. Retry consulta a mesma operação e mantém o mesmo operationId/chave externa; resultado ambíguo não inicia cobrança nova nem repete cegamente a chamada.
- Chamadas externas não compartilham transação PostgreSQL. Referência externa, snapshot de termos e estado mínimo são persistidos; não armazenar PAN/CVV nem payload bruto de webhook.
- Autenticar e normalizar evento segundo o protocolo específico do provider antes da admissão. Não implementar um algoritmo de assinatura genérico como protocolo de produção. A correlação usa referência/conta persistidas no servidor; tenant recebido no corpo não concede autoridade.
- Deduplicar por provider/account/event ID. Aplicar fatos sob lock e CAS, respeitando uma sequência monotônica que o adapter específico consiga comprovar. Sem ordenação autoritativa verificável, o evento não pode regredir a assinatura e exige consulta autoritativa/reconciliação.
- Promoção a estado comercial pago atualiza assinatura/histórico somente com evento autenticado e compatível com operação, plano, valor/moeda e referência vinculados. Redirect do checkout e JavaScript não são prova de pagamento.
- Efeitos de estado local, histórico, auditoria e receipt transacional são confirmados juntos. Erro em qualquer escrita reverte a transação local; o evento autenticado precisa poder ser reprocessado sem duplicar efeito.
- Renovação, retries de inadimplência, tolerância, cancelamento, estorno e chargeback são histórias/contratos próprios (AGD-042/043 e posteriores); esta decisão não os habilita.

## Recomendação externa, não decisão de produto

Manter a integração neutra e avaliar Mercado Pago Checkout Pro para operação brasileira: a documentação oficial descreve checkout hospedado, idempotency key na criação de ordem e assinatura HMAC para notificações. Comparar com Stripe Checkout, cuja documentação oficial descreve página hospedada, idempotência e webhooks. Isso é uma recomendação técnica preliminar, não seleção ou contratação. Produto/operação deve aprovar entidade recebedora, países, liquidação, recorrência, taxas, política de reembolso/chargeback, preço, moeda, sandbox e suporte de consulta/idempotência antes de teste com credenciais.

## Consequências

- A aplicação pode testar persistência, autorização, dedupe, ordenação, rollback e recuperação sem habilitar cobrança real.
- O endpoint de checkout fica indisponível sem oferta e provider reais; o webhook não admite eventos de produção sem verificador específico configurado.
- Testes com adapters não comprovam sandbox, assinatura de provider, pagamento, liquidação ou entrega.
- Retenção de eventos/receipts não é definida por esta ADR; não há expurgo automático.
