# Runbook: scrape da aplicação indisponível

## Alertas, limiares e responsabilidade

- `KronosApplicationScrapeUnavailable`: target configurado retorna `up=0` por
  2 minutos; **critical**. Responsável: papel de operações de plataforma.
- Estado de fonte observado em `agd_metrics_source_available=0` por 1 minuto:
  **warning**, mesmo papel.
- Estado/timestamp de fonte ausente por 3 minutos ou último sucesso com mais de
  120 segundos por 1 minuto: **warning**, mesmo papel.

Os limiares são valores operacionais iniciais de demonstração, não SLO/SLA. A
designação de pessoa, escala e canal de plantão está pendente.

## Diagnóstico

1. Confirme `job`, `instance`, `source`, timestamp e estado do alerta sem divulgar
   labels não permitidos.
2. Consulte `/targets` no Prometheus e confirme o target file-service-discovery
   usado. Verifique path `/actuator/prometheus` e o status HTTP.
3. Confira o token de serviço por meio do gestor local autorizado, sem copiar seu
   valor para logs, terminal compartilhado ou evidência. Ausência/invalidez causa
   resposta fail-closed e target indisponível.
4. Confira readiness, logs redigidos da API e erro de fonte (`timeout`, `error` ou
   `success`) apenas por labels finitos do contrato.
5. Registre UTC, nome do alerta, target e categoria de falha; não anexe bearer,
   cookie, request completo, tenant, URL literal ou mensagem de exceção.

## Ações permitidas

- No ambiente local descartável, corrigir target/credencial sintética e recarregar
  apenas este projeto de observabilidade.
- Tratar falha PostgreSQL com o runbook de banco já aprovado e preservar as fontes
  de métricas independentes; uma fonte falha não autoriza zerar as demais.
- Para serviço real, validar rede privada e configuração de token por procedimento
  operacional aprovado antes de alterar endpoints.

Não transformar `source.available=0`, ausência de amostra ou timeout em contagem
zero. Não executar operação de negócio, retry financeiro ou notificação por causa
deste alerta.

## Escalonamento

Escalone ao papel de operações de plataforma após 2 minutos de target indisponível,
ou ao responsável técnico da fonte quando somente uma projeção falhar por 3 minutos.
Os nomes de responsáveis e plantão continuam pendentes. Este alerta depende de o
Prometheus ainda estar ativo; queda total do coletor requer monitor externo.
