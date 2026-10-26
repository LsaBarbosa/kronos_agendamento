# Runbook: projeção de métricas ausente, falha ou obsoleta

Aplica-se às regras `MetricsSourceUnavailable`, `MetricsSourceStateMissing*`,
`MetricsSourceLastSuccessMissing*` e `MetricsSourceLastSuccessStale`. Limiar: falha
observada por 1 minuto; ausência por 3 minutos após scrape saudável; último sucesso
mais antigo que 120 segundos por 1 minuto. Severidade warning. Responsável: papel
de operações de plataforma com o módulo proprietário da fonte; designação nominal
e plantão pendentes.

## Diagnóstico

1. Separe `available=0` (falha observada), fonte sem estado (nunca/sem amostra),
   último sucesso ausente e timestamp obsoleto.
2. Verifique se o target da aplicação está healthy e se a série tem labels finitos
   `source` e `instance` definidos pelo contrato.
3. Confira a fonte proprietária (PostgreSQL, appointments, outbox, notifications,
   subscriptions ou exports) e duração/result `success|timeout|error`.
4. Colete fonte, resultado, duração, instante UTC e logs com redaction. Não inclua
   linha de negócio, tenant, IDs, telefone, referência externa ou mensagem de erro.

## Ações permitidas

- Corrigir configuração/timeout somente após confirmar a causa e o limite definido
  pelo módulo proprietário.
- Manter valores do último sucesso como obsoletos e exibir o estado da fonte; nunca
  preencher gauge faltante com zero.
- Revalidar com a consulta agregada e limitada do collector, sem consulta manual de
  registros sensíveis.

Não alterar reserva, cobrança, notificação, exportação ou estado de reconciliação
para fazer a métrica voltar ao normal. Uma coleta bem-sucedida não executa efeito
de negócio.

## Escalonamento

Escalone ao papel de plataforma e ao responsável do módulo quando falha/ausência
persistir por 3 minutos. A designação de pessoas e a política operacional de timeout
por fonte permanecem pendentes.
