# ADR-0018 — Limites temporais de cancelamento e encerramento

- **Status:** Aceita
- **Data:** 17/10/2026
- **Contexto:** AGD-023 precisa definir limites observáveis para conclusão/falta e para a política preparada de cancelamento do cliente, sem liberar ocupação antes do buffer nem aplicar tolerância não aprovada.

## Decisão

- Decisão de produto aprovada pelo usuário nesta instrução: `COMPLETED` e `NO_SHOW` somente são elegíveis quando `Clock.instant() >= occupiedUntil`. `occupiedUntil` inclui duração e buffer posterior; com buffer zero, coincide com `service_ends_at`.
- Cancelamento do cliente é permitido quando `now <= startsAt - cancellationCutoffHours`; o cutoff configurado continua entre 2 e 24 horas.
- Cancelamento administrativo permanece elegível somente pelo estado `CONFIRMED`, sem herdar o cutoff do cancelamento do cliente.
- Não existe tolerância adicional de dez minutos.
- A regra temporal é verificada na nova execução, depois dos locks e da leitura atual da reserva. Rejeição não grava receipt de sucesso nem qualquer efeito. Replay autorizado de comando concluído retorna o receipt original sem reavaliar o limite ou reaplicar a transição.
- O domínio mantém a política pura para cancelamento do cliente, mas não há rota mutável até AGD-024/025 implementarem identidade verificada.

## Consequências

- Cancelamento administrativo não libera uma reserva futura por um cutoff destinado ao cliente.
- Status, versão, revisão, história, auditoria, receipt e outbox permanecem atômicos.
- O instante exato de `occupiedUntil` é permitido; o instante imediatamente anterior é rejeitado.
- Esta decisão não implementa envio/invalidação de lembretes (AGD-030/031) nem contagem/restrição automática por falta (AGD-032/033).

## Referências

- AGD-023; documentos 01 §9, 02 §§6/9 e 10, 03 comandos de encerramento e 06 AGD-023.
- ADR-0008 para idempotência/outbox; ADR-0014 para lock concorrente por tenant.
