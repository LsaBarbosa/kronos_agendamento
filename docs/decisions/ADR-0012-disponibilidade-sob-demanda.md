# ADR-0012 — Disponibilidade calculada sob demanda

**Data:** 16/10/2026
**Status:** aceita no escopo técnico de AGD-013; sem alteração do gate de publicação.

## Contexto

AGD-012 persiste grade, políticas, exceções e bloqueios e emite `ScheduleChanged`, mas não existe cache de disponibilidade, consumer de invalidação nem persistência de reservas. AGD-013 precisa calcular ofertas sem transformar slots derivados em autoridade e sem ampliar infraestrutura sem necessidade demonstrada.

## Decisão

- Calcular slots sob demanda a partir das leituras PostgreSQL, com `Clock` injetado, timezone IANA confiável e ocupações fornecidas por porta tenant-scoped.
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

## Referências

Documentos 01 §5, 02 §12, 03 contrato de disponibilidade e evento `ScheduleChanged`, 04 arquitetura modular, critérios AGD-012/013/014/015; ADR-0011; plano `docs/plans/AGD-013-motor-disponibilidade.md`.
