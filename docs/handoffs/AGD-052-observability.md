# Handoff — correções da infraestrutura AGD-052

## Branch e comparação do handoff anterior

- Worktree: `/tmp/kronos-agd052-observability-fix`
- Branch: `agd-052-observabilidade-infra-fix`
- Base integrada: `4eac76900d17175febb4ed15b46c92d52e6498b6`
- Infra original preservada em `/tmp/kronos-agd052-observability`, branch
  `agd-052-observabilidade-infra`, HEAD `8969c129c8f61297190aa45cb42e082a12aa9d13`.
- O handoff antigo foi comparado à infraestrutura integrada em
  `f2c95bebd4d13f382026dfd66b61a016affa8da4`: `git diff` sobre Compose,
  `ops/observability/**`, validador, runbooks e handoff não mostrou diferenças
  de conteúdo. Os P2 eram semânticos e estavam presentes no conteúdo integrado;
  não eram defeitos causados por SHA diferente.
- Contrato integrado usado: AGD-052 métricas v1.2 em
  `docs/observability/AGD-052-metrics.md`. Nenhum backend ou contrato foi editado
  nesta branch.

## Correções

### Estado das fontes

O contrato v1.2 só implementa projeções independentes para `outbox`,
`notifications`, `subscriptions` e `exports`; `postgresql` e `appointments` não
são fontes esperadas. Regras agora mapeiam cada fonte a seu worker e exigem
configuração `enabled` e scrape healthy. Assim:

- worker/coletor desabilitado não gera alerta de fonte, inclusive outbox/exports
  que podem estar desabilitados por default;
- `sampled=0` gera `MetricsSourceNeverSampled` após 3 min, sem ser confundido com
  falha;
- série `sampled` ausente em coletor habilitado gera regra própria após 3 min;
- `sampled=1,available=0` é falha observada e alerta após 1 min;
- ausência de timestamp de sucesso após coleta tentada e freshness acima de 120 s
  têm alertas distintos, ambos após 3 min/1 min respectivamente.

Os dashboards de workers mostram `sampled`, `available`, idade do último sucesso
e explicam as fontes suportadas. Os painéis de cobrança e exportações deixaram de
consultar `agd_reconciliation_pending{subsystem="payments"}` e séries de
reconciliação de exports que o contrato não publica; exibem apenas dependências
realmente disponíveis e explicam a lacuna. Não foram adicionadas séries de
produção para silenciar alertas; valores sintéticos existem somente em fixtures
promtool.

### Isolamento de cleanup

Cada modo `smoke` gera um nome aleatório de projeto Compose e deixa Docker alocar
portas efêmeras em binds `127.0.0.1`. Nomes de volumes e rede derivam do projeto.
Antes de subir, o script procura containers, volumes e redes com a label exata do
novo projeto e recusa qualquer colisão. Cleanup usa somente esse project name.

`isolation-test` cria um container parado, um volume e uma rede sentinela de um
projeto descartável próprio; executa o smoke em outro projeto aleatório e verifica
que o ID/estado do container e os dois recursos continuam presentes. Só então
remove os recursos que o próprio teste sentinela criou.

## Validação executada

Comandos, todos aprovados em 2026-10-27:

```bash
scripts/validate-agd052-observability.sh validate
scripts/validate-agd052-observability.sh smoke
scripts/validate-agd052-observability.sh isolation-test
bash -n scripts/validate-agd052-observability.sh
git diff --check
```

- `promtool check config`: configuração válida; 16 regras de contrato e 3 de
  plataforma válidas.
- `promtool test rules`: fixtures provaram fonte saudável, fonte não implementada
  sem alerta, coletor disabled, startup `sampled=0`, falha observada,
  `sampled` ausente, perda de freshness, disparo/não disparo dos workers e reset
  de contador.
- Parse/UID/painéis dos seis dashboards e referência existente para todos os
  runbooks passaram.
- Smoke subiu dois containers com projeto próprio, readiness passou, Grafana
  retornou datasource Prometheus e os seis dashboards. Containers, volumes e rede
  próprios foram removidos.
- Regressão de isolamento passou: container parado, volume e rede sentinela
  sobreviveram ao cleanup do smoke e foram removidos somente pelo cleanup
  sentinela.
- O Compose compartilhado e a worktree original da infraestrutura não foram
  iniciados, modificados ou limpos.

## Limites

Os testes de métricas usam fixtures; não demonstram scrape live do backend. Esse
scrape depende da configuração local autorizada do bearer token e de target real
do contrato v1.2. Dashboards sem série permanecem sem dados, sem inferir zero,
disponibilidade ou implementação.

Nenhuma revisão independente foi realizada nesta worktree; revisão sequencial
incluiu contrato, regras, fixtures e diff. Isso não representa aprovação humana.
Não foram executados image scans, E2E de aplicação nem CI nesta correção de
infraestrutura. Responsáveis nominais/plantão, limites como SLO/SLA e integração
live continuam pendentes.

## Atualização do PR #29 após AGD-050-A

- Referências: head #29 antes da atualização `6acdb6b981486b511ce11862080940c6817e6ba4`; head #28 integrado `6cf81661cdcb14f03ca098cf2eae33bed8d820de`. #28 foi mesclado em `main` como `86c1bf0bb6e0bb1d76df3eda4a99f4c15094bdf8`; App CI push `41254366298` e CodeQL push `41254366297` passaram.
- Merges normais locais em #29: #28 em `f23278f` e main pós-merge em `8cee18a9b9a5d3b34aae7c0146984ec4aaeb8482`. Sem rebase/force-push; V44/V45 e o ajuste Clock preservados sem duplicação.
- Correção adicional de contrato v1.4: removidos `ExportOutcomeUnknownPending` (sem série de exportações) e `trial_expiry` de `WorkerRepeatedExecutionFailures` (sem backlog para satisfazer a regra). Teste de regras e validação estática rejeitam essas referências sem suporte.
- Validações posteriores: `validate`, `smoke`, `isolation-test`, `bash -n scripts/validate-agd052-observability.sh`, `node --check scripts/dev/test-keycloak-browser.mjs`, `git diff --check`; todas passaram. Live proof descartável: backend Image ID `sha256:3210d32e5cb1c80d178e4b2ddea1c21a21dc12fdb53ae608f2b75a377d35d024`; endpoint técnico 401 sem bearer/200 com bearer; Prometheus consultou `up` e contador HTTP real; ambiente saudável sem firing; PostgreSQL interrompido controladamente acionou `MetricsSourceUnavailable` enquanto o scrape permaneceu disponível. Projetos exclusivos foram limpos.
- Main resultante está incorporada. PR #29 publicado draft com base `main`, diff restrito a AGD-052; head `1f136f9eb5bd65b4a79792cfc643b6a2c38cf300`. App CI push `41256735383` e PR `41256738488` passaram todos os jobs; CodeQL push `41256735380` e PR `41256738487` passaram ambos os analisadores. Estes resultados são do head indicado; uma consolidação documental posterior não altera código e requer seus próprios checks. Alertmanager/destinatários, SLO e plantão não foram aprovados. O scan Keycloak passou neste head; nenhuma política, severidade, pin ou suppression foi alterada.
