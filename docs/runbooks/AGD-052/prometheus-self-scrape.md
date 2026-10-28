# Runbook: saúde do scrape local do Prometheus

## Escopo e alertas

Aplica-se aos alertas locais `PrometheusSelfScrapeFailed` (warning, `up == 0` por
2 minutos), `PrometheusSelfScrapeStale` (warning, amostra com mais de 45 segundos
por 30 segundos) e `PrometheusSelfScrapeMissing` (critical, ausência por 2 minutos
mais 1 minuto de persistência). Responsável funcional: papel de operações de
plataforma. A designação de pessoas, escala e canal de plantão não foi aprovada.

Essas regras detectam somente o alvo interno do Prometheus e dependem do próprio
processo ainda avaliar alertas. Não demonstram disponibilidade da API; queda total
do coletor exige monitor independente, não incluído nesta stack local.

## Diagnóstico

1. Confirme o alerta, instante, `job` e `instance` no Prometheus local.
2. Consulte `/targets` e `/-/ready` no endereço loopback configurado.
3. Verifique `docker compose --project-name kronos-agd052-observability -f compose.observability.yaml ps`
   e os logs do serviço Prometheus desse projeto apenas.
4. Compare a última amostra com a configuração versionada e o estado do volume
   `kronos-agd052-observability_prometheus-data`.
5. Colete horário UTC, alerta/labels não sensíveis, estado do serviço e trecho
   redigido dos logs. Não anexe configuração com credenciais nem dados de tenants.

## Ações permitidas

- Para o stack local descartável, corrigir erro sintático de configuração e
  reiniciar somente o serviço Prometheus do projeto AGD-052.
- Conferir espaço/permissões do volume próprio e estado da porta local.
- Se o erro for reproduzível, preservar configuração e logs redigidos antes de
  recriar o projeto de validação.

Não remova dados persistidos fora do volume exclusivo desta stack. Não reinicie
Compose compartilhado ou serviços de ambiente real.

## Escalonamento

Escalone para o papel de operações de plataforma após confirmar indisponibilidade
persistente ou perda de dados. A pessoa/canal responsável deve ser designada antes
de qualquer uso operacional além do ambiente local. Ausência do próprio Prometheus
não pode ser resolvida por este alerta; é necessário monitor externo aprovado.
