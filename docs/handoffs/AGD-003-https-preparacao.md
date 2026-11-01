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

## Execução reproduzível

```sh
scripts/staging/run-disposable.sh
scripts/staging/test-cleanup-isolation.sh
```

Para validar configuração externa sem iniciar serviço:

```sh
chmod 0600 /caminho/seguro/staging.env
scripts/staging/preflight.sh --env-file /caminho/seguro/staging.env --project-name agendamento-staging
```

`infra/staging/staging.env.example` é somente formato: domínios `.invalid` e campos de segredo vazios. Nunca reutilizar a CA, o realm ou as credenciais geradas pelo runner.

## Limitações e pendências

O ensaio com nomes `.test` e CA do runner não é homologação pública. Faltam domínios/DNS controlados, certificados públicos e renovação, secret manager e rotação, provisionamento hospedado isolado, teste de expiração real após 30 minutos e aprovação operacional. O preflight desta preparação rejeita bind fora de loopback e não é configuração de publicação externa.

A rede de ingresso dá egress ao edge, embora apenas a porta HTTPS esteja publicada em `127.0.0.1`; app, Keycloak e bancos permanecem sem conexão nessa rede. O edge tem configuração estática e nenhuma integração de fornecedor. O scan de imagem não foi executado nesta tarefa; permanecem aplicáveis as avaliações residuais de segurança de Keycloak documentadas na frente de infraestrutura/PR #27. Nenhuma aprovação de segurança, homologação ou prontidão de produção é inferida.
