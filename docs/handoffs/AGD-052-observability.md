# Handoff — infraestrutura de observabilidade AGD-052

## Revisão e integração

- Worktree: `/tmp/kronos-agd052-observability`
- Branch: `agd-052-observabilidade-infra`
- Base exata: `d453c1dc8853a6762039e2b5209065de9b0963b2`
- Contrato consumido: `f1e595771cf436db8c1abafa62c5b5ded76a7934`,
  `docs/observability/AGD-052-metrics.md` na branch do Terminal 2.
- Este contrato foi lido como artefato compartilhado; a branch do Terminal 2 e
  seu backend não foram integrados nem modificados nesta worktree. O live scrape
  não foi demonstrado.

## Artefatos desta frente

- `compose.observability.yaml`: projeto `kronos-agd052-observability`, rede e
  volumes próprios; interfaces publicadas somente em `127.0.0.1:19090` e
  `127.0.0.1:13000`; Prometheus não publica sua porta de scrape da aplicação.
- `ops/observability/prometheus/`: scrape autenticado por bearer token em arquivo,
  discovery inicialmente vazio, regras e testes sintéticos com `promtool`.
- `ops/observability/grafana/`: datasource e provisioning versionados com seis
  dashboards: API, reservas, workers, WhatsApp, cobrança e exportações.
- `docs/runbooks/AGD-052/`: runbooks para todos os alertas; designação nominal,
  escala e canal de plantão permanecem pendentes.
- `scripts/validate-agd052-observability.sh`: modos `validate` e `smoke`.
- `ops/observability/README.md`: credenciais/target locais descartáveis e uso
  isolado da stack.

## Imagens e escopo de rede

Pins oficiais por digest de índice multiarch, verificados em 2026-10-26:

- Prometheus `3.15.1`, `prom/prometheus`,
  `sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2`.
- Grafana `13.2.4`, `grafana/grafana`,
  `sha256:dbbc38ea21103cefb50396340cd88afec779c7e070b0a7bf5a75b4ce46651d3f`.
- Os manifests incluem `linux/amd64` e `linux/arm64`; o host validado foi
  `x86_64`.

Referências oficiais: [release Prometheus 3.15.1](https://github.com/prometheus/prometheus/releases/tag/v3.15.1),
[instalação Prometheus](https://prometheus.io/docs/prometheus/latest/installation/),
[release Grafana 13.2.4](https://github.com/grafana/grafana/releases/tag/v13.2.4),
[imagem Docker Grafana](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/)
e [provisioning Grafana](https://grafana.com/docs/grafana/latest/administration/provisioning/).

Não há Alertmanager nem destino real de notificação. Rede bridge é necessária
para o Docker publicar as portas; os binds loopback limitam o acesso ao host
local. O Grafana tem signup/anônimo, analytics, pré-instalação e atualização de
plugins desabilitados. Nenhum provedor WhatsApp/financeiro ou storage externo é
configurado pela stack.

## Contrato, painéis e interpretação

Os nomes, unidades, fontes e labels de negócio vêm somente do SHA do contrato
acima. Prometheus usa labels de scrape `job` e `instance`; não são adicionados
tenant, PII, IDs de reserva/cliente, referências externas, tokens, query strings,
payloads ou mensagens de exceção. O target versionado é vazio até configuração
local fora do Git com credencial sintética.

Contadores são analisados com `rate`/`increase`. Backlog, idade e reconciliações
globais repetidos por instância são agregados por `max by(...)`, nunca somados.
Os painéis distinguem ausência/fonte indisponível/valor zero. Aceite de transporte
não é entrega de WhatsApp nem pagamento; ausência de fornecedor aparece como
indisponibilidade, não como sucesso.

Alertas incluem auto-scrape do Prometheus; target da aplicação indisponível;
fonte não disponível, ausente ou obsoleta; proporção de 5xx; worker habilitado
sem progresso somente com backlog elegível; backlog antigo; falhas repetidas de
workers; reconciliações financeiras, de notificação e exportação em estado
incerto. Cada regra inclui limiar, duração, severidade, papel e caminho de
runbook. Limiares são demonstrativos, não SLO/SLA. Sem designação aprovada de
pessoa/plantão, não há roteamento humano. Ausência total do Prometheus exige
monitor externo e não é detectável por uma regra executada pelo próprio processo.

Os alertas de reconciliação apenas refletem itens persistidos já existentes; não
criam operações. Runbooks proíbem retry cego de cobrança ou notificação ambígua.
Não existe métrica de entrega efetiva de WhatsApp neste contrato.

## Execução reproduzível e evidência

Com Docker disponível, a partir da raiz desta worktree:

```bash
scripts/validate-agd052-observability.sh validate
scripts/validate-agd052-observability.sh smoke
```

Resultados executados nesta rodada:

- `validate`: `docker compose config`, `promtool check config`, verificação de
  regras e `promtool test rules` passaram. Foram validadas 24 regras de contrato,
  3 regras de plataforma, alertas sintéticos de disparo/não disparo, fonte
  ausente/indisponível, worker desabilitado/sem trabalho, reset de contador e
  gauges globais duplicados. Os seis JSONs passaram parse/UID/painéis.
- `smoke`: subiu somente os dois containers deste projeto com credenciais
  aleatórias temporárias; readiness HTTP passou, datasource Prometheus foi
  confirmado pela API Grafana e os seis dashboards foram encontrados pela API.
  A limpeza removeu somente containers, rede e volumes efêmeros deste projeto.
- `bash -n scripts/validate-agd052-observability.sh` e `git diff --check`
  passaram.
- Um smoke inicial falhou porque a rede Docker `internal` impedia publicação das
  portas. A configuração foi corrigida para bridge com binds loopback, e o smoke
  final passou. O Compose compartilhado não foi iniciado, reiniciado nem alterado.

Fixtures provam regras e provisioning local, não a integração com o backend. Para
demonstrar integração depois de incorporar o commit do contrato/backend, configure
target file e token local autorizados, valide uma resposta real do endpoint
`/actuator/prometheus` e confirme as séries e fontes por `job`/`instance`. Até essa
prova, painéis sem amostras devem permanecer sem dados; não inferir zero nem saúde.

## Revisão e pendências

Não houve revisores independentes disponíveis nesta sessão; revisão executada
sequencialmente pelo implementador, incluindo `promtool`, smoke real e inspeção
do diff. Isso não é aprovação humana.

Pendente: live scrape do backend integrado, nomeação de responsáveis/plantão e
política operacional aprovada para limiares. A stack local não comprova staging,
produção, entrega de notificações, pagamento nem SLO/SLA.
