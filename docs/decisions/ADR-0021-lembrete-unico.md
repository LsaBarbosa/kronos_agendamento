# ADR-0021 — Intenção persistida de lembrete único

**Data:** 19/10/2026  
**Status:** adotada para o núcleo técnico de AGD-031; sem aprovação de política temporal/retroatividade ou fornecedor.

## Contexto

AGD-030 persiste notificações imediatas e oferece outbox, receipts, claims individuais, lease/fencing, backoff e correlação autenticada de evidências. AGD-031 precisa agendar um lembrete por reserva sem introduzir broker ou enviar quando a configuração estiver ausente. A política admite offsets de 2, 6, 12 ou 24 horas, mas não especifica a semântica de atraso nem a retroatividade de mudanças.

## Decisões técnicas

- Um consumidor separado (`notifications.appointment-reminders.v1`) lê eventos de confirmação, remarcação, cancelamento, conclusão e falta. O receipt e a criação/invalidação de intent compartilham a transação PostgreSQL já fornecida pelo consumidor idempotente.
- A identidade lógica existente (tenant, reserva, versão, tipo e destinatário) permanece a garantia de dedupe. V24 remove apenas a unicidade de `source_event_id`, pois uma confirmação pode originar a mensagem imediata e um lembrete; a unicidade lógica não é removida.
- `due_at` guarda o instante UTC original e não muda com backoff; `next_attempt_at` continua controlando retries. O offset é lido da política tenant-scoped quando o evento compatível é processado. Configuração ausente não cria intent nem aplica default.
- Eventos que não correspondem à versão/estado corrente não criam intent. Remarcação invalida intents anteriores; estado terminal invalida os lembretes ainda pendentes. O feed inclui `AppointmentCompleted` e `ClientNoShowRecorded` além de confirmação, remarcação e cancelamento.
- Cada intent é reivindicada imediatamente antes do preflight/transporte, nunca um lote inteiro. Antes de chamar o adapter, o worker verifica token/lease, status, versão e `starts_at > Clock.instant()`. Não existe chamada externa dentro de transação. Uma alteração após o início da chamada não pode cancelar efeito em voo.
- Falta de transport configurado mantém produção sem claim e sem tentativa. `OUTCOME_UNKNOWN` continua sem retry automático. Métricas são agregadas e não usam PII ou labels tenant/reserva.

## Questões de produto ainda abertas

- Documento 06 pede reavaliar se job atrasado ainda faz sentido, mas não define o limite. Documento 01 descreve lembrete como “padrão ligado”, enquanto `AvailabilityPolicy` pode estar ausente e não existe offset default aprovado. É necessária decisão sobre o que “ligado” significa sem configuração explícita.
- Também é necessário decidir se reserva confirmada depois de `due_at` deve ser lembrada imediatamente se ainda futura, ou não gerar lembrete; e se alteração de `reminderOffsetHours` afeta intents já criadas ou eventos ainda não consumidos.
- A implementação atual representa uma proposta técnica: materializa o offset lido ao consumir o evento; um dueAt vencido pode ser enviado somente antes do início. Essa proposta não é decisão de produto e o critério temporal correspondente fica parcial até aprovação.

## Consequências e limites

- Nenhum novo broker ou fornecedor é necessário para provar o fluxo local. Adapter de teste prova composição e estados, não WhatsApp, template, aceite ou entrega real.
- A revalidação imediatamente antes do transporte minimiza envio obsoleto, mas não fornece cancelamento de chamada já em voo nem exactly-once externo.
- Não há expurgo/TTL novo; retenção permanece pendente. V1–V23 permanecem imutáveis.
