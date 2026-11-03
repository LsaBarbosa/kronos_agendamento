# Handoff — AGD-003 HTTPS executável

**Estado:** incremento executável verificado localmente em infraestrutura descartável; sem homologação pública, deploy ou credenciais reais.

**Corte:** base `a4c6e2fb2ccba7de4c7f083c0e38c085dcc8d76b`; branch `agd-003-homologacao-https` na worktree `/tmp/kronos-agd003-https`. Este handoff descreve o commit final desta branch quando publicado.

## Topologia entregue

- `app.<host>`: frontend estático e API/OIDC backend na mesma origem HTTPS.
- `id.<host>`: Keycloak em origem separada, modo `start`, hostname/issuer estritos.
- Caddy usa hosts exatos, rejeita host desconhecido com 421, remove `Forwarded`, define `X-Forwarded-Proto/Port` e aplica o tratamento padrão seguro de `X-Forwarded-Host/For`. O backend não processa forwarded headers. Keycloak confia somente no IP estático interno do Caddy.
- App, Keycloak e bancos ficam na rede interna; edge também entra numa rede de ingresso exclusiva e publica somente loopback. Edge usa usuário sem privilégios, `no-new-privileges`, `cap_drop: ALL`, rootfs read-only e tmpfs para estado efêmero. PostgreSQL de app/Keycloak, subnets, volumes, tags de imagem e projeto Compose são exclusivos por execução.
- Cookie de sessão continua Secure/HttpOnly/SameSite=Lax; CSRF permanece habilitado.

## Evidências executadas — 2026-11-01

Comandos passaram:

```sh
python3 -m py_compile scripts/staging/run-disposable.py scripts/staging/smoke-https.py
bash -n scripts/staging/preflight.sh scripts/staging/run-disposable.sh scripts/staging/test-cleanup-isolation.sh
git diff --check
scripts/staging/test-cleanup-isolation.sh
```

O preflight aceitou a configuração sintética completa e recusou configurações com bind `0.0.0.0` e callback divergente sem imprimir segredos. O runner construiu os serviços, importou o realm Keycloak em modo de produção, aplicou migrações Flyway até V45 no PostgreSQL descartável e aguardou readiness.

O smoke completo passou:

- OpenSSL, Python SSL e `curl --cacert` verificaram cadeia e SAN com CA efêmera.
- Discovery e issuer HTTPS do Keycloak corresponderam à configuração; redirect OAuth usou a origem HTTPS separada, callback exato da app e PKCE S256.
- Host desconhecido recebeu 421; forwarded headers falsificados não mudaram issuer/redirect; sessão administrativa sem autenticação recebeu 401.
- Chrome executou login OIDC Authorization Code + PKCE. Associação atual no banco resolveu o tenant server-side; cookie `AGDSESSION` tinha Secure, HttpOnly e SameSite=Lax.
- Logout sem CSRF ou CSRF inválido recebeu 403; logout válido recebeu 200 e a consulta seguinte à sessão recebeu 401.
- Desativação da associação deu 403 na requisição de detecção e 401 na reutilização do cookie; usuário OIDC sem associação foi negado.

Image IDs construídos e exercitados pelo mesmo runner, antes do cleanup:

- Keycloak: `sha256:4b305f3c21f7dc967b6522790bbc16937d98eefa1c3e2bfac21a06f974d99e99`.
- Backend: `sha256:22ad028fe6a343430b4e0dde629217860053eab81606101bcceda1e03331f52d`.
- Edge: `sha256:9d6be94ac76a50c0100650db18dd94233557d5aacdc3f8c1958abea899cf62fc`.

O teste de isolamento criou um container parado, volume e rede sob outro projeto sintético. Todos permaneceram intactos durante e depois do runner; em seguida, o teste removeu somente seu próprio sentinel. O runner também confirmou a remoção dos containers, volumes, redes e imagens locais com tags exclusivas que ele próprio criou. Não restaram recursos dos projetos de teste.

## Revalidação de scan e colisão — 2026-11-03

O runner passou a recusar, antes de criar temporários ou registrar cleanup, qualquer nome explícito de projeto que já tenha containers, volumes ou redes rotulados com esse projeto, ou tags no namespace `kronos-staging-<projeto>-`. `scripts/staging/test-cleanup-isolation.sh` comprovou o caso de colisão: havia um container parado, volume, rede e tag de imagem sintéticos associados ao projeto escolhido; a execução foi recusada e os quatro recursos permaneceram. Em seguida, a execução normal concluiu o smoke e removeu apenas seus próprios recursos e tags, preservando o sentinel. Não foi usado recurso de outro projeto como fixture.

Comando de teste:

```sh
scripts/staging/test-cleanup-isolation.sh
```

Resultado: PASS para recusa da colisão sem remoção e PASS para cleanup isolado da execução normal, incluindo container, volume, rede e tags de imagem.

O scan completo do artefato edge foi executado por `scripts/staging/scan-edge.sh`. O mesmo runner construiu e iniciou as imagens, completou o smoke HTTPS/OIDC e emitiu o Image ID; o scanner verificou que a tag edge ainda apontava para esse ID, exportou-a com `docker save` e passou o arquivo resultante ao Trivy. O SHA-256 do arquivo escaneado também foi registrado. Assim, o smoke e o scan estão vinculados ao mesmo conteúdo de imagem, sem depender de uma tag mutável durante a análise.

Evidência do artefato testado:

- Edge: Image ID `sha256:e2033e54c3cbd36468c73999e617f4e7615de5383b76bc22218bd2e8685cc4ce`; tag do run `kronos-staging-agd003-https-1793707246-469496-84717e-edge:run`; arquivo `docker save` SHA-256 `3416b6ee0f4592547a0e62cb9cff29ec02d1489bb5e334254c3e3985e83fcc49`.
- Plataforma/configuração: Linux amd64, usuário `1000:1000`, Alpine `3.23.6`, Caddy `v2.11.4`, 32 pacotes APK.
- Scanner: imagem oficial Trivy 0.75.0, Image ID `sha256:6d93f1ec925b37c5ce785e416b86cba056e10c8c2914a59a8ea7be18115d6764`, registry digest `aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`.
- Política: scanners de vulnerabilidade, severidades HIGH/CRITICAL, `ignore-unfixed=false`, `--exit-code 1`, sem arquivo de ignore ou suppressions.
- DB Trivy: versão 2; atualização `2026-11-03T07:14:02.629452927Z`, próxima atualização `2026-11-04T07:14:02.629452405Z`, download `2026-11-03T12:02:20.985520331Z`.
- Inventário CycloneDX: 180 componentes; inventário OS/APK: 32 pacotes. Relatório Trivy: 29 findings, 1 CRITICAL e 28 HIGH; processo saiu com status 1 conforme a política.
- Arquivos integrais: [`resumo`](AGD-003-edge-scan-e2033e54c3cbd364/summary.md), [`relatório Trivy`](AGD-003-edge-scan-e2033e54c3cbd364/trivy-report.json), [`CycloneDX`](AGD-003-edge-scan-e2033e54c3cbd364/inventory.cdx.json), [`inventário TSV`](AGD-003-edge-scan-e2033e54c3cbd364/inventory.tsv), [`metadados do DB`](AGD-003-edge-scan-e2033e54c3cbd364/database-metadata.json), [`configuração da imagem`](AGD-003-edge-scan-e2033e54c3cbd364/image-inspect.json), [`configuração do scanner`](AGD-003-edge-scan-e2033e54c3cbd364/scanner-image-inspect.json) e [`SO/pacotes`](AGD-003-edge-scan-e2033e54c3cbd364/os-and-packages.txt).

O relatório separa 3 registros de pacotes Alpine (Brotli CRITICAL e curl/libcurl HIGH) e 26 findings ligados ao binário Go do Caddy, incluindo stdlib e dependências Go. Eles são findings do scanner e mantêm o gate local reprovado; esta rodada não avaliou aplicabilidade nem implementou correções. Nenhum finding foi suprimido. O CI existente do PR verifica outras imagens (backend/Keycloak) e não executa este scan do edge; seus resultados não devem ser usados como substitutos desta evidência nem como aprovação do edge.

## Execução reproduzível

```sh
scripts/staging/run-disposable.sh
scripts/staging/test-cleanup-isolation.sh
scripts/staging/scan-edge.sh
```

Para validar configuração externa sem iniciar serviço:

```sh
chmod 0600 /caminho/seguro/staging.env
scripts/staging/preflight.sh --env-file /caminho/seguro/staging.env --project-name agendamento-staging
```

`infra/staging/staging.env.example` é somente formato: domínios `.invalid` e campos de segredo vazios. Nunca reutilizar a CA, o realm ou as credenciais geradas pelo runner.

## Limitações e pendências

O ensaio com nomes `.test` e CA do runner não é homologação pública. Faltam domínios/DNS controlados, certificados públicos e renovação, secret manager e rotação, provisionamento hospedado isolado, teste de expiração real após 30 minutos e aprovação operacional. O preflight desta preparação rejeita bind fora de loopback e não é configuração de publicação externa.

A rede de ingresso dá egress ao edge, embora apenas a porta HTTPS esteja publicada em `127.0.0.1`; app, Keycloak e bancos permanecem sem conexão nessa rede. O edge tem configuração estática e nenhuma integração de fornecedor. O scan executado encontrou 29 findings HIGH/CRITICAL e reprovou segundo a política estabelecida; a configuração HTTPS/OIDC funcional não altera esse resultado. Também permanecem as avaliações residuais de segurança das imagens Keycloak/backend documentadas na frente de infraestrutura/PR #27. Nenhuma aprovação de segurança, homologação ou prontidão de produção é inferida.
