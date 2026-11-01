# Handoff — AGD-054-A

Estado: baseline sintético funcional, pronto para revisão.

- Base: `a4c6e2fb2ccba7de4c7f083c0e38c085dcc8d76b` (`main` observada no início desta entrega).
- Branch: `agd-054a-load-baseline`.
- Arquivos da entrega: `scripts/load/`, `infra/load/`, este handoff e `docs/plans/AGD-054-A-baseline-carga-concorrencia.md`.
- Comando reproduzível: `NODE_BIN=/home/kronos/.nvm/versions/node/v24.14.1/bin/node scripts/load/run-baseline.sh` (Node 24; `NODE_BIN=node` quando Node 24 está no PATH).
- O comando constrói a aplicação desta base e sobe PostgreSQL e aplicação em projeto Compose com sufixo aleatório, rede, volume e porta loopback próprios. Credenciais temporárias são gravadas em arquivo 0600; o resultado JSON local fica em `scripts/load/results/` (ignorado pelo Git). O cleanup valida os labels do projeto e remove somente recursos daquela execução e sua tag de imagem exclusiva.
- Perfil sintético: adapters `e2e` existentes, habilitados somente na imagem e stack descartáveis. As fixtures criam tenants/clientes, serviços, grade, política, publicação e trials sintéticos. Nenhum pagamento ou mensagem é emitido; workers externos ficam desligados.

## Execução registrada

Run `f34fbd9c8b`, executado em `2026-11-01`, projeto `agd054-load-f34fbd9c8b`; duração total medida 2.233 s.

| Fase | Volume e resultado | Throughput | Latência p50 / p95 / p99 |
|---|---|---:|---:|
| Consultas disponibilidade | 100/100 HTTP 200; 0 rejeições e 0 falhas técnicas | 152.50 req/s | 40.20 / 113.54 / 128.37 ms |
| Criações em horários distintos | 24/24 HTTP 201; 6 por tenant; 0 rejeições inesperadas | 75.94 req/s | 82.01 / 153.75 / 179.50 ms |
| Retry mesma intenção | primeiro e retry HTTP 201; retry identificado como replay e mesma referência | n/a (um retry medido) | 25.71 / 25.71 / 25.71 ms |
| Disputa pelo mesmo slot | 4/8 HTTP 201 e 4/8 HTTP 409 `SLOT_NO_LONGER_AVAILABLE`; todos os 4 conflitos esperados, sem falha técnica | 23.94 req/s | 39.89 / 69.49 / 69.49 ms |

PostgreSQL real `16.13`, schema V45. Verificação: 4 tenants; 29 reservas, 29 históricos, 29 auditorias, 29 outbox e 29 receipts de idempotência; 33 intenções; sem pares de reservas sobrepostos; sem referências tenant/client/service cruzadas; nenhum efeito requerido ausente. Reservas por tenant: 10, 6, 6, 7.

Ambiente: Linux amd64, kernel `6.12.88+deb13-amd64`, Intel Core i5-8350U a 1.70GHz, 8 CPUs e 33,512,931,328 bytes RAM; Node `v24.14.1`; Docker Engine `28.5.2`; Compose `5.1.3`; Java `21.0.13` (2026-10-20). Image ID da aplicação: `sha256:c750e448bfde83d45ba293529dcfcb70cbc32777e7550973503003713bcfa4c1`. PostgreSQL: `postgres@sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50`.

## Interpretação e limites

- É um primeiro baseline reproduzível, não um SLO, capacidade de produção ou comparação antes/depois. Throughput e latência são de uma única máquina/processo, com rede loopback e banco recém-criado; não extrapolar para múltiplas instâncias, histórico produtivo, workers ou integrações reais.
- A disputa demonstra validação/serialização do slot neste perfil e volume. O replay prova uma única intenção e cadeia de efeitos neste cenário; não constitui teste de endurance ou falha de rede após commit.
- O perfil `e2e` usa adapters sintéticos para precondições comerciais/publicação. Não prova publicação comercial real, pagamento, entrega de mensagens ou operação de workers.
- O relatório de execução é local e ignorado pelo Git, evitando persistir fixtures/respostas além dos agregados. As medidas consolidadas acima foram copiadas do JSON da execução.
- Validações locais executadas: `bash -n scripts/load/run-baseline.sh`; `node --check scripts/load/run-baseline.mjs`; `git diff --check`; execução integral do comando de baseline. Resultado: aprovados.

## Publicação

Destino solicitado: PR draft para `main`; sem merge ou deploy. SHA final e URL serão informados na entrega da branch.
