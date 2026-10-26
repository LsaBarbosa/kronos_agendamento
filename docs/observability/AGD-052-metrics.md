# AGD-052 — Contrato compartilhado de métricas

Versão 1.1 — 2026-10-26. Base de implementação: `d453c1dc8853a6762039e2b5209065de9b0963b2`.

Este contrato descreve nomes Micrometer e nomes normalizados no exposition Prometheus. Métricas não são API de tenant nem fonte de decisão de negócio. Todos os nomes abaixo têm prefixo `agd.` no Micrometer; o Prometheus exporter normaliza pontos para sublinhados.

## Scrape e segurança

- Método/path: `GET /actuator/prometheus`, formato oficial text exposition Prometheus (OpenMetrics somente se explicitamente habilitado no scrape).
- O Actuator web expõe explicitamente `health,prometheus`; não se libera `/actuator/**`. Health, liveness e readiness permanecem públicos sem detalhes. `/actuator/metrics` não será aberto como backend de scrape.
- `/actuator/prometheus` requer bearer token de serviço dedicado `OBSERVABILITY_SCRAPE_TOKEN`, comparado em tempo constante, sem sessão/cookie e com comportamento fail-closed quando ausente. A rede privada deve permitir conexão apenas do Prometheus de ambiente; a porta não deve ser publicada para rede pública/host por padrão.
- Usuário OIDC `ROLE_TENANT_ADMIN`, mesmo com associação ativa e sessão válida, não autoriza scrape. Não existe filtro/label por tenant: séries de negócio são agregadas, razão adicional para não encaminhar esse endpoint à UI administrativa.
- Prometheus faz pull por instância da aplicação. Intervalo de scrape esperado: 15 s. Métricas de processo usam os rótulos `job`/`instance` atribuídos pelo Prometheus, não label de aplicação próprio.

## Convenções de unidade e labels

- Contadores são monotônicos por processo; em Prometheus terminam em `_total` (conforme tipo de meter); dashboards aplicam `rate`/`increase` e somam instâncias somente para contadores de eventos.
- Durações usam `Timer`/segundos em Prometheus; gauges de idade são segundos. Backlog e pendências são contagens inteiras não negativas.
- Labels permitidos são enums finitos indicados abaixo. Proibidos: `tenantId`, slug/nome/telefone, IDs de reservas, IDs de usuário, referência externa, token, chave de idempotência, payload, URL/rota bruta, query string, exception message ou qualquer valor originado de usuário/provedor.
- Labels de duração da API: `method`, `uri` (template normalizado), `status`, `outcome`, `exception` (classe controlada/`none`). Se não existir rota conhecida, `uri=UNKNOWN`; nunca a URL solicitada literal.
- Labels de reservas: `channel={public,admin}`, `result={created,replay,conflict,rejected}`. Resultado `replay` não é novo efeito. Labels de worker, source, dependency, execution result e reconciliation são listas finitas descritas abaixo.

## Métricas da API

| Micrometer | Prometheus (família principal) | Tipo/unidade | Labels | Significado e fonte |
|---|---|---|---|---|
| `http.server.requests` | `http_server_requests_seconds` (`_count`, `_sum`, buckets se habilitados) | Timer, segundos | `method,uri,status,outcome,exception` | Requisições MVC concluídas por rota normalizada; contador = volume, outcome/status = erros, distribuição = latência. Por processo. Rotas desconhecidas usam `UNKNOWN`. |

Buckets de latência são configuração de instrumentação da aplicação e permanecerão finitos; valor inicial: limites Prometheus padrão de Micrometer, sem prometer SLA. Não registrar path/query de URL de maneira literal.

## Reservas

| Micrometer | Prometheus | Tipo/unidade | Labels | Significado |
|---|---|---|---|---|
| `agd.appointments.booking.commands` | `agd_appointments_booking_commands_total` | Counter/eventos | `channel,result` | Tentativas terminadas em created/replay/conflict/rejected. Somatório por processo. |
| `agd.appointments.booking.attempts` | `agd_appointments_booking_attempts_total` | Counter/tentativas | `channel={public,admin}` | Entradas nos caminhos de criação pública e criação administrativa; não inclui reagendamento/cancelamento como nova reserva. |
| `agd.appointments.booking.effects.confirmed` | `agd_appointments_booking_effects_confirmed_total` | Counter/efeitos | `channel,effect` | Efeito confirmado pelo fluxo proprietário após persistência: `created,rescheduled,cancelled,outcome_recorded`. Replay não incrementa. Não participa da transação/decisão; o incremento é feito após retorno de resultado autoritativo. |

Replay, conflito e rejeição não são efeitos confirmados. A instrumentação não refaz validação, não altera idempotência e não promete durabilidade do próprio contador em crash.

## Workers e projeções persistidas

Workers permitidos: `outbox`, `reminders`, `notifications`, `subscription_dunning`, `trial_expiry`, `exports`. A inclusão de worker exige atualizar este contrato. `reminders` corresponde ao processamento de notificação lembrete já existente, sem inventar entrega.

| Micrometer | Prometheus | Tipo/unidade | Labels | Significado e fonte |
|---|---|---|---|---|
| `agd.worker.executions` | `agd_worker_executions_total` | Counter/execuções | `worker,result={completed,failed,no_work}` | Ciclos observados pelos schedulers existentes; `no_work` é ciclo válido sem itens. Não afirma entrega externa. Por processo. |
| `agd.worker.heartbeat.timestamp` | `agd_worker_heartbeat_timestamp_seconds` | Gauge, epoch seconds | `worker` | Última execução do ciclo de polling, inclusive sem trabalho. Não é progresso. Ausência de série significa nunca amostrado/instrumentado, não zero saudável. |
| `agd.worker.progress.timestamp` | `agd_worker_progress_timestamp_seconds` | Gauge, epoch seconds | `worker` | Última mudança persistida ou etapa completada pelo worker. Não avança em ciclo no-work. Ausência de amostra é distinta de valor zero. |
| `agd.worker.configuration` | `agd_worker_configuration` | Gauge, 0/1 | `worker,state={enabled,disabled}` | Estado efetivo one-hot, incluindo defaults já existentes. `agd.worker.configuration.present` (`agd_worker_configuration_present`, label `worker`) vale 1 quando a propriedade foi explicitamente configurada e 0 quando foi usado o default; assim ausência não se confunde com disabled. Config inválida mantém o comportamento de startup existente. |
| `agd.workers.backlog.eligible` | `agd_workers_backlog_eligible` | Gauge, itens | `worker` | Itens persistidos atualmente elegíveis em backlog da fonte proprietária. Escopo global do banco, snapshot amostrado e duplicado entre processos. |
| `agd.workers.backlog.overdue` | `agd_workers_backlog_overdue` | Gauge, itens | `worker` | Subconjunto vencido nas projeções que calculam vencimento (reminders e subscription_dunning); ausência para outros workers significa não implementado, não zero. |
| `agd.workers.backlog.oldest_age` | `agd_workers_backlog_oldest_age_seconds` | Gauge, segundos | `worker` | Idade do item elegível mais antigo na fonte; 0 somente quando consulta bem-sucedida confirma backlog vazio. Escopo global do banco. |
| `agd.workers.backlog.capped` | `agd_workers_backlog_capped` | Gauge, 0/1 | `worker` | 1 quando a consulta limitada observou ao menos um registro além dos 10.000 considerados; contagem e idade devem ser lidas como limitadas quando capped=1. |
| `agd.worker.execution.duration` | `agd_worker_execution_duration_seconds` | Timer, segundos | `worker` | Duração dos ciclos instrumentados, inclusive `no_work` e falha. |
| `agd.worker.items.processed` | `agd_worker_items_processed_total` | Counter/itens | `worker` | Itens retornados como processados pelo método proprietário; não equivale a entrega externa. |
| `agd.metrics.source.available` | `agd_metrics_source_available` | Gauge, 0/1 | `source` | Última coleta da projeção: `postgresql`, `appointments`, `outbox`, `notifications`, `subscriptions`, `exports`. 0 é falha observada; não é contagem. |
| `agd.metrics.source.last_success.timestamp` | `agd_metrics_source_last_success_timestamp_seconds` | Gauge, epoch seconds | `source` | Instante UTC da última coleta completa. Nunca removido/zerado em falha. Ausente indica nunca coletado. |
| `agd.metrics.source.collection.duration` | `agd_metrics_source_collection_duration_seconds` | Timer, segundos | `source,result` | Duração da coleta e resultado `success,timeout,error`; erro não altera último valor bom dos gauges de negócio. |

Consultas são feitas pelos ciclos periódicos dos workers proprietários, nunca no caminho do scrape Prometheus. A frequência é a cadência já configurada do worker (outbox/notificações/exports têm polling curto configurável; dunning usa 60 s por default); não há collector global de 30 s. As projeções definem timeout SQL de 2 s e limitam a amostra a 10.001 linhas. Uma falha marca `sampled=1,available=0`, conserva gauges do último sucesso e freshness indica staleness. No startup, `sampled=0,available=0` significa ainda sem amostra; `last_success` só é registrada após sucesso. Falha não substitui valor por zero.

Backlog/idade são projeções globais repetidas em cada processo porque as instâncias compartilham o mesmo banco. O scrape mantém `instance` para diagnóstico; consultas de cluster usam `max by (worker)` (ou escolha de uma instância saudável) para gauge global, nunca `sum`. Worker heartbeat/progress e contadores são por processo; Prometheus mantém `instance` e pode agregar corretamente os counters com `sum`/`rate`. PromQL não deve confundir o timestamp mais recente de qualquer worker com saúde de todas as instâncias.

## Dependências e reconciliação

| Micrometer | Prometheus | Tipo | Labels | Significado |
|---|---|---|---|---|
| `agd.dependency.configuration` | `agd_dependency_configuration` | Gauge 0/1 | `dependency,state={configured,missing_configuration}` | Estado one-hot para `outbox_destination,whatsapp,payment_provider,payment_reconciler,export_storage`. Não afirma disponibilidade operacional nem sucesso de negócio. |
| `agd.dependency.available` | `agd_dependency_available` | Gauge 0/1 | `dependency={outbox_destination,whatsapp,payment_provider,payment_reconciler,export_storage}` | Última verificação/uso observado: disponível (1) ou falha (0). A série nasce na primeira observação; ausência é “sem amostra”. Provedores sem health-check não recebem sondagem sintética. |
| `agd.reconciliation.pending` | `agd_reconciliation_pending` | Gauge, itens | `subsystem={outbox}` | Registros persistidos na outbox com estado de reconciliação requerido. Projeção financeira de billing não implementada nesta versão. Sem criar itens. |
| `agd.reconciliation.outcome_unknown` | `agd_reconciliation_outcome_unknown` | Gauge, itens | `subsystem={payments,notifications}` | Resultados ambíguos persistidos nos snapshots de dunning e notificações. Não cobre exportações/checkout sem projeção disponível e não prova que houve reconciliação. |

As fontes de projeção desta versão são `outbox,notifications,subscriptions,exports`; `postgresql` e appointments não têm amostras independentes nesta versão. Se uma fonte falhar, fica identificada em `agd.metrics.source.*`; se um port estiver ausente, a lacuna é documentada. Não criar adapter sintético, não fazer `SELECT *`, não denominar aceite de transporte como entrega e não converter alerta em item de reconciliação.

## Estado, falha e interpretação

- Worker `disabled` indica polling desligado; `enabled` indica somente que o scheduler existente está habilitado. `configuration.present=0` identifica defaults aplicados. Para dependências, `missing_configuration` é estado separado. Nenhum estado prova contratação, envio ou saúde do provedor.
- Heartbeat avança em cada polling; progresso somente após avanço persistido; `no_work` é um ciclo válido sem itens. Um worker parado é identificado por heartbeat stale; backlog antigo é derivado de gauge elegível + idade. Ausência de amostra não será inicializada em zero para aparência saudável.
- Erro/timeout de consulta atualiza estado da fonte e não os dados de negócio com zero. `last_success` permite freshness. Coletas são isoladas por fonte.
- API/process counters são eventuais, locais ao processo e não duráveis. Projeções PostgreSQL são snapshots READ COMMITTED individuais, não snapshot atômico conjunto entre módulos; cada série tem seu próprio instante de coleta.
- Nenhuma métrica altera transação, estado de reserva, retry, lease/fencing, auditoria, entrega ou reconciliação.

## Consultas, dashboard e alertas de demonstração

Exemplos de PromQL pertencem ao handoff/runbook da infraestrutura. Como referência inicial não aprovada: `rate(http_server_requests_seconds_count[5m])`, taxa de respostas `outcome="SERVER_ERROR"`, p95 a partir de buckets se configurados, backlog e idade por worker com `max by (worker)`, e stale heartbeat por `time() - max by (instance,worker)(agd_worker_heartbeat_timestamp_seconds)`. Limiar de demonstração não é SLO, SLA nem política de plantão; owner operacional permanece a definir.

Dashboard/alerta não comprova garantia funcional ou entrega externa. Falha financeira ambígua só satisfaz o aceite “abre item de reconciliação” se a projeção contar o item persistido existente; métrica/alerta por si só não o abre.
