# Handoff — candidato NGINX para o edge AGD-003

## Decisão técnica deste incremento

O Caddy não passou o gate de scan e continua registrado como reprovado no histórico. A alternativa avaliada foi a imagem oficial estável e slim `nginx:1.30.6-alpine3.24-slim`, fixada no digest do índice multiplataforma `sha256:dede8895648b461178c3bc604f7eed4423efe5220b2c4d839f5eb3d34ac3f8f7`. A imagem preliminar examinada era `linux/amd64`, Image ID `sha256:bfdf0fe001a54d0f67ce359b7d7b57a0d141328dd94236cd1a4820421f5f811f`; o inventário identifica Alpine 3.24.3, `nginx` 1.30.6-r1 e 21 APKs, sem variante Perl ou OTel. O digest/tag é mantido pelo projeto oficial NGINX e aparece no índice de Docker Official Images; fontes em [NGINX Docker Hub](https://hub.docker.com/layers/library/nginx/stable-alpine-slim), [Docker Official Images](https://github.com/docker-library/official-images/blob/master/library/nginx) e [notas da versão estável 1.30.6](https://nginx.org/2026.html).

A imagem oficial sem frontend foi escaneada primeiro. Trivy 0.75.0 identificou Alpine 3.24.3 e os 21 pacotes APK, sem arquivos específicos de linguagem; o gate HIGH/CRITICAL passou com `ignore-unfixed=false` e sem suppressions. Evidência preliminar completa: [`AGD-003-edge-scan-preliminary-nginx-1.30.6`](AGD-003-edge-scan-preliminary-nginx-1.30.6/summary.md). Esse resultado qualificou a base para adaptação; não era a imagem final.

## Artefato final e evidência vinculada

- Image ID final: `sha256:9c425a17e72dfaedfa8689afa099dac63d056851ba21756d2e7f915a51df1477`.
- Plataforma: `linux/amd64`; user configurado no artefato: `1000:1000`.
- Referência NGINX da etapa final: `nginx:1.30.6-alpine3.24-slim@sha256:dede8895648b461178c3bc604f7eed4423efe5220b2c4d839f5eb3d34ac3f8f7`.
- O inventário final contém 21 APKs, inclusive NGINX 1.30.6-r1, e o SBOM CycloneDX lista 22 componentes.
- Trivy oficial 0.75.0, DB version 2 atualizada em `2026-11-03T13:13:42Z`; scan do arquivo salvo: `--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`, sem ignore files/suppressions. Resultado: 0 HIGH/CRITICAL, exit 0.
- O scanner reconheceu o target Alpine e 21 pacotes, mas não encontrou arquivos language-specific. O bundle frontend não distribui Node/npm ou manifestos; este scan não substitui `npm audit` dos manifests/lockfile, mantido no job frontend do App CI.
- O smoke HTTPS/OIDC passou antes do scan e imprimiu exatamente esse Image ID; o script conferiu que o tag temporário ainda apontava ao mesmo ID antes de exportar o artefato para Trivy.
- Relatório, inspect, inventário, SBOM, lista de APKs e DB metadata: [`AGD-003-edge-scan-9c425a17e72dfaed`](AGD-003-edge-scan-9c425a17e72dfaed/summary.md).
- O relatório intermediário `AGD-003-edge-scan-e093c06b00ba7ef0/` também permanece como evidência do scan/smoke anterior; não inclui as provas de lock/cleanup e roteamento cruzado desta revisão e é supersedido pelo artefato `9c425a17…` acima.

## Smoke e roteamento

`scripts/staging/scan-edge.sh` passou com um projeto Docker/Compose descartável, nomes sintéticos `.test`, TLS efêmero, Chrome headless e dados sintéticos. A prova conferiu descoberta OIDC, issuer HTTPS, redirect URI e PKCE S256, cookie Secure/HttpOnly/SameSite=Lax, associação tenant, CSRF, logout e revogação.

O smoke ainda verificou:

- Host desconhecido: `421` sem corpo.
- SNI da aplicação com Host Keycloak, e SNI Keycloak com Host da aplicação: ambos rejeitados com `421` sem corpo.
- `/api` e `/api/v1/__agd003_missing_route__`: não retornam o documento `index.html` da SPA.
- `Forwarded` e X-Forwarded falsificados não alteram a autorização administrativa nem o issuer fixo do Keycloak.
- SPA e APIs continuam na origem de aplicação; Keycloak continua em origem própria. OAuth, callback, logout e fallback SPA mantêm caminhos explícitos.

A primeira execução do smoke encontrou uma resposta 421 com corpo gerada pelo default server; após configurar o error handler para servir o arquivo vazio por named location, a assertion original `status == 421 and not body` passou. A tentativa seguinte revelou `map_hash_bucket_size` insuficiente para as chaves compostas; foi elevado a 128 e o NGINX passou `nginx -t` e o smoke integrado.

## Runner e cleanup

O runner adquire um `flock` exclusivo e privado por `--project-name` antes da checagem de colisões e o mantém durante a execução e o cleanup. Lock files persistentes em `/tmp/agd003-runner-locks` evitam que dois processos concorrentes sincronizem em inodes diferentes. Uma colisão sai antes de invocar o callback de execução ou cleanup. Cleanup que retorna erro, lança exceção ou excede timeout imprime diagnóstico mínimo, preserva a falha principal se ambas ocorrerem, produz exit não zero e mantém a configuração temporária mode-restricted para recuperação.

- `python3 scripts/staging/test-run-disposable-lifecycle.py`: 5 regressões determinísticas simuladas passaram (processos concorrentes, colisão sem side effect, falha parcial, timeout e falha primária + cleanup secundário).
- `scripts/staging/test-cleanup-isolation.sh`: prova Docker real passou. A colisão preservou container parado, volume, rede e tag da imagem sentinela; após execução normal, somente os recursos do projeto do runner foram removidos e a sentinela estrangeira permaneceu.
- O job App CI `edge-staging` executa os cinco testes simulados, a prova de sentinelas Docker e `scan-edge.sh`. Esse job cobre o edge; não substitui ou reivindica cobertura dos jobs existentes de backend e Keycloak.

## Integração e limites

- Main incorporada por merge normal: `618512d0b8c57554e7ec5cc54701f0eed3662ea4`; merge local `8f3dd3b030d8dd0aa1cff941980ea804928423a0`.
- A integração deve continuar no PR #33, branch publicada `agd-003-homologacao-https`, base `main`, draft. No momento deste handoff, publicação e checks GitHub do SHA final ainda precisam ser confirmados.
- Não houve deploy, DNS, certificado público, ACME, serviço pago ou integração com provedor real. A homologação hospedada continua pendente.
- O scan da imagem final não comprova segurança do backend ou Keycloak. Os relatórios Caddy anteriores, incluindo o relatório do commit `93352d6e0e98fcb282c2342eb56d576546e2b27d`, permanecem históricos e reprovados; não foram substituídos nem apresentados como aprovados.
