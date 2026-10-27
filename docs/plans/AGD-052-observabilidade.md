# AGD-052 — Observabilidade e runbooks

## Identificação e objetivo

- História: AGD-052, P0, tamanho L. Base revisada: `d453c1dc8853a6762039e2b5209065de9b0963b2`.
- Objetivo: instrumentar API, reservas e workers com métricas de cardinalidade controlada, coletar projeções persistidas com limites e timeout, proteger o scrape de métricas agregadas e integrar aplicação → Prometheus → consultas/dashboard/alertas em ambiente descartável.
- Contrato compartilhado: [`docs/observability/AGD-052-metrics.md`](../observability/AGD-052-metrics.md).
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
- [x] Stack de infra isolada integrada do commit autorizado `8969c129c8f61297190aa45cb42e082a12aa9d13` como `f2c95be`; validação de regras/dashboards e smoke passaram.
- [x] Aplicação containerizada, scrape protegido, Prometheus queries, seis dashboards e alerta de aplicação indisponível foram exercitados em projeto descartável independente. Nenhum Compose compartilhado foi alterado.
- [ ] PR draft baseado em `agd-050a-retention-assessment`, dependente do PR #28; publicação e checks remotos do SHA final.

## Validação

| Critério | Procedimento | Esperado | Observado |
|---|---|---|---|
| Rotas sem PII e labels estáveis | `DatabaseBootstrapIT` faz scrape real e envia slug/URI sentinela | Passou: URI literal não aparece; `/actuator/prometheus` protegido | E2E com tráfego sintético, sem PII |
| Reserva, replay e conflito | `BookingMetricsTest` e suíte `verify` | Passou: replay distinto de novo efeito e resultados controlados | Contadores são por processo e não duráveis em crash |
| Projeções de workers | `NotificationPipelineIT`, `ExportLifecycleIT`, `IdempotencyOutboxIT`, `SubscriptionTrialIT`, `TenantPersistenceIT` | Passou em PostgreSQL real; consultas limitadas/timeouts e filtros proprietários | Snapshot individual por fonte, não atômico entre módulos |
| Segurança de métricas | `SecurityBoundaryTest`, `DatabaseBootstrapIT` | Passou: admin sem acesso, sem/errado bearer negado, token técnico aceito; rotas Actuator não autorizadas permanecem fechadas | Segredo de serviço precisa ser provisionado fora do Git |
| Pipeline integrado | `validate`, `smoke` e execução descartável com app/Postgres/Prometheus/Grafana | Passou: scrape, séries consultáveis, dashboards, regra e alerta firing após parada sintética | Sem Alertmanager/destinatário externo; limiares não são SLO |

## Descobertas e decisões

- Decisão técnica nesta entrega: scrape Prometheus em formato oficial Micrometer/Prometheus; rota exata com autenticação de serviço e fail-closed. O segredo é dedicado, nunca emitido como métrica/log e não concede acesso administrativo.
- Métricas de HTTP e execução são por processo e cada scrape target é distinto. Gauges derivados de tabelas compartilhadas são uma projeção global replicada; agregações usam `max`/uma amostra válida por worker, não soma por instância.
- Métricas são observacionais. Pendências financeiras ambíguas só serão descritas como reconciliadas se a fonte persistida existente disser isso; se uma integração não possui mecanismo de reconciliação, o gap será documentado sem simular um item.
- Parâmetros iniciais de dashboard/alertas são exemplos técnicos, não SLO, escala ou compromisso operacional aprovado.

## Encerramento ou retomada

Implementação e integração seguem pendentes neste plano. Retomar pelos snapshots/ports proprietários descritos acima, respeitando ownership de operações. Não iniciar outra história nem alterar configurações de produção.
