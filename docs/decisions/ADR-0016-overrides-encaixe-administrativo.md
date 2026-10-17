# ADR-0016 — Limites dos overrides de encaixe administrativo

- **Status:** Aceita
- **Data:** 17/10/2026
- **Contexto:** AGD-021 permite exceções explícitas para grade semanal e antecedência. Exceções por data substituem ou fecham a grade; o POST não pode reinterpretar nem alterar silenciosamente essa configuração.

## Decisão

- `OUTSIDE_WEEKLY_SCHEDULE` ignora somente a grade semanal e só se aplica quando não existe exceção para a data. O administrador pode reservar fora da grade com confirmação explícita e motivo.
- `CLOSED` sempre impede atendimento. O override não reabre a data.
- `SPECIAL` substitui a grade semanal por sua lista final de períodos. O override de grade não ultrapassa essa lista; a ocupação inteira `[startsAt, occupiedUntil)`, incluindo buffer, deve caber em um único período especial e respeitar âncora/passo.
- `MIN_NOTICE` permanece exceção independente; pode ser aplicada dentro de período `SPECIAL` com motivo, sem dispensar seus limites.
- Bloqueios, ocupações, overlap, horizonte, serviço inativo e ausência de configuração não são ignoráveis. Não há nova política de override.
- O comando administrativo não cria, amplia, remove ou altera grade/exceção como efeito colateral.
- Motivo continua obrigatório para qualquer override, persistido apenas na história de domínio permitida; auditoria/outbox mantêm allowlists existentes.

## Consequências

- A avaliação do servidor permanece autoritativa; UI só apresenta estimativa e não relaxa validação.
- Replay usa a mesma identidade/canonicalização e não repete reserva, história, auditoria ou outbox.
- A consulta de revisão da agenda continua protegendo alterações concorrentes; rejeições não produzem efeitos de sucesso.
- `NOT_TRACKED` não comprova integração de restrições reais de autoagendamento, que permanece dependente de AGD-032/033.

## Referências

- Documentos 01, 02, 03 e 06; AGD-021; ADR-0011 e ADR-0014.
