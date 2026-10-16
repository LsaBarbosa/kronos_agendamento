# ADR-0012 — Disponibilidade calculada sob demanda

**Data:** 16/10/2026
**Status:** aceita no escopo técnico de AGD-013; sem alteração do gate de publicação.

## Contexto

AGD-012 persiste grade, políticas, exceções e bloqueios e emite `ScheduleChanged`, mas não existe cache de disponibilidade, consumer de invalidação nem persistência de reservas. AGD-013 precisa calcular ofertas sem transformar slots derivados em autoridade e sem ampliar infraestrutura sem necessidade demonstrada.

## Decisão

- Calcular slots sob demanda a partir das leituras PostgreSQL, com `Clock` injetado, timezone IANA confiável e ocupações fornecidas por porta tenant-scoped.
- Executar as leituras da configuração, serviço e bloqueios na mesma transação PostgreSQL `REPEATABLE READ`, com captura única de `Clock` por consulta. Isso oferece um snapshot coerente das tabelas lidas pela aplicação; a porta externa de ocupações deve consultar dentro do mesmo contexto quando tiver adapter de produção. Não promete consistência com sistemas externos nem impede mutações posteriores ao snapshot.
- Consultar por janela local inclusiva de no máximo 31 datas (`DAYS.between + 1`); janela invertida/excessiva resulta em `400 INVALID_AVAILABILITY_WINDOW`. Datas pedidas além do horizonte são retornadas vazias.
- Ancorar inícios no início de cada período e avançar por `slotStepMinutes`; bloqueios, ocupações e antecedência somente filtram. Períodos `SPECIAL` possuem âncoras próprias. A ocupação completa deve caber em um único período.
- Usar antecedência inclusiva no instante exato (`capturedNow + noticeHours`). Horizonte `N` inclui hoje e mais `N - 1` datas locais; a data final inteira é elegível, a partir do início da data local seguinte não há candidatos. Não interpretar como `N * 24h`.
- Não adicionar cache, Redis, broker ou publisher de invalidação nesta história. Não interpretar `ScheduleChanged` como invalidação efetiva.
- Manter o contrato público e a rota deny-all até o gate explícito de publicação ser definido e implementado em AGD-014. A existência de tenant ativo, serviço ativo ou grade configurada não publica o negócio.
- Manter cálculo indicativo: não cria hold/reserva e não garante que o slot ainda esteja livre no commit futuro.
- A integração com ocupações reais e a proteção concorrente final pertencem a AGD-015. A porta e os adapters de teste não afirmam existência de reservas em produção.
- Reavaliar cache somente quando métricas demonstrarem necessidade. Se adotado, a estratégia futura precisará cobrir dependências, invalidação pós-commit, concorrência de repopulação e indisponibilidade do cache.

## Consequências e trade-offs

- Evita cache obsoleto, invalidação prematura e novos serviços operacionais; cada consulta lê configuração e bloqueios persistidos.
- O custo de leitura/cálculo será medido quando houver tráfego real; sem benchmark de staging não há afirmação de capacidade/SLO.
- `ScheduleChanged` continua útil para consumidores futuros, mas não comprova cache atualizado.
- Não desbloqueia rotas públicas, publicação, confirmação ou proteção contra corrida com reserva.

As regras temporais acima foram aprovadas pelo usuário em 16/10/2026 e também estão registradas nos documentos de requisitos 01 e contrato 03. Antes de publicação, AGD-014 ainda precisa aprovar/implementar o gate público; AGD-015 precisa integrar ocupações persistidas e impedir corrida com confirmação.

## Referências

Documentos 01 §5, 02 §12, 03 contrato de disponibilidade e evento `ScheduleChanged`, 04 arquitetura modular, critérios AGD-012/013/014/015; ADR-0011; plano `docs/plans/AGD-013-motor-disponibilidade.md`.
