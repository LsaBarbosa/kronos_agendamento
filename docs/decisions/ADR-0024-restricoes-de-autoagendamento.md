# ADR-0024 — Restrições materializadas de autoagendamento

- **Status:** Decisão técnica e decisões de produto aprovadas em 20/10/2026; implementação da reconciliação explícita em andamento no PR #21.
- **Data:** 20/10/2026.
- **Contexto:** AGD-032 fornece política tenant-scoped, fatos de falta, janela/asOf e correção compensatória. A reserva pública e as mudanças de reserva já compartilham lock por tenant e transações com idempotência, auditoria e outbox.

## Decisão técnica

- Materializar uma restrição tenant/cliente com estado `ACTIVE` ou `LIFTED`, versão CAS, fato de falta causal, instante de avaliação, contagem, limite e versão da política. Restrições/histórico são preservados, sem exclusão física.
- A ativação integra a transação do comando `NO_SHOW`; constraint parcial limita uma restrição ativa por cliente/tenant e unicidade do fato causal evita duplicação em reprocessamento.
- A criação pública consulta o cliente existente e a restrição após adquirir o lock comum do tenant e antes de criar cliente/reserva. Ativação de falta, atualização de política e reservas usam a mesma ordem de lock. Replay de reserva já confirmada revalida autorização/gates existentes, não reexecuta a mutação nem consulta a nova restrição.
- A reserva administrativa continua possível e não desbloqueia o cliente. Consulta e cancelamento da área do cliente permanecem fora do escopo da restrição.
- Desbloqueio é comando administrativo explícito com motivo, `expectedVersion`, chave idempotente, revalidação da associação e efeitos transacionais de estado/histórico/auditoria/receipt/outbox. Fatos de falta não são apagados.
- Status de política (`CONFIGURED`/`NOT_CONFIGURED`) é independente do status da restrição (`NOT_CONFIGURED`/`CLEAR`/`RESTRICTED`). Ausência de política não cria limite padrão.
- A API pública usa uma resposta não reveladora para restrição ativa, sem faltas, contagem, motivo ou identificador.
- Correção de falta e envelhecimento só alteram a contagem; nunca liberam restrição ativa. A liberação requer comando administrativo explícito com motivo, CAS, idempotência e efeitos transacionais.
- Política ausente/removida não altera restrições existentes nem cria novas ativações por limite. Sem política e sem restrição ativa, a criação pública continua permitida com status `NOT_CONFIGURED`, desde que todos os outros gates (publicação, legal/comercial, serviço e disponibilidade) sejam atendidos.
- Redução do limite não dispara varredura no `PUT`. Reconciliação administrativa explícita por cliente avalia a versão da política informada, sob lock tenant-first e com `asOf` capturado após o lock; lê contagem/fatos de forma coerente e retorna decisão, janela, contagem e versão. A chave idempotente é escopada por tenant/cliente e o digest versionado inclui `expectedPolicyVersion`; autorização atual é revalidada antes do replay.
- Decisões possíveis incluem limite abaixo, já restrito, sem causa ainda não consumida e ativação. Ativação só usa fato `NO_SHOW` ainda não referenciado como causa de qualquer restrição, inclusive uma já desbloqueada. Isso impede reativar por replay/reprocessamento da mesma falta. Uma falta nova pode causar outra ativação conforme política vigente. Replay recupera o resultado original e não desfaz desbloqueio posterior.

## Evidência desta implementação

AGD-033 materializa e consulta restrições em PostgreSQL, ativa-as na transação de `NO_SHOW` quando a política está configurada, impede nova reserva pública sob lock tenant-scoped, permite reserva administrativa e implementa desbloqueio explícito. V28 adicionou constraints e grants mínimos. A continuação acrescenta reconciliação explícita por cliente, sem varredura no PUT. O PR segue sujeito a revisão e CI; não representa prontidão para produção.

## Pendências de produto

As decisões acima foram aprovadas pelo usuário nesta instrução em 20/10/2026 e não são atribuídas retroativamente à especificação anterior. Permanecem pendentes os gates operacionais, legais/comerciais, homologação e retenção registrados em `docs/development/OPEN-QUESTIONS.md`.

## Consequências e limites

- V28 introduziu persistência de restrições; V29 estende o evento de histórico para `RECONCILED` e allowlists correspondentes. V1–V28 são imutáveis. Runtime permanece sem privilégios de exclusão para auditoria; administradores do banco ficam fora dessa fronteira.
- A garantia de concorrência depende de todos os caminhos relevantes manterem o lock por tenant; chamadas externas não entram nas transações.
- Essa ADR não aprova prontidão legal/comercial, fornecedor, retenção, homologação ou regra de liberação automática.
