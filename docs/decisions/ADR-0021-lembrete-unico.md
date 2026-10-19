# ADR-0021 — Intenção persistida de lembrete único

**Data:** 19/10/2026  
**Status:** adotada para o núcleo técnico e regras temporais de AGD-031; fornecedor e homologação permanecem pendentes.

## Contexto

AGD-030 persiste notificações imediatas e oferece outbox, receipts, claims individuais, lease/fencing, backoff e correlação autenticada de evidências. AGD-031 agenda um lembrete por reserva sem broker e sem envio de produção quando a configuração real estiver ausente. Esta decisão de produto foi aprovada nesta instrução, em 19/10/2026, para fechar a semântica de política ausente, atraso e mudança de offset.

## Decisões técnicas

- Um consumidor separado (`notifications.appointment-reminders.v1`) lê eventos de confirmação, remarcação, cancelamento, conclusão e falta. O receipt e a criação/invalidação de intent compartilham a transação PostgreSQL já fornecida pelo consumidor idempotente.
- A identidade lógica existente (tenant, reserva, versão, tipo e destinatário) permanece a garantia de dedupe. V24 remove apenas a unicidade de `source_event_id`, pois uma confirmação pode originar a mensagem imediata e um lembrete; a unicidade lógica não é removida.
- `due_at` guarda o instante UTC original e não muda com backoff; `next_attempt_at` continua controlando retries. O offset é lido da política tenant-scoped quando o evento compatível é processado. Configuração ausente não cria intent nem aplica default.
- Eventos que não correspondem à versão/estado corrente não criam intent. Remarcação invalida intents anteriores; estado terminal invalida os lembretes ainda pendentes. O feed inclui `AppointmentCompleted` e `ClientNoShowRecorded` além de confirmação, remarcação e cancelamento.
- Cada intent é reivindicada imediatamente antes do preflight/transporte, nunca um lote inteiro. Antes de chamar o adapter, o worker verifica token/lease, status, versão e `starts_at > Clock.instant()`. Não existe chamada externa dentro de transação. Uma alteração após o início da chamada não pode cancelar efeito em voo.
- Falta de transport configurado mantém produção sem claim e sem tentativa. `OUTCOME_UNKNOWN` continua sem retry automático. Métricas são agregadas e não usam PII ou labels tenant/reserva.
- V25 separa elegibilidade e resultado da tentativa com `notification_intent.reminder_eligible`. Evento de cancelamento/remarcação obsoleta PENDING/RETRY e marca CLAIMED inelegível sem limpar ownership/lease nem alterar attempt STARTED. `authorizeSend` e `claimDue` rejeitam lembrete inelegível. Se o transporte já começou, aceite com referência permanece gravável e a correlação de evidência continua no protocolo comum do ADR-0020; resultado ambíguo permanece `OUTCOME_UNKNOWN`, sem retry automático. Uma falha conhecida sem efeito, retornada depois da invalidação, encerra a intent como obsoleta e preserva o resultado detalhado na tentativa.

## Decisões de produto aprovadas em 19/10/2026

- Política ausente: não criar lembrete nem inventar offset. A descrição de “padrão ligado” só se aplica quando a política estiver explicitamente configurada.
- Atraso: permitir envio atrasado somente enquanto a reserva continuar `CONFIRMED`, na versão correta, e `starts_at` for estritamente maior que o instante capturado no preflight. Na igualdade ou depois do início, a intenção é obsoleta; não há tolerância.
- Reserva criada/confirmada depois de `due_at`: pode ser processada imediatamente enquanto ainda futura, sujeita às demais validações e deduplicação.
- Política temporal: calcular e materializar `due_at` na criação da intenção com a política vigente quando o evento é consumido. Alteração posterior não recalcula intents persistidas. Evento ainda não consumido usa a política vigente no processamento; remarcação cria intenção da nova versão com essa mesma regra.

## Consequências e limites

- Nenhum novo broker ou fornecedor é necessário para provar o fluxo local. Adapter de teste prova composição e estados, não WhatsApp, template, aceite ou entrega real.
- A revalidação imediatamente antes do transporte minimiza envio obsoleto, mas não fornece cancelamento de chamada já em voo nem exactly-once externo.
- V25 acrescenta somente a elegibilidade persistida; V1–V24 permanecem imutáveis. Instalação limpa, upgrade V24→V25, validate/rerun e privilégios runtime são verificados em PostgreSQL real.
- Não há expurgo/TTL novo; retenção permanece pendente.
- Regras temporais e ausência de configuração estão decididas e podem ser validadas sem serviço externo. AGD-031 permanece parcial por transport/verifier, templates aprovados, credenciais gerenciadas e homologação. A decisão não autoriza envio real.
