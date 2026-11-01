# Plano — AGD-003: preparação executável de homologação HTTPS

## Identificação e objetivo

- História: AGD-003; incremento autorizado de preparação HTTPS local, sem homologação pública.
- Base confirmada: `a4c6e2fb2ccba7de4c7f083c0e38c085dcc8d76b` (`main` remota).
- Branch isolada: `agd-003-homologacao-https` em `/tmp/kronos-agd003-https`.
- Objetivo observável: subir aplicação e Keycloak em uma rede/projeto Compose exclusivo, validar HTTPS/issuer/OIDC/sessão/autorização/CSRF/logout com identidades descartáveis e limpar somente os recursos daquele projeto.
- Referências: `docs/plans/AGD-003-homologacao-https.md`; ADR-0006; AGD-003 no documento 06; configuração OIDC do backend.

## Escopo e exclusões

Inclui `infra/staging/**`, `scripts/staging/**`, `compose.staging.yaml` e este plano/handoff. Não altera Compose existente, workflows, observabilidade, carga ou código da aplicação. Não abre o gate público/comercial. Não configura DNS, certificados públicos, secret manager, serviço pago, credenciais reais ou deploy.

## Estado e decisões técnicas

- A topologia usa `https://app.<domínio>` para frontend e APIs na mesma origem; Caddy encaminha apenas rotas OAuth/API para o backend. `https://id.<domínio>` é a origem própria do Keycloak.
- Caddy possui blocos Host exatos e uma rota catch-all `421`; remove `Forwarded`, fixa `X-Forwarded-Proto`/`Port` e usa o tratamento do Caddy para substituir `X-Forwarded-Host`/`For` em vez de confiar nos valores recebidos. A seleção `handle` garante que as rotas OAuth/API cheguem ao backend e o restante use o frontend estático. O backend define `SERVER_FORWARD_HEADERS_STRATEGY=none`; redirects OIDC são URLs explícitas e fixas.
- Keycloak executa `start` (não `start-dev`), hostname HTTPS estrito, `proxy-headers=xforwarded`, `proxy-trusted-addresses` limitado ao IP estático do proxy, HTTP interno habilitado somente entre containers e PostgreSQL próprio.
- Sessão mantém `Secure=true`, `HttpOnly`, `SameSite=Lax`, sessão JDBC e CSRF da aplicação. Client é confidencial, Authorization Code com PKCE S256, redirect URI exata e Direct Access Grants desligado.
- O executor local gera CA/certificado com extensões X.509 explícitas, usuários, client secret e senhas apenas sob `/tmp`, usa nomes `.test` e Chrome com exceção SPKI restrita ao certificado efêmero. `curl`, OpenSSL e Python verificam cadeia/SAN com a CA do runner; nenhuma trust store do sistema é alterada.
- Cada ensaio tem projeto, nomes de imagem, subnets `/24` disjuntas, IP confiável, porta aleatória em loopback, volumes e diretório temporário únicos. App, Keycloak e bancos ficam só na rede interna; apenas edge também participa da rede de ingresso. O edge é non-root, `no-new-privileges`, sem capabilities, rootfs read-only e `/data`, `/config`, `/tmp` efêmeros. O trap usa exclusivamente `docker compose -p <projeto> ... down --volumes --remove-orphans --rmi local`; não usa prune nem toca recursos de outros projetos. Um teste de sentinelas confirma que container parado, volume e rede de outro projeto sobrevivem.
- Caddy `2.11.4-alpine` é fixado no digest do índice `sha256:6aeddd44c3078b0f9a35206472a11420648a79c184603ef95957d0a20044cb2b`; Node é fixado no índice oficial `22-alpine3.23@sha256:6abbcf5dd6b231da109bcde1181db1d92bf78015ae1a9514d9807c1f857869db`; PostgreSQL 16.13 Alpine no índice oficial `sha256:4e6e670bb069649261c9c18031f0aded7bb249a5b6664ddec29c013a89310d50`. Keycloak e backend são construídos dos Dockerfiles versionados existentes e recebem tags exclusivas do ensaio.
- `infra/staging/staging.env.example` contém apenas valores ilustrativos não secretos e campos vazios para segredos. O preflight exige arquivo `0600`, segredo externo por finalidade, callback/issuer exatos, hosts não descartáveis fora do modo local, subnets disjuntas e tags de imagem contendo o projeto. O preflight rejeita publicação fora do loopback nesta preparação.

## Contrato operacional

- Preflight externo: `scripts/staging/preflight.sh --env-file <arquivo-externo-0600> --project-name <slug>`. Não imprime valores. Exige origens HTTPS, hosts distintos, callback exato, issuer de realm, segredos distintos com comprimento mínimo, certificado/realm existentes, PostgreSQL com digest, redes disjuntas, proxy /32 e imagens locais vinculadas ao projeto.
- Ensaio reproduzível: `scripts/staging/run-disposable.sh`. Ele constrói as imagens, configura o realm sintético, sobe duas bases isoladas e o proxy, aguarda readiness com polling e executa `smoke-https.py` antes do cleanup.
- Configuração hospedada deve vir de secret manager/gestor de certificados fora do Git. O compose não inclui valores secretos padrão. O serviço edge só publica a porta HTTPS no endereço explicitamente configurado; no executor descartável, exclusivamente `127.0.0.1`.

## Validação

| Critério | Procedimento | Resultado observado (2026-11-01) |
|---|---|---|
| Preflight positivo e rejeições seguras | `scripts/staging/run-disposable.sh` chama preflight real e tenta bind `0.0.0.0` e callback incorreto | Passou; as duas variantes foram rejeitadas e nenhum segredo apareceu na saída |
| Compose, build e readiness | `scripts/staging/run-disposable.sh` em Linux amd64 / Compose v2 | Passou; realm Keycloak importado, migrações Flyway V1–V45 aplicadas no PostgreSQL descartável e backend ficou ready |
| TLS, SAN, discovery e issuer | OpenSSL, Python `ssl` e `curl --cacert` usando CA do runner | Passou para ambos os hosts sintéticos; issuer e endpoint público de autorização conferidos |
| Redirect e PKCE | HTTP com headers encaminhados falsificados | Passou; redirect usa Keycloak HTTPS, callback exato da app, client correto e PKCE S256 |
| Cookie, associação, CSRF e logout | Chrome headless com perfil novo e PostgreSQL do projeto | Passou; cookie `AGDSESSION` Secure/HttpOnly/SameSite=Lax; sem/CSRF inválido 403, logout válido 200 e sessão posterior 401 |
| Revogação e identidade não associada | Duas sessões/browser profiles e alteração controlada de associação no banco descartável | Passou; detecção da revogação 403, reutilização do cookie 401; usuário OIDC sem vínculo negado |
| Isolamento do cleanup | `scripts/staging/test-cleanup-isolation.sh` com container parado, volume e rede sentinela de outro projeto | Passou; recursos sentinela permaneceram, e nenhuma rede/volume/container `agd003` permaneceu após cleanup |
| Imagens do ensaio final | Image IDs emitidos pelo runner antes do cleanup | Keycloak `sha256:4b305f3c21f7dc967b6522790bbc16937d98eefa1c3e2bfac21a06f974d99e99`; backend `sha256:22ad028fe6a343430b4e0dde629217860053eab81606101bcceda1e03331f52d`; edge `sha256:9d6be94ac76a50c0100650db18dd94233557d5aacdc3f8c1958abea899cf62fc` |
| Homologação hospedada e expiração de sessão em 30 minutos | Domínios/certificados/segredos reais; inatividade cronometrada em ambiente aprovado | Pendente; não autorizada nesta tarefa |

## Retomada e limites

Executar `scripts/staging/run-disposable.sh` em host amd64 com Docker Compose v2, OpenSSL, curl, Python 3 e Chrome; para provar o cleanup, executar `scripts/staging/test-cleanup-isolation.sh`. O teste final confirmou a remoção dos recursos e imagens locais pertencentes ao projeto do runner e preservou container parado, volume e rede do projeto sentinela até a limpeza explícita do próprio teste. Arquivos de evidência não incluem secrets, cookies ou tokens. O ensaio é local sintético, não valida DNS público, cadeia de CA pública, secret manager/rotação, proxy/LB externo, timeout real de 30 minutos nem aprovação operacional. A rede de ingresso do edge permite egress do container, embora somente a porta TLS esteja publicada em loopback e nenhum provedor esteja configurado; app, Keycloak e bancos permanecem na rede interna. A imagem Keycloak herdada mantém as limitações de segurança já registradas em PR #27; nenhum scan foi executado nesta tarefa e este incremento não altera o gate de imagem.
