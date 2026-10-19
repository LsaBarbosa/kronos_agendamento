# ADR-0022 — Persistência do limite de faltas por tenant

**Data:** 19/10/2026
**Status:** adotada para configuração administrativa; avaliação/correção permanecem parciais.

## Contexto

AGD-032 requer limite configurável de 1 a 5 faltas por tenant. Os requisitos ainda não definem o instante e fronteiras da janela móvel nem o resultado de corrigir um `NO_SHOW`. A configuração pode ser entregue sem inferir essas regras ou declarar bloqueio de autoagendamento.

## Decisões técnicas

- Persistir uma linha opcional por tenant em `client_no_show_policy`; ausência é distinta de qualquer limite e não recebe default.
- Expor GET/PUT administrativos tenant-scoped. `expectedVersion` usa CAS; o comando usa escopo idempotente próprio e audita apenas o campo alterado e versões.
- Restringir threshold no domínio, DTO e PostgreSQL ao intervalo 1–5. Runtime recebe somente SELECT/INSERT/UPDATE; não recebe DELETE/TRUNCATE.
- Resposta informa `restrictionStatus=NOT_TRACKED`; o limite configurado, isoladamente, não cria nem remove restrição. Bloqueio real depende de AGD-033.
- Até a resolução das questões de produto, não calcular contagem “completa”, não executar correção e não afirmar restrição.

## Consequências e trade-offs

- O administrador pode registrar a política sem que o sistema finja ter implementado contagem/restrição.
- A alteração de limite não reescreve faltas nem reavalia automaticamente restrições nesta fase.
- A migration V26 estende as allowlists de auditoria sem alterar V1–V25; instalação limpa e upgrade devem validar o mesmo schema e grants.
- Questões da janela e correção permanecem em `docs/development/OPEN-QUESTIONS.md`; esta ADR não as decide.
