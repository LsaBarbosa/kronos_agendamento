# ADR-0022 — Persistência do limite de faltas por tenant

**Data:** 19/10/2026
**Status:** adotada para persistência do limite; regras de avaliação/correção complementadas pela ADR-0023.

## Contexto

AGD-032 requer limite configurável de 1 a 5 faltas por tenant. Esta decisão trata exclusivamente da persistência/configuração; as regras observáveis de janela, correção e ausência de política foram aprovadas posteriormente nesta instrução e registradas em ADR-0023.

## Decisões técnicas

- Persistir uma linha opcional por tenant em `client_no_show_policy`; ausência é distinta de qualquer limite e não recebe default.
- Expor GET/PUT administrativos tenant-scoped. `expectedVersion` usa CAS; o comando usa escopo idempotente próprio e audita apenas o campo alterado e versões.
- Restringir threshold no domínio, DTO e PostgreSQL ao intervalo 1–5. Runtime recebe somente SELECT/INSERT/UPDATE; não recebe DELETE/TRUNCATE.
- Resposta informa estado da política (`CONFIGURED`/`NOT_CONFIGURED`) separadamente de `restrictionStatus=NOT_TRACKED`; o limite, isoladamente, não cria nem remove restrição. Bloqueio real depende de AGD-033.

**Nota de evolução:** esta descrição registra a fronteira quando AGD-032 foi implementada. AGD-033 substitui o status corrente por consulta persistida tenant-scoped (`NOT_CONFIGURED`, `CLEAR` ou `RESTRICTED`); `NOT_TRACKED` permanece somente como valor histórico de auditoria. As decisões pendentes de produto estão em ADR-0024 e `OPEN-QUESTIONS.md`.
- Contagem/fatos e correção compensatória seguem as decisões da ADR-0023; esta ADR não define o cálculo nem a semântica do comando corretivo.

## Consequências e trade-offs

- O administrador pode registrar a política sem que o sistema finja ter implementado contagem/restrição.
- A alteração de limite não reescreve faltas nem materializa restrições automaticamente; essa integração permanece em AGD-033.
- A migration V26 estende as allowlists de auditoria sem alterar V1–V25; instalação limpa e upgrade devem validar o mesmo schema e grants.
- V26 cria a linha opcional e atualiza a allowlist de auditoria; V27 adiciona referência da correção compensatória e evento allowlisted sem modificar V1–V26.
