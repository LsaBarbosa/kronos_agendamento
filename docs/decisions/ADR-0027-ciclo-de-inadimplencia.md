# ADR-0027 — Ciclo persistido de inadimplência

- **Status:** decisão técnica para AGD-042; escolhas temporais de produto seguem abertas.
- **Data:** 22/10/2026.
- **Referências:** AGD-042, documentos 01 §§12.3–12.4, 02 §7, 03 §§12–13 e jobs, documento 06; ADR-0003, ADR-0008, ADR-0025 e ADR-0026.

## Contexto

AGD-041 persiste checkout hospedado e eventos de pagamento autoritativos, mas não existe ciclo de inadimplência nem autorização de débito recorrente. A transição comercial também precisa competir corretamente com reservas públicas e trial.

## Decisão técnica

- O vencimento é o `paid_through_at` persistido. O primeiro processamento cria um ciclo único por tenant, assinatura e vencimento, transiciona `ACTIVE → PAST_DUE` por CAS e persiste tentativas lógicas D0/D+3/D+6. D+3/D+6 são 72/144 horas UTC do instante de vencimento, escolha técnica provisória até decisão de produto sobre dias corridos.
- Cada sequência tem identidade própria e estável no ciclo. Uma repetição técnica conserva a identidade; `OUTCOME_UNKNOWN` bloqueia qualquer sequência lógica posterior até reconciliação autoritativa. O worker não mantém transação durante transporte.
- Provider financeiro e autorização vigente para cobrança recorrente são portas separadas. Ambos permanecem desabilitados em produção enquanto provider/mandato/configuração não forem aprovados. Sem eles, não há chamada externa nem alegação de tentativa aceita/pagamento.
- Transições do ciclo e pagamento adquirem `TenantAgendaLock` antes de ler estado comercial. O gate da reserva usa o mesmo lock e verifica `PAST_DUE` contra o instante de fim da tolerância mesmo se o worker estiver atrasado.
- Pagamento autenticado regulariza assinatura e cancela tentativas ainda não iniciadas na mesma transação. Uma tentativa já iniciada conserva sua evidência/estado; conflito ambíguo não autoriza cobrança paralela.
- Histórico, auditoria minimizada, receipt determinístico da operação de sistema e outbox de transição são atômicos. Eventos externos permanecem sem PII e sem valores financeiros não aprovados.

## Decisões de produto ainda necessárias

O backlog define tolerância de sete dias e datas D0/D+3/D+6, mas não resolve: (a) se a suspensão ocorre em igualdade com `grace_ends_at` ou somente depois; (b) se, após indisponibilidade, tentativas vencidas são processadas em ordem ou ignoradas. A proposta está em `docs/development/OPEN-QUESTIONS.md`. Até aprovação, a implementação pode demonstrar persistência e estados, mas esses limites permanecem explicitamente parciais.

Também seguem pendentes provider, oferta, preço, moeda, mandato de recorrência, segredos e homologação. Esta ADR não autoriza cobrança real, renovação anual automática nem retenção/expurgo.

## Consequências

- PostgreSQL continua autoridade para unicidade, claims, CAS e isolamento; restart/múltiplas instâncias não dependem de memória local.
- Adapters sintéticos comprovam composição somente. Sem provider real, o worker falha fechado e não simula aceite.
- `PAST_DUE` mantém a operação permitida durante a tolerância; `SUSPENDED` bloqueia novas reservas, mas não apaga reservas nem impede acessos permitidos a elas.
