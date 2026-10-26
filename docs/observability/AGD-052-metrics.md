# AGD-052 — Contrato compartilhado de métricas

Versão inicial — 2026-10-26. Base de implementação: `d453c1dc8853a6762039e2b5209065de9b0963b2`.

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
| `agd.appointments.booking.effects.confirmed` | `agd_appointments_booking_effects_confirmed_total` | Counter/efeitos | `channel,effect` | Efeito confirmado pelo fluxo proprietário após persistência: `created,rescheduled,cancelled,outcome_recorded`. Replay não incrementa. Não participa da transação/decisão; o incremento é feito após retorno de resultado autoritativo. |

Replay, conflito e rejeição não são efeitos confirmados. A instrumentação não refaz validação, não altera idempotência e não promete durabilidade do próprio contador em crash.

## Workers e projeções persistidas

Workers permitidos: `outbox`, `reminders`, `notifications`, `subscription_dunning`, `trial_expiry`, `exports`. A inclusão de worker exige atualizar este contrato. `reminders` corresponde ao processamento de notificação lembrete já existente, sem inventar entrega.

| Micrometer | Prometheus | Tipo/unidade | Labels | Significado e fonte |
|---|---|---|---|---|
| `agd.worker.executions` | `agd_worker_executions_total` | Counter/execuções | `worker,result` | Ciclos `started,completed,failed,no_work`; não afirma entrega externa. Por processo. |
| `agd.worker.heartbeat.timestamp` | `agd_worker_heartbeat_timestamp_seconds` | Gauge, epoch seconds | `worker` | Última execução do ciclo de polling, inclusive sem trabalho. Não é progresso. Ausência de série significa nunca amostrado/instrumentado, não zero saudável. |
| `agd.worker.progress.timestamp` | `agd_worker_progress_timestamp_seconds` | Gauge, epoch seconds | `worker` | Última mudança persistida ou etapa completada pelo worker. Não avança em ciclo no-work. Ausência de amostra é distinta de valor zero. |
| `agd.worker.configuration` | `agd_worker_configuration` | Gauge, 0/1 | `worker,state` | Estado one-hot `enabled,disabled,missing_configuration`. Uma config explicitamente ausente não se confunde com desabilitado. Config inválida mantém o comportamento fail-closed de startup já definido e não é traduzida em saúde. |
| `agd.workers.backlog.eligible` | `agd_workers_backlog_eligible` | Gauge, itens | `worker` | Itens persistidos atualmente elegíveis em backlog da fonte proprietária. Escopo global do banco, snapshot amostrado e duplicado entre processos. |
| `agd.workers.backlog.oldest_age` | `agd_workers_backlog_oldest_age_seconds` | Gauge, segundos | `worker` | Idade do item elegível mais antigo na fonte; 0 somente quando consulta bem-sucedida confirma backlog vazio. Escopo global do banco. |
| `agd.metrics.source.available` | `agd_metrics_source_available` | Gauge, 0/1 | `source` | Última coleta da projeção: `postgresql`, `appointments`, `outbox`, `notifications`, `subscriptions`, `exports`. 0 é falha observada; não é contagem. |
| `agd.metrics.source.last_success.timestamp` | `agd_metrics_source_last_success_timestamp_seconds` | Gauge, epoch seconds | `source` | Instante UTC da última coleta completa. Nunca removido/zerado em falha. Ausente indica nunca coletado. |
| `agd.metrics.source.collection.duration` | `agd_metrics_source_collection_duration_seconds` | Timer, segundos | `source,result` | Duração da coleta e resultado `success,timeout,error`; erro não altera último valor bom dos gauges de negócio. |

Consultas são feitas por collector periódico, nunca no caminho de request Prometheus. Frequência inicial de snapshot: 30 s; cada query é indexável, limitada por query timeout de 2 s e retorna agregados, sem carregar linhas. Uma fonte falha sem interromper as fontes seguintes; marca `available=0`, conserva os gauges do último sucesso e deixa freshness indicar staleness. O scrape em si continua respondendo, sem transformar indisponibilidade em zero. Uma coleta SQL que não executou nunca não gera amostra de zero.

Backlog/idade são projeções globais repetidas em cada processo porque as instâncias compartilham o mesmo banco. O scrape mantém `instance` para diagnóstico; consultas de cluster usam `max by (worker)` (ou escolha de uma instância saudável) para gauge global, nunca `sum`. Worker heartbeat/progress e contadores são por processo; Prometheus mantém `instance` e pode agregar corretamente os counters com `sum`/`rate`. PromQL não deve confundir o timestamp mais recente de qualquer worker com saúde de todas as instâncias.

## Dependências e reconciliação

| Micrometer | Prometheus | Tipo | Labels | Significado |
|---|---|---|---|---|
| `agd.dependency.configuration` | `agd_dependency_configuration` | Gauge 0/1 | `dependency,state` | Estado de configuração one-hot para `postgresql,whatsapp,payment_provider,export_storage`; `configured,missing_configuration`. Não afirma disponibilidade operacional nem sucesso de negócio. |
| `agd.dependency.available` | `agd_dependency_available` | Gauge 0/1 | `dependency` | Última verificação/uso observado: disponível (1) ou falha (0). Ausência é “sem amostra”, não disponibilidade. Provedores sem health-check não recebem sondagem sintética; disponibilidade só deriva de resposta real ou health-check explicitamente autorizado. |
| `agd.reconciliation.pending` | `agd_reconciliation_pending` | Gauge, itens | `subsystem,state` | Registros persistidos elegíveis para reconciliação: `payments` com estados autoritativos existentes; `exports`/`notifications` somente se estado persistido próprio suportar. Sem criar itens. |
| `agd.reconciliation.outcome_unknown` | `agd_reconciliation_outcome_unknown` | Gauge, itens | `subsystem` | Resultados ambíguos ainda presentes no mecanismo persistido proprietário, incluindo cobrança quando suportada pelo snapshot de dunning. |

Se uma fonte não tiver port de snapshot seguro, o estado será `source.available=0`/amostra ausente e a limitação será documentada. Não criar adapter sintético, não fazer `SELECT *`, não denominar aceite de transporte como entrega e não converter alerta em item de reconciliação. A existência de mecanismo financeiro de reconciliação é verificada no módulo de billing; outros subsistemas sem mecanismo serão declarados como lacuna.

## Estado, falha e interpretação

- Worker `disabled` é configuração explícita falsa; `missing_configuration` indica que requisito de configuração/transport está ausente; `enabled` indica somente que o polling está habilitado. Nenhum estado prova que o provedor está contratado, enviando ou saudável.
- Heartbeat avança em cada polling; progresso somente após avanço persistido; `no_work` é um ciclo válido sem itens. Um worker parado é identificado por heartbeat stale; backlog antigo é derivado de gauge elegível + idade. Ausência de amostra não será inicializada em zero para aparência saudável.
- Erro/timeout de consulta atualiza estado da fonte e não os dados de negócio com zero. `last_success` permite freshness. Coletas são isoladas por fonte.
- API/process counters são eventuais, locais ao processo e não duráveis. Projeções PostgreSQL são snapshots READ COMMITTED individuais, não snapshot atômico conjunto entre módulos; cada série tem seu próprio instante de coleta.
- Nenhuma métrica altera transação, estado de reserva, retry, lease/fencing, auditoria, entrega ou reconciliação.

## Consultas, dashboard e alertas de demonstração

Exemplos de PromQL pertencem ao handoff/runbook da infraestrutura. Como referência inicial não aprovada: `rate(http_server_requests_seconds_count[5m])`, taxa de respostas `outcome="SERVER_ERROR"`, p95 a partir de buckets se configurados, backlog e idade por worker com `max by (worker)`, e stale heartbeat por `time() - max by (instance,worker)(agd_worker_heartbeat_timestamp_seconds)`. Limiar de demonstração não é SLO, SLA nem política de plantão; owner operacional permanece a definir.

Dashboard/alerta não comprova garantia funcional ou entrega externa. Falha financeira ambígua só satisfaz o aceite “abre item de reconciliação” se a projeção contar o item persistido existente; métrica/alerta por si só não o abre.
