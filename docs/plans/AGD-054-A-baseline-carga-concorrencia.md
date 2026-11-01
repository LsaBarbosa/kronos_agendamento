# AGD-054-A — Baseline reproduzível de carga e concorrência

## Identificação e objetivo

- História: AGD-054; incremento A, execução sintética local, sem mudança na aplicação.
- Base confirmada: `a4c6e2fb2ccba7de4c7f083c0e38c085dcc8d76b`.
- Branch: `agd-054a-load-baseline`.
- Resultado observável: um comando sobe aplicação e PostgreSQL reais em projeto Compose próprio, sem dependência dos Composes compartilhados, prepara tenants sintéticos, executa carga, mede latências/throughput e valida efeitos persistidos.
- Referências: backlog `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`, AGD-054; disponibilidade `docs/spec/app-agendamento-01-especificacao-mvp.md` §5.6 e `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` §3; criação pública, idempotência e corrida `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` §§1–3 e `docs/spec/app-agendamento-02-modelo-dominio-estados.md` §§6, 12–14.
- Inclui somente `scripts/load/`, `infra/load/` e este plano/handoff. Exclui arquivos de staging, Composes existentes, workflows compartilhados e regras da aplicação.

## Estado e dependências

- Disponibilidade pública é indicativa e não cria hold; o commit valida novamente e a exclusão GiST sobre `(tenant_id, [starts_at, occupied_until))` impede sobreposição.
- Criação pública usa preparo de intenção seguido de POST, ambos com a mesma chave/capability/comando; replay deve retornar a mesma referência sem repetir histórico, auditoria ou outbox.
- A aplicação já oferece adapter de evidência de publicação e identidade comercial apenas no perfil `e2e`, compilado somente pelo build argument `INCLUDE_E2E_FIXTURES=true`. Não existe endpoint geral de criação de fixtures. O perfil é ligado somente na Compose descartável deste incremento; nunca é usado no runtime compartilhado.
- PostgreSQL real é obrigatório para medir e verificar concorrência e persistência. SQL de fixture roda somente no banco do projeto exclusivo, depois do Flyway. As consultas de verificação são somente leitura.
- Os workers de outbox, expiração de trial e exportação ficam desabilitados no Compose de carga. O incremento não inicia pagamentos, notificações nem integrações externas.

## Estratégia

1. Criar Compose com projeto aleatório `agd054-load-<10 hex>`, portas loopback atribuídas pelo Docker, volume e rede do próprio projeto. A senha do owner e a senha do runtime são aleatórias, temporárias e armazenadas em arquivo com modo 0600 fora do repositório.
2. Construir a aplicação da base exata com os adapters sintéticos já existentes do perfil `e2e`; aguardar readiness do actuator.
3. Semear tenants ativos publicados, serviço ativo, grade, política e trial sintéticos, com nomes e telefones marcados AGD-054. Não semear reservas, histórico, auditoria, outbox ou idempotência.
4. Medir consulta de disponibilidade, criação em slots distintos distribuída por tenants, retry após commit da mesma intenção e disputas sincronizadas pelo mesmo slot. Relatar status por classe; `409 SLOT_NO_LONGER_AVAILABLE` dos perdedores é rejeição de negócio esperada, não falha técnica.
5. Verificar no banco contagem de tenants/intenções/efeitos, vínculo de reserva e serviço ao tenant esperado, histórico/auditoria/outbox/idempotência únicos e ausência de pares de reservas sobrepostos.
6. Remover somente containers, rede e volume cujo label `com.docker.compose.project` corresponda ao projeto aleatório criado pela execução. Se o projeto já possuir recursos antes da subida ou label divergente, falhar sem cleanup amplo.

Parâmetros padrão: 4 tenants, 100 consultas de disponibilidade, 24 criações distintas, 4 disputas pelo mesmo slot (8 comandos), concorrência HTTP 8; limites são inputs de experimento, não SLO. Datas vêm do timezone do tenant (`America/Sao_Paulo`) e do relógio real, com agendamento dois dias à frente. Cada tenant recebe tráfego de criação. A grade tem 32 inícios diários de 15 minutos; a execução falha se o parâmetro solicitar mais slots do que a fixture permite.

## Progresso

- [x] 2026-11-01 — Base `a4c6e2f` confirmada pelo GitHub e capturada numa cópia isolada em `/tmp/kronos-agd054-load`; branch própria sem alteração de outras worktrees.
- [x] 2026-11-01 — Escopo e dependências de fixture revisados em migrations, teste de integração pública e adapters `e2e` existentes.
- [x] Implementar/validar automação de execução e invariantes em PostgreSQL.
- [x] Registrar primeira medição, host, versões, volumes e limitações.
- [ ] Publicar PR draft contra `main`; sem merge/deploy.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Disponibilidade sob carga | `NODE_BIN=/home/kronos/.nvm/versions/node/v24.14.1/bin/node scripts/load/run-baseline.sh` | HTTP 200 medido com throughput e p50/p95/p99 | PASS: 100/100 HTTP 200; 152.50 req/s; p50 40.20 ms, p95 113.54 ms, p99 128.37 ms |
| Criação em slots distintos e tenants | Mesmo comando | Sucessos medidos por tenant, sem rejeição inesperada | PASS: 24/24 HTTP 201; 6 criações por tenant; zero rejeição técnica/de negócio inesperada |
| Disputa simultânea | Mesmo comando; duas intenções preparadas e release conjunto por lote | Um POST 201 e um 409 `SLOT_NO_LONGER_AVAILABLE` por slot | PASS: 4 vencedores 201, 4 conflitos de negócio esperados 409, zero falhas técnicas |
| Retry de intenção idempotente | Mesmo comando | Mesmo `appointmentId`, retry marcado replay, uma cadeia persistida | PASS: retry HTTP 201, marcado replay, mesma referência; uma reserva e uma cadeia de efeitos |
| Banco PostgreSQL real | Probe SQL após a carga | Zero overlap; contagens e referências por tenant coerentes | PASS: 29 reservas, 29 history/audit/outbox/receipts; 0 pares sobrepostos e 0 vínculos cruzados/incompletos |
| Isolamento da infraestrutura | Labels e cleanup validados pelo script | Só projeto/volume/rede daquela execução removidos | PASS: projeto `agd054-load-f34fbd9c8b` sem containers/volumes/redes após execução; imagens temporárias dos ensaios removidas por tags próprias |
| Métricas úteis | Relatório JSON | Throughput, p50/p95/p99, status/falhas técnicas e recusas de negócio separados | PASS: JSON local `scripts/load/results/baseline-f34fbd9c8b.json`; fases separam respostas de negócio e falhas técnicas |

### Execução de referência registrada

- Run: `f34fbd9c8b`, início `2026-11-01T03:27:24Z`, conclusão `2026-11-01T03:27:26Z`; duração total medida 2.233 s.
- Host: Linux amd64, kernel `6.12.88+deb13-amd64`, Intel Core i5-8350U, 8 CPUs, 33,512,931,328 bytes RAM.
- Ferramentas: Node `v24.14.1`, Docker Engine `28.5.2`, Compose `5.1.3`, Java `21.0.13` (2026-10-20), PostgreSQL `16.13`; schema V45.
- Artefatos: aplicação `sha256:c750e448bfde83d45ba293529dcfcb70cbc32777e7550973503003713bcfa4c1`; PostgreSQL `postgres@sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50`.
- Parâmetros: 4 tenants, 100 consultas, 24 criações distintas, 4 rodadas de disputa (8 comandos), concorrência 8, data de reserva `2026-11-03`.
- Criação distinta: 75.94 req/s; p50 82.01 ms, p95 153.75 ms, p99 179.50 ms. Disputa: 4/4 conflitos esperados, 0 falhas técnicas.
- Limite: uma execução, uma máquina, um processo da aplicação, tráfego sintético no loopback; não é SLO nem estimativa de capacidade de produção.

## Descobertas e decisões

- O perfil `e2e` satisfaz somente blockers de evidência/comercial com componentes sintéticos. Ele não prova publicação comercial, workers, provedor, segurança operacional, SLO ou capacidade de produção.
- As métricas de carga são end-to-end no loopback local, incluem latência de rede/container e não são extrapoladas para produção.
- Sem baseline histórico de hardware/ambiente comparável, esta execução documenta referência inicial e gargalos observáveis, sem decisão de capacidade.

## Encerramento ou retomada

Executar `scripts/load/run-baseline.sh`, conferir o JSON em `scripts/load/results/` (artefato local ignorado pelo Git), preencher o handoff com recursos, versões, parâmetros, latências e resultados do probe. Só então publicar PR draft contra `main`.
