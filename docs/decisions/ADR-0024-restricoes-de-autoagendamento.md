# ADR-0024 — Restrições materializadas de autoagendamento

- **Status:** Decisão técnica implementada na branch AGD-033; escolhas de produto listadas em `OPEN-QUESTIONS.md` continuam sem aprovação.
- **Data:** 20/10/2026.
- **Contexto:** AGD-032 fornece política tenant-scoped, fatos de falta, janela/asOf e correção compensatória. A reserva pública e as mudanças de reserva já compartilham lock por tenant e transações com idempotência, auditoria e outbox.

## Decisão técnica proposta

- Materializar uma restrição tenant/cliente com estado `ACTIVE` ou `LIFTED`, versão CAS, fato de falta causal, instante de avaliação, contagem, limite e versão da política. Restrições/histórico são preservados, sem exclusão física.
- A ativação integra a transação do comando `NO_SHOW`; constraint parcial limita uma restrição ativa por cliente/tenant e unicidade do fato causal evita duplicação em reprocessamento.
- A criação pública consulta o cliente existente e a restrição após adquirir o lock comum do tenant e antes de criar cliente/reserva. Ativação de falta, atualização de política e reservas usam a mesma ordem de lock. Replay de reserva já confirmada revalida autorização/gates existentes, não reexecuta a mutação nem consulta a nova restrição.
- A reserva administrativa continua possível e não desbloqueia o cliente. Consulta e cancelamento da área do cliente permanecem fora do escopo da restrição.
- Desbloqueio é comando administrativo explícito com motivo, `expectedVersion`, chave idempotente, revalidação da associação e efeitos transacionais de estado/histórico/auditoria/receipt/outbox. Fatos de falta não são apagados.
- Status de política (`CONFIGURED`/`NOT_CONFIGURED`) é independente do status da restrição (`NOT_CONFIGURED`/`CLEAR`/`RESTRICTED`). Ausência de política não cria limite padrão.
- A API pública usa uma resposta não reveladora para restrição ativa, sem faltas, contagem, motivo ou identificador.

## Evidência desta implementação

A branch AGD-033 materializa e consulta restrições em PostgreSQL, ativa-as na transação de `NO_SHOW` quando a política está configurada, impede nova reserva pública sob o lock tenant-scoped, permite reserva administrativa e implementa desbloqueio explícito. V28 adiciona constraints e grants mínimos. A integração continua sujeita à revisão e ao CI do PR; isto não encerra as escolhas de produto pendentes.

## Pendências de produto

Não se decide aqui se a restrição ativa é removida automaticamente após correção/expiração, como tratar remoção/ausência de política com bloqueio ativo, se redução de limite deve reconciliar fatos anteriores, nem se a falta de política deve impedir novas reservas. Propostas e impactos estão em `docs/development/OPEN-QUESTIONS.md`. A implementação não automatiza essas decisões.

## Consequências e limites

- Nova migration V28 adiciona persistência mínima, constraints e grants sem `DELETE` para o runtime. Administradores do banco permanecem fora dessa fronteira.
- A garantia de concorrência depende de todos os caminhos relevantes manterem o lock por tenant; chamadas externas não entram nas transações.
- Essa ADR não aprova prontidão legal/comercial, fornecedor, retenção, homologação ou regra de liberação automática.
