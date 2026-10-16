# ADR-0011 — Semântica temporal de exceções e bloqueios

**Data:** 16/10/2026
**Status:** aprovada pelo usuário para AGD-012.

## Contexto

AGD-012 introduz exceções de calendário e bloqueios administrativos. A recorrência da grade semanal é local ao tenant, enquanto bloqueios podem cruzar datas e mudanças de offset. A regra de tempo precisa evitar conversão implícita em transições DST, preservar a semântica da grade e não prometer proteção concorrente contra reservas antes de existir persistência de appointments.

## Decisão

- Ausência de exceção aplica a grade semanal. `CLOSED` fecha o dia. `SPECIAL` substitui integralmente a grade pela lista final de períodos, que pode conter períodos semanais mantidos e períodos adicionais escolhidos explicitamente. Remover a exceção restaura a grade semanal.
- Períodos especiais usam data, `LocalTime` e zona IANA confiável do tenant; os limites são múltiplos de 15 minutos, independentemente do slot; intervalos `[início,fim)`, adjacência permitida, overlap rejeitado, `início < fim`, sem cruzar meia-noite. `SPECIAL` exige lista não vazia; `CLOSED` exige lista vazia.
- Um limite local sem offset é válido somente se a zona resolver a data/hora para exatamente um offset. Horário inexistente ou ambíguo é rejeitado sem ajuste. Uma entrada com offset explícito pode selecionar uma ocorrência ambígua, desde que o offset informado pertença aos offsets válidos da zona na data/hora. Um offset inválido é rejeitado.
- Bloqueios persistem `starts_at` e `ends_at` como instantes UTC (`timestamptz`) e a zona IANA usada. Podem atravessar meia-noite e datas distintas; a duração é a diferença entre instantes, inclusive ao atravessar mudança de offset. Limites locais devem ter minutos múltiplos de 15 e sem segundos/fração. Requer `start < end`.
- Motivo é obrigatório em criação e cancelamento de bloqueio. Cancelamento é lógico e mantém ID, motivo original, motivo de cancelamento, timestamp e versão incrementada. Não há exclusão física nem restrição genérica de sobreposição entre bloqueios.
- Exceção é única por tenant/data. Listagens exigem `from` e `to` válidos, datas inclusivas e no máximo 366 datas locais. Janela de bloqueios é convertida por limites civis locais da zona autenticada e usa predicado de interseção semiaberta; limite de busca é técnico, não política de retenção.
- Escritas mutáveis usam scopes idempotentes versionados e digest canônico de comando validado, alvo e versão esperada. Mutação, registro idempotente concluído, auditoria allowlisted e `ScheduleChanged` compartilham transação. Motivos livres não vão à auditoria nem ao evento.
- O fluxo de criação chama uma porta de consulta de reservas confirmadas. Atualmente não há schema/caso de uso de appointments; o adapter temporário apenas informa que a fonte não está implementada. Isso não comprova ausência de conflito em futuras reservas, nem fecha a corrida contra confirmação concorrente. AGD-015 precisa integrar ambos os comandos à mesma proteção de banco/transação.
- O projeto não tem cache de disponibilidade nem consumer que o invalide. AGD-012 grava `ScheduleChanged` via outbox, mas não cria cache/publisher artificial nem declara invalidação concluída. Integração de cache é responsabilidade futura de AGD-013.

## Consequências e trade-offs

- Horário especial é configuração final do dia, não união implícita com a grade; a UI pode copiar os períodos semanais como ponto inicial, mas a persistência contém a lista explicitamente escolhida.
- Offset explícito é aceito somente como escolha verificável de ocorrência; UI usa hora local e não escolhe offset em nome do usuário. Entradas ambíguas sem offset falham com orientação para informar um horário não ambíguo.
- Os bloqueios podem cruzar transições de offset sem assumir duração fixa de um dia civil.
- O limite de 366 dias mantém consultas administrativas delimitadas; precisa ser revisto deliberadamente se requisitos de UX mudarem.
- Sem persistência de reservas/cache, AGD-012 fica parcial quanto à prova de conflito concorrente e invalidação real, apesar do port/outbox testável.

## Referências

Decisão explícita do usuário em 16/10/2026; documentos 01 §5.3/§5.5, 02 §§2/4, 03 contrato administrativo de exceções e bloqueios, backlog AGD-012, ADR-0010 e plano `docs/plans/AGD-012-excecoes-bloqueios.md`.
