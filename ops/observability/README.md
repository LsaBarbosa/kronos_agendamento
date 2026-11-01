# AGD-052 — stack local de observabilidade

Esta stack é isolada e de desenvolvimento. Prometheus `3.15.1` e Grafana `13.2.4`
usam tags e digests oficiais de índice multiarch, com manifests `linux/amd64` e
`linux/arm64` verificados em 2026-10-26. Não use tags móveis.

O contrato integrado v1.4 em `docs/observability/AGD-052-metrics.md` define o scrape
`GET /actuator/prometheus`, token bearer dedicado e nomes/unidades/labels usados nos
dashboards e regras desta pasta. Ele foi publicado originalmente em
`f1e595771cf436db8c1abafa62c5b5ded76a7934` e faz parte da base integrada desta
correção.

## Fronteira local

- Projeto Compose: `kronos-agd052-observability`.
- Rede bridge própria do projeto e volumes com namespace do projeto; não há
  destinos externos configurados. O isolamento de acesso ao host vem dos binds
  `127.0.0.1`; a rede permite publicação dessas portas pelo Docker.
- Portas: Prometheus `127.0.0.1:19090`; Grafana `127.0.0.1:13000`.
- Signup e acesso anônimo do Grafana desativados; senha aleatória local.
- Relatórios, verificações de atualização e pré-instalação/atualização de plugins
  estão desativados. A imagem oficial ainda pode fazer consultas internas de
  atualização de detectores; isso não é um destino de integração configurado.
- Sem Alertmanager: as regras ficam visíveis em Prometheus/Grafana e são testadas
  localmente; não há roteamento para destinatário externo.
- Não anexar ao Compose compartilhado nem expor portas em `0.0.0.0`.

## Credenciais e target de desenvolvimento

Mantenha todos os arquivos em diretório privado fora do repositório. Exemplo de
bootstrap local descartável:

```bash
state="${XDG_STATE_HOME:-$HOME/.local/state}/kronos-agd052-observability"
install -d -m 700 "$state/targets"
umask 077
openssl rand -hex 32 > "$state/scrape-token"
openssl rand -hex 24 > "$state/grafana-password"
cat > "$state/targets/application.yml" <<'EOF'
- targets: ["host.docker.internal:8080"]
EOF
cat > "$state/compose.env" <<EOF
AGD052_GRAFANA_ADMIN_USER=agd052-local-admin
AGD052_GRAFANA_ADMIN_PASSWORD=$(cat "$state/grafana-password")
AGD052_SCRAPE_TOKEN_FILE=$state/scrape-token
AGD052_PROMETHEUS_TARGET_DIR=$state/targets
AGD052_PROMETHEUS_PORT=19090
AGD052_GRAFANA_PORT=13000
EOF
chmod 600 "$state/compose.env" "$state/grafana-password"
# Prometheus runs without root; the parent directory stays private (0700).
chmod 644 "$state/scrape-token"
```

Configure o mesmo valor de `scrape-token` no processo local do backend como
`OBSERVABILITY_SCRAPE_TOKEN` por mecanismo local seguro. Não o mostre em terminal,
logs ou Git. O exemplo de target só é válido se backend local escutar em `8080` e
estiver configurado com esse token. A porta do scrape não é publicada pelo Compose;
somente Prometheus acessa o endpoint na rede privada.

Para iniciar, na raiz do repositório:

```bash
docker compose --project-name kronos-agd052-observability \
  --env-file "$state/compose.env" -f compose.observability.yaml up -d
```

Grafana: `http://127.0.0.1:13000`; Prometheus: `http://127.0.0.1:19090`.
`docker compose ... down` preserva os volumes nomeados. A validação descartável
`scripts/validate-agd052-observability.sh smoke` usa nome de projeto aleatório,
portas efêmeras e credenciais temporárias, verifica os labels de containers,
volumes e redes antes de iniciar e remove somente recursos daquele projeto novo.

## Dados e interpretação

- `application.yml` versionado contém uma lista vazia. Target ausente significa não
  configurado/não consultado; não é API saudável nem zero.
- O contrato v1.4 tem projeções independentes somente para `outbox`,
  `notifications`, `subscriptions` e `exports`. `postgresql` e `appointments` não
  são fontes esperadas. Regras diferenciam série `sampled` ausente, `sampled=0`,
  falha observada (`sampled=1,available=0`) e último sucesso obsoleto; exigem o
  worker correspondente habilitado. Coletor desabilitado não dispara alerta.
- Secret/token e target de dev podem ficar em `$state` fora do Git.
- Scrape exige o token em arquivo, usa endpoint e labels do contrato, sem tenant ou
  PII. Não publicar `/actuator/metrics` nem abrir `/actuator/**`.
- Métricas de API e contadores por processo usam `rate`/`increase` agregando séries.
- Gauges globais duplicados por instância (backlog, idade, reconciliações) usam
  `max by(...)`, nunca `sum`.
- Worker sem trabalho ou explicitamente desabilitado não dispara alerta de progresso
  ou backlog. Aceite do transporte não é entrega; transporte aceito não é pagamento.
- Métricas são observabilidade, nunca autoridade para mudar reserva/retry/cobrança.
- Alert thresholds desta configuração são valores iniciais demonstrativos, não SLO,
  SLA, política comercial ou aprovação de plantão. Os papéis existem como destino
  funcional; pessoas, escala e canal de plantão estão pendentes.

## Validação

```bash
scripts/validate-agd052-observability.sh validate
scripts/validate-agd052-observability.sh smoke
scripts/validate-agd052-observability.sh isolation-test
```

`validate` roda `docker compose config`, `promtool check config`, `promtool check
rules`, `promtool test rules`, parse JSON e conferência dos runbooks. `smoke` repete
esses gates, usa projeto aleatório e portas Docker efêmeras ligadas a loopback,
aguarda readiness por polling, confirma datasource e seis dashboards pela API
Grafana e limpa somente recursos próprios. `isolation-test` cria container parado,
volume e rede sentinela descartáveis de outro projeto, roda o smoke e prova que os
três continuam presentes até a limpeza sentinela. Fixtures não comprovam scrape
integrado do backend; isso exige target válido e séries reais do contrato.
