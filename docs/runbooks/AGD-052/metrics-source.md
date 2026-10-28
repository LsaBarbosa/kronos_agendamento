# Runbook: projeção de métricas ausente, falha ou obsoleta

Aplica-se às regras `MetricsSourceSampleMetricMissing`,
`MetricsSourceNeverSampled`, `MetricsSourceUnavailable`,
`MetricsSourceLastSuccessMissing` e `MetricsSourceLastSuccessStale`.

## Limiares e responsabilidade

- Série `sampled` ausente por 3 minutos para fonte implementada e worker enabled:
  warning (`platform-operations`).
- `sampled=0` por 3 minutos: primeira coleta ainda não aconteceu; warning.
- `sampled=1, available=0` por 1 minuto: falha observada; warning.
- `sampled=1` sem timestamp de sucesso por 3 minutos: nenhum ciclo completou
  sucesso; warning.
- Último sucesso com mais de 120 segundos por 1 minuto: freshness obsoleta;
  warning.

São alertas de demonstração, não SLO/SLA. Pessoa, escala e canal de plantão
continuam pendentes.

## Fontes e estados

O contrato v1.4 implementa projeções independentes apenas para `outbox`,
`notifications`, `subscriptions` e `exports`. Os alertas associam essas fontes
aos workers `outbox`, `notifications`, `subscription_dunning` e `exports`.
`postgresql` e `appointments` não têm projeções independentes nesta versão e não
são fontes esperadas pelas regras. Não invente série para elas.

- **Worker disabled:** não é coletor elegível; nenhum alerta de fonte é esperado.
  `outbox` e `exports` podem estar desabilitados por default.
- **Worker enabled, sampled=0:** inicialização/nenhuma primeira tentativa; após
  três minutos gera `MetricsSourceNeverSampled`, separado de falha.
- **Worker enabled sem série sampled:** discrepância do contrato/instrumentação,
  `MetricsSourceSampleMetricMissing` após três minutos.
- **sampled=1 e available=0:** falha/timeout observado, `MetricsSourceUnavailable`.
- **sampled=1 sem last_success:** tentativa ocorreu, mas ainda não houve sucesso;
  `MetricsSourceLastSuccessMissing` após três minutos.
- **last_success antigo:** freshness perdida, `MetricsSourceLastSuccessStale`;
  valor de negócio anterior não é convertido em zero.
- Fonte sem amostra no painel não significa contagem zero, saudável ou falha sem
  examinar scrape, estado do worker e configuração.

Uma instância de aplicação fora do scrape saudável não participa dos alertas de
fonte; investigue `KronosApplicationScrapeUnavailable` pelo runbook próprio.

## Diagnóstico

1. Confirme `job`, `instance`, `source`, `worker`, estado de configuração e o
   estágio `sampled/available/last_success`; não agregue gauges globais por soma.
2. Consulte `/targets` e valide que o scrape e bearer técnico estão funcionando,
   sem copiar token/headers para evidência.
3. Verifique se o worker correspondente está efetivamente habilitado e se a
   projeção completou no ciclo esperado. O ciclo de `no_work` continua válido.
4. Para indisponibilidade, compare resultado de coleta (`timeout|error`) e
   duração sem divulgar exception message, consulta SQL ou dados de negócio.
5. Registre UTC, nome do alerta e labels finitos. Não inclua tenant, IDs, telefone,
   referência externa, payload ou credencial.

## Ações permitidas

- Corrigir configuração/timeout após confirmação da causa e conforme o limite do
  módulo proprietário.
- Manter valores do último sucesso com indicação de freshness; nunca preencher
  série ausente ou falha com zero.
- Recarregar apenas a stack local dedicada depois de validar target/credencial
  sintéticos; em ambiente real use o procedimento aprovado pelo proprietário.

Não altere reserva, cobrança, notificação ou exportação para limpar alerta. Não
repita operação financeira ou mensagem por ausência de amostra.

## Escalonamento

Escalone ao papel de operações de plataforma e ao proprietário do worker quando
falha/ausência ultrapassar o limiar correspondente. A designação nominal e o
plantão permanecem pendentes.
