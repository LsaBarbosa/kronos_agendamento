# AGD-052 — Observabilidade e runbooks

## Identificação e objetivo

- História: AGD-052, P0, tamanho L. Base revisada: `d453c1dc8853a6762039e2b5209065de9b0963b2`.
- Objetivo: instrumentar API, reservas e workers com métricas de cardinalidade controlada, coletar projeções persistidas com limites e timeout, proteger o scrape de métricas agregadas e integrar aplicação → Prometheus → consultas/dashboard/alertas em ambiente descartável.
- Contrato compartilhado v1.4: [`docs/observability/AGD-052-metrics.md`](../observability/AGD-052-metrics.md). Preserva v1.3 e acrescenta a série `sampled` já emitida pelo backend, limita as fontes a quatro implementadas e documenta o nome Prometheus real `agd_metrics_source_last_success_timestamp`.
- Critérios: `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`, seção AGD-052.

## Referências e limites

- Requisitos relacionados: `docs/spec/app-agendamento-04-arquitetura-implementacao.md` (monólito modular, integração e observabilidade); `docs/spec/app-agendamento-05-resiliencia-controles-avancados.md` (seção 22 e resiliência); `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` (módulos, eventos, limites e privacidade); ADR-0001, ADR-0004, ADR-0008, ADR-0020, ADR-0026 e ADR-0027.
- Não altera transações, decisões de negócio, retries, leases/fencing ou entregas de provedores. Alertas não criam registros persistidos.
- Não declara SLO, política de plantão, contratação de fornecedor ou escala de produção. Limiares serão explicitamente demonstrativos.
- Não acessa métricas agregadas por sessão de administrador de tenant, nem expõe `/actuator/**` de forma ampla.

## Estado confirmado na base

- Java 21, Spring Boot 4.1.1, Actuator já presente; exposição web em `application.yml` limita-se a health. `SecurityConfiguration` permite apenas health/liveness/readiness e nega demais rotas não explicitamente autorizadas.
- `DunningMetricsSnapshot` e `SubscriptionDunningRepository.metricsSnapshot(asOf)` já consultam backlog, atraso, idade, resultado desconhecido, estados finais e tentativas técnicas. `SubscriptionDunningWorker` publica gauges a partir dessa consulta. Reaproveitar o port e a projeção existente; revisar timeout, freshness, falhas e duplicação multi-instância antes de adicionar consultas.
- `OutboxStore`, `NotificationRepository`/`NotificationWorker`, `ExportWorkerRepository`/`ExportWorker` e portas/projeções de appointments são os pontos proprietários a inspecionar antes de criar novos adaptadores. Nenhum snapshot alternativo deve cruzar diretamente os repositórios internos dos módulos.
- Dependências externas de cobrança, WhatsApp e armazenamento podem não estar configuradas. “Não configurado”, “indisponível”, “não há amostras” e “sem trabalho” permanecem estados distintos.

## Ownership

- Coordenador: backend, `backend/pom.xml`, `backend/src/main/**`, testes backend, `frontend` somente se a validação de CI exigir ajuste comum? Não previsto; contrato, `App` não será alterado, plano, `docs/observability/AGD-052-metrics.md`, documentos gerais, CI e integração final.
- Terminal 1: somente `ops/observability/**`, `compose.observability.yaml`, scripts específicos da infraestrutura de observabilidade, runbooks próprios e `docs/handoffs/AGD-052-frontend.md` se acordado. O coordenador não edita esses caminhos durante o trabalho paralelo.
- Contrato de métricas e nomes de série são compartilhados; mudanças posteriores serão anunciadas com delta e novo SHA.

## Estratégia

1. Publicar este plano e o contrato como commit inicial isolado, antes da implementação.
2. Adicionar exporter Prometheus compatível com o BOM Boot 4.1.1; manter health público e liberar explicitamente somente o endpoint de scrape. Exigir credencial técnica dedicada, fail-closed, sem reutilizar sessão/cookie de tenant; o scrape ficará na rede privada criada pela frente de operações.
3. Instrumentar volume/erro/duração de rotas por URI normalizada; instrumentar comandos de reserva distinguindo tentativa, replay, conflito e efeito confirmado sem alterar o resultado transacional.
4. Instrumentar início/fim/heartbeat/progresso/no-work e estados de configuração dos workers. Adotar os ports proprietários para snapshots de backlog/idade e pendências ambíguas, com coleta periódica, limites, timeout SQL e indicadores de disponibilidade/freshness. Falha de fonte mantém último valor apenas com fonte marcada indisponível/stale; não substitui por zero.
5. Persistir contadores de processo por instância (scrape target) e tratar projeções de banco como escopo global: usar `max`/deduplicação por worker, nunca somar o mesmo snapshot replicado por instância. Não incluir tenant em labels.
6. Acrescentar testes de minimização, cardinalidade, proteção de scrape, falhas de fonte e PostgreSQL real para as projeções; integrar com infra descartável do Terminal 1 e demonstrar consultas/alertas.
7. Atualizar STATUS, VALIDATION, TRACEABILITY e descrição do PR quando houver incremento funcional verificável.

## Progresso

- [x] 2026-10-26 — Worktree `agd-052-observabilidade` criada em `/tmp/kronos-agd052`, base `d453c1dc8853a6762039e2b5209065de9b0963b2`; estado inicial limpo.
- [x] 2026-10-26 — Plano e contrato publicados no commit inicial isolado. Implementação backend em seguida; não aguardar dashboard.
- [x] Instrumentação e proteção da API de scrape implementadas em `3481253d36e7ab58955fd56302165bdd00593f21`.
- [x] Testes de portas/projeções em PostgreSQL real e de segurança/minimização passaram em `spotless:check verify`; refinamento final da projeção de notificações passou em `NotificationPipelineIT`.
- [x] Stack inicial integrada do commit autorizado `8969c129c8f61297190aa45cb42e082a12aa9d13` como `f2c95be`; a correção autorizada do Terminal 1 `9f948a99b42c8415836290b69b441f3dc5231e35` foi integrada sem incorporar a branch backend. Merge local: `52d22d924b6142ff79d24cbf0821d92a9b793c05`.
- [x] Validação integrada da stack corrigida: `validate`, `smoke`, `isolation-test`, `bash -n` e `git diff --check` passaram. Regras promtool exercitam coletor disabled, série ausente/nunca amostrada, falha observada e last_success obsoleto.
- [x] Prova live descartável: PostgreSQL 16.13 com migrations V1–V45; aplicação Spring Boot 4.1.1/JDK 21; Prometheus 3.15.1 e Grafana 13.2.4 na rede privada própria. `/actuator/prometheus` respondeu 401 sem bearer e 200 com token sintético; query PromQL observou `up=1` e contador real de `GET /api/v1/csrf`; seis dashboards foram carregados. Com a regra alinhada ao nome exportado, configuração saudável resultou em zero alertas firing.
- [x] Falha integrada controlada por parada do app sintético e alerta `KronosApplicationScrapeUnavailable` observado após seu `for: 2m` (ver VALIDAÇÃO).
- [x] P2 financeiro corrigido em `PostgresSubscriptionDunningRepository.metricsSnapshot`: estados de resultado ambíguo são filtrados antes do limite; V45 adiciona índice parcial sem alterar V1–V44. Regressão PostgreSQL cobre uma pendência antiga com 10.001 tentativas terminais mais recentes.
- [x] P2 de booking corrigido: `attempts` e `commands` representam somente criação; remarcação/cancelamento/conclusão/falta/correção incrementam apenas efeito confirmado, e replay não duplica efeitos. Conflitos dessas rotas não contam como conflito de criação.
- [x] JDK 21 `spotless:check verify` passou após as correções: 94 unitários/arquiteturais e 331 integrações PostgreSQL/Testcontainers, sem falhas, erros ou skips. Testes focais adicionais: 1 `BookingMetricsTest` e 174 integrações em quatro classes.
- [x] PR #28 integrado à `main` no SHA `86c1bf0bb6e0bb1d76df3eda4a99f4c15094bdf8`; App CI push `41254366298` e CodeQL push `41254366297` passaram. A branch local de #29 incorporou #28 e a main pós-merge por merges normais. O head consolidado de #29 aguarda publicação e seus próprios checks.

## Validação

| Critério | Procedimento | Esperado | Observado |
|---|---|---|---|
| Rotas sem PII e labels estáveis | `DatabaseBootstrapIT` faz scrape real e envia slug/URI sentinela | Passou: URI literal não aparece; `/actuator/prometheus` protegido | E2E com tráfego sintético, sem PII |
| Reserva, replay, efeitos e conflito | `BookingMetricsTest`, `PublicAppointmentIT` e suíte `verify` | Passou: criação incrementa comandos/tentativas; mutações só efeitos confirmados; replay não duplica; conflito de remarcação não conta como criação | Contadores são por processo e não duráveis em crash |
| Projeções de workers | `NotificationPipelineIT`, `ExportLifecycleIT`, `IdempotencyOutboxIT`, `SubscriptionTrialIT`, `TenantPersistenceIT` | Passou em PostgreSQL real; consultas limitadas/timeouts e filtros proprietários | Snapshot individual por fonte, não atômico entre módulos |
| Segurança de métricas | `SecurityBoundaryTest`, `DatabaseBootstrapIT` | Passou: admin sem acesso, sem/errado bearer negado, token técnico aceito; rotas Actuator não autorizadas permanecem fechadas | Segredo de serviço precisa ser provisionado fora do Git |
| Pipeline integrado | `validate`, `smoke`, `isolation-test` e execução descartável com app/Postgres/Prometheus/Grafana | Passou: bearer 401/200, `up=1`, contador de rota consultável, seis dashboards, zero firing saudável e alerta `KronosApplicationScrapeUnavailable` após parada sintética | Sem Alertmanager/destinatário externo; limiares não são SLO |

## Descobertas e decisões

- Decisão técnica nesta entrega: scrape Prometheus em formato oficial Micrometer/Prometheus; rota exata com autenticação de serviço e fail-closed. O segredo é dedicado, nunca emitido como métrica/log e não concede acesso administrativo.
- Métricas de HTTP e execução são por processo e cada scrape target é distinto. Gauges derivados de tabelas compartilhadas são uma projeção global replicada; agregações usam `max`/uma amostra válida por worker, não soma por instância.
- Métricas são observacionais. Pendências financeiras ambíguas só serão descritas como reconciliadas se a fonte persistida existente disser isso; se uma integração não possui mecanismo de reconciliação, o gap será documentado sem simular um item.
- Parâmetros iniciais de dashboard/alertas são exemplos técnicos, não SLO, escala ou compromisso operacional aprovado.

## Encerramento ou retomada

AGD-052 segue parcial até revisão e checks remotos no head consolidado. O gate de segurança conhecido no scan da imagem Keycloak permanece fora deste escopo e não foi alterado. Não iniciar outra história nem alterar configurações de produção.

Na atualização de 01/11/2026, foram removidos alertas incompatíveis com as séries v1.4 ausentes para exports e trial_expiry, com regressões no promtool e no validador. Detalhes da validação e da prova descartável estão em [VALIDATION](../development/VALIDATION.md) e [TRACEABILITY](../development/TRACEABILITY.md). Alertmanager, destinatários, SLO e plantão permanecem decisões pendentes.
