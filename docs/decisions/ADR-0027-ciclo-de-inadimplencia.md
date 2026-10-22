# ADR-0027 — Ciclo persistido de inadimplência

- **Status:** decisões técnicas e temporais aprovadas para AGD-042; integração financeira real continua fechada.
- **Data:** 22/10/2026.
- **Referências:** AGD-042, documentos 01 §§12.3–12.4, 02 §7, 03 §§12–13 e jobs, documento 06; ADR-0003, ADR-0008, ADR-0025 e ADR-0026.

## Contexto

AGD-041 persiste checkout hospedado e eventos de pagamento autoritativos, mas não existe ciclo de inadimplência nem autorização de débito recorrente. A transição comercial também precisa competir corretamente com reservas públicas e trial.

## Decisão técnica

- O vencimento é o `paid_through_at` persistido. O primeiro processamento cria um ciclo único por tenant, assinatura e vencimento, transiciona `ACTIVE → PAST_DUE` por CAS e persiste tentativas lógicas D0/D+3/D+6. **Decisão aprovada pelo usuário em 22/10/2026:** tolerância = 168 horas exatas do vencimento, terminando em `now >= graceEndsAt`; D+3/D+6 = offsets UTC de 72/144 horas.
- **Decisão aprovada pelo usuário em 22/10/2026:** na recuperação após downtime dentro da tolerância, somente a tentativa vencida mais recente nunca iniciada pode permanecer elegível. As anteriores nunca iniciadas são `SKIPPED_LATE_RECOVERY` com motivo. Tentativas futuras mantêm vencimentos; não há nova tentativa/ciclo. Tentativa iniciada/em voo/aceita/ambígua nunca é pulada e requer reconciliação antes de autorizar outra cobrança lógica. Após a fronteira nenhuma nova chamada começa, mas resultados iniciados continuam sendo persistidos mesmo após suspensão.
- Cada sequência tem identidade própria e estável no ciclo. Retry técnico conserva attemptId/chave externa. Só se repete com prova de ausência de efeito ou idempotência externa aplicável; timeout/crash é ambíguo. Sem consulta/idempotência suportada, o resultado desconhecido fica bloqueado para operação. O worker não mantém transação durante transporte.
- Provider financeiro e autorização vigente para cobrança recorrente são portas separadas. Ambos permanecem desabilitados em produção enquanto provider/mandato/configuração não forem aprovados. Sem eles, não há chamada externa nem alegação de tentativa aceita/pagamento.
- Transições do ciclo e pagamento adquirem `TenantAgendaLock` antes de ler estado comercial. O gate da reserva usa o mesmo lock e verifica `PAST_DUE` contra o instante de fim da tolerância mesmo se o worker estiver atrasado.
- Pagamento autenticado regulariza assinatura e cancela tentativas ainda não iniciadas na mesma transação. Uma tentativa já iniciada conserva sua evidência/estado; conflito ambíguo não autoriza cobrança paralela.
- Histórico, auditoria minimizada, receipt determinístico da operação de sistema e outbox de transição são atômicos. Eventos externos permanecem sem PII e sem valores financeiros não aprovados.

## Pendências operacionais/produto fora das decisões temporais

Provider/sandbox, oferta/preço/moeda, autorização/mandato recorrente confiável, segredos gerenciados e homologação seguem pendentes. Esta ADR não autoriza cobrança real, renovação anual automática nem retenção/expurgo. Plano anual não será cobrado sem autorização específica. A porta de autorização é separada da autoridade de checkout.

## Consequências

- PostgreSQL continua autoridade para unicidade, claims, CAS e isolamento; restart/múltiplas instâncias não dependem de memória local.
- Métricas de worker usam agregados globais sem etiquetas de alta cardinalidade ou PII: backlog, atrasos, tentativas técnicas, desconhecidos, aceite de transporte, recusas, falhas finais e recuperação tardia.
- Adapters sintéticos comprovam composição somente. Sem provider e autorização financeira reais configurados, o worker não chama transporte nem contabiliza tentativa como enviada.
- `PAST_DUE` mantém a operação permitida durante a tolerância; `SUSPENDED` bloqueia novas reservas, mas não apaga reservas nem impede acessos permitidos a elas.
