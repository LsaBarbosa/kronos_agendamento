# ADR-0014 — Protocolo concorrente de reserva e bloqueio

- **Status:** Aceita para AGD-015
- **Data:** 16/10/2026
- **Contexto:** AGD-012 já grava bloqueios, mas não havia persistência de appointments. A exclusion constraint entre appointments não protege a corrida entre tabelas diferentes.

## Decisão

- Reserva pública e criação de bloqueio administrativo adquirem primeiro `SELECT tenant_id FROM agendamento.tenant WHERE tenant_id = ? FOR UPDATE` dentro da transação PostgreSQL.
- As duas operações usam a mesma ordem de locks: tenant → leituras/validações da agenda → gravações de cliente/reserva/história/auditoria/idempotência/outbox. Chamadas externas ficam fora da transação.
- `PostgresIdempotencyCoordinator` declara `@Transactional(isolation = READ_COMMITTED)` como boundary da mutação; o protocolo não depende do default da role/banco. Depois de esperar pelo lock, os comandos executam as consultas de conflitos em novas instruções SQL; assim não reutilizam snapshot obtido antes da espera e observam o commit que liberou o lock.
- Reserva-reserva também é protegida por constraint `EXCLUDE USING gist (tenant_id WITH =, tstzrange(starts_at, occupied_until, '[)') WITH &&) WHERE (status='CONFIRMED')`. Adjacent intervals são permitidos. A violação da constraint nomeada é traduzida para `SLOT_NO_LONGER_AVAILABLE`; outros erros de integridade continuam erros distintos.
- A criação de bloqueio, sob o mesmo lock, consulta reservas `CONFIRMED` com interseção semiaberta e recusa o bloqueio conflitante. Nenhuma reserva é movida/cancelada.
- Reserva pública sem identidade verificada registra auditoria com `actor_type=SYSTEM`, `actor_ref=PUBLIC_BOOKING`, `actor_id=NULL`: identifica o canal originador sem atribuir ao telefone uma identidade autenticada. História inicial registra origem pública sem PII em logs/auditoria/outbox.
- `AppointmentConfirmed` usa payload allowlisted contendo somente referência `appointmentId` e versão; consumidores que precisarem de dados consultam a fonte autorizada. A intenção de notificação é posterior; esta ADR não integra WhatsApp.
- Snapshot preserva nome/duração/buffer do serviço e zona. Preço/moeda permanecem ausentes enquanto não definidos; sem valor zero, moeda implícita ou cobrança.

## Consequências

- Toda criação de reserva/bloqueio para o mesmo tenant é serializada no lock do registro tenant; o custo é menor paralelismo intratenant, aceito para o MVP e necessário para proteger a disputa cross-table.
- A garantia depende de ambos os caminhos adquirirem o lock antes das leituras de conflito. `SELECT` prévio isolado não serve.
- A exclusão GiST é a autoridade final para reserva-reserva, mas não substitui protocolo contra `blocked_period`.
- Replay idempotente revalida publicação, elegibilidade e acesso ao serviço antes de retornar; não reexecuta validação de ocupação contra a reserva original.
- Telefone continua não verificado; AGD-024/025 são necessários para autenticação e consulta do cliente.

## Validação

- Testes PostgreSQL com transações independentes provam: reserva primeiro bloqueia criação de bloqueio; bloqueio primeiro impede reserva; duas reservas no mesmo intervalo resultam em um commit; a violação da constraint mapeia ao conflito de slot; adjacência e isolamento de tenant continuam válidos.
- Migração deve validar installation fresh, upgrade V12→V13, `flyway validate`/rerun e permissões runtime.

## Referências

- Documentos 01 §§5–6, 02 §§4.2/4.5/11/13/16, 03 §§1/2/6/7/8, 04 §§6–7/16 e AGD-015.
