# Evidências de validação — AGD-001

**Data:** 14/10/2026.

Esta revalidação substitui os resultados do snapshot anterior: baseline Spring Boot 4.1.1, Java 21.0.11, Maven Wrapper 3.9.11, Node 24.14.1/npm 11.12.1, Docker 28.5.2 e Trivy 0.75.0. O `.env` local é ignorado e não foi incluído nos scans nem no Git.

**Commits publicados em `main`:** `38efcc8977bf0ca7d45f935aab3b1ad31d676e5c` e `3a4653442762abc5b0a64f32c633394b733715de` (HEAD; `docs: record GitHub CI results and retry blocker`). O push fast-forward foi concluído sem force. App CI e CodeQL passaram no HEAD; links e escopos estão na tabela abaixo.

## Execuções locais

| Critério/gate | Comando executado (raiz/escopo) | Resultado observado |
|---|---|---|
| Java format, build, testes e arquitetura | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH; ./mvnw -B -ntp -Dmaven.repo.local=/tmp/agendamento-m2 spotless:check verify` (`backend/`) | `BUILD SUCCESS`; 18 testes unitários/arquitetura + 5 integrações, 0 falhas/erros; Testcontainers iniciou PostgreSQL 16.13. |
| Flyway em banco vazio | Incluso em `verify` (`DatabaseBootstrapIT`) | V1 aplicada e validada em PostgreSQL real. |
| Upgrade de versão anterior | Sem comando executável ainda | Pendente: não há V2 funcional. AGD-002 deve introduzir a próxima alteração de schema e testar V1→V2 e vazio→V1→V2. Nenhuma migration vazia criada. |
| Frontend format/lint/testes/build/dependências | `export PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH; npm ci && npm run format:check && npm run lint && npm test && npm run check && npm audit --audit-level=high` (`frontend/`) | Passou; Prettier e Oxlint sem achados, 2 testes passaram, TypeScript/Vite build passaram, `found 0 vulnerabilities` (48 pacotes instalados). |
| Trivy imagem (vulnerabilidades OS/JAR) | `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 image --exit-code 1 --severity HIGH,CRITICAL --scanners vuln agendamento-backend:ci` | Passou; 0 HIGH/CRITICAL em Ubuntu 24.04 e JAR. Digest local `sha256:5802db603808725c81e9cf0b779eb9bf8a118ea9cef28301a79c726c27603aee`; bases JDK/JRE fixadas por digest no Dockerfile. Não publicado. |
| Trivy configuração | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 config --exit-code 1 --severity HIGH,CRITICAL /workspace` | Passou; arquivo Dockerfile analisado, 0 misconfigurações HIGH/CRITICAL. |
| Trivy config via filesystem completo | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --exit-code 1 --severity HIGH,CRITICAL --scanners misconfig /workspace` | Reprodução local falhou com HTTP 429 do Maven Central ao resolver o POM. O alvo foi reduzido a `backend/Dockerfile`, sem excluir regra ou achado. |
| Trivy config no alvo isolado | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --exit-code 1 --severity HIGH,CRITICAL --scanners misconfig /workspace/backend/Dockerfile` | Passou em 14/10/2026; 0 misconfigurações HIGH/CRITICAL (Dockerfile, um alvo). Fix em `.github/workflows/backend-ci.yml`, commit `38efcc8`. Isso comprova somente o Dockerfile, não um scan completo do repositório nem das dependências. |
| Trivy segredos | Repetir `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL /workspace/<pasta>` para `backend/src`, `frontend/src` e `.github` | Passou; nenhum segredo encontrado nessas árvores. O `.env` de trabalho foi omitido; `.env.example` é versionável. |
| Compose/health/readiness | `POSTGRES_PASSWORD=local-validation-only DB_PASSWORD=local-validation-only docker compose -p agendamento-validation-healthcheck --profile app up --build --wait --wait-timeout 90 -d`, curls para `/actuator/health` e `/actuator/health/readiness`, seguido por `down --volumes --remove-orphans` | Passou após Boot 4; ambos serviços `healthy`, endpoints `UP`, volume/network temporários removidos. Credencial descartável, não persistida. |
| Trivy filesystem/dependências fonte | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,misconfig,secret --skip-files /workspace/.env --skip-dirs /workspace/.git,/workspace/backend/target,/workspace/frontend/node_modules,/workspace/frontend/dist /workspace` | **Pendente, não validado**: consulta de metadata Maven Central retornou HTTP 429 (Retry-After 1800s), antes de concluir o scan. A correção para analisar apenas `backend/Dockerfile` não cobre dependências nem substitui este gate; não houve supressão. Repetir o scan completo quando Maven Central aceitar as consultas. |
| SAST CodeQL e CI GitHub | [App CI run 38663531502](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38663531502); [CodeQL run 38663531528](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38663531528) | Ambos concluídos com `success` no commit `3a4653442762abc5b0a64f32c633394b733715de`. App CI: todos os nove jobs passaram (`frontend`, `verify`, `security` no alvo Dockerfile e seis matrizes de segredos); CodeQL: Java/Kotlin e JavaScript/TypeScript passaram. A falha anterior em `0f06ed9` foi investigada: log do job `116043439232` confirma HTTP 429 do Maven Central ao resolver `spring-data-bom:2026.0.1`, sem achado de misconfig reportado. O run verde analisa configuração apenas no Dockerfile; não equivale ao scan completo de dependências, que segue pendente. Há avisos não bloqueantes sobre a migração de `ubuntu-latest` para Ubuntu 26 e Actions/Node.js em depreciação. |
| Staging/homologação | Não aplicável localmente | Pendente de destino, autorização e ambiente; nenhum deploy, smoke/E2E de produto ou promoção executado. |

## Git e publicação

- `.git` está nesta raiz, branch `main`; `origin` aponta para `https://github.com/LsaBarbosa/kronos_agendamento.git`. Após o push, `origin/main` está em `3a4653442762abc5b0a64f32c633394b733715de`; os dois commits foram enviados por fast-forward, sem force push. `git diff --check` passou.
- `gh auth status` confirmou login de `LsaBarbosa` e o push foi concluído. O setup do GitHub CLI informou armazenamento das credenciais em texto simples (`/home/kronos/.config/gh/hosts.yml`); considere configurar um credential store seguro no sistema. As alterações documentais desta revalidação ainda estão no working tree e não fazem parte do SHA dos runs acima.
- `.env`, `backend/target/`, `frontend/node_modules/` e `frontend/dist/` estão ignorados. `backend/.dockerignore` também exclui `.env*`, chaves privadas e `.git` do contexto da imagem.
- Histórico publicado: `9991345` (gates), `0f06ed9` (evidências iniciais), `38efcc8` (escopo da configuração Trivy) e `3a46534` (registro da primeira execução CI). Os novos runs referenciam `3a4653442762abc5b0a64f32c633394b733715de`.

## Reproduzir

Na raiz, com Docker disponível:

```bash
cd backend
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
./mvnw -B -ntp spotless:check verify
cd ../frontend
npm ci
npm run format:check
npm run lint
npm test
npm run check
npm audit --audit-level=high
```

Para a imagem, da raiz: `docker build --pull --iidfile /tmp/agendamento-image-id.txt -t agendamento-backend:ci backend` e depois execute o comando Trivy da tabela. Para segredos, use os escopos de código/workflows acima; não escaneie o `.env` local. Para o gate Maven Trivy completo, execute novamente quando Maven Central aceitar as consultas.

## Definition of Done e pendências

AGD-001 permanece **em andamento**. App CI e CodeQL passaram no SHA publicado `3a4653442762abc5b0a64f32c633394b733715de`. Permanecem pendentes o scan Trivy completo de dependências sem rate limit (o Dockerfile é apenas o escopo da checagem de configuração verde), upgrade real V1→V2 e DoD de staging/homologação. A aplicação-base não tem contratos OpenAPI de produto nem fluxo funcional para smoke/E2E; adicionar esses gates quando houver contrato/fluxo aprovado, sem alegar que compilação os substitui.

Próximo incremento recomendado: AGD-002 para tenant e isolamento, com migration V2 funcional e testes PostgreSQL de banco vazio e upgrade a partir de V1.

## AGD-002 — Tenant e isolamento (branch local)

**Branch:** `agd-002-tenant-isolamento`, baseada no `main` em `271569fcb1de0f98c695ecacfeccd19ba6608777`. **Commit de código:** `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`. O head remoto `5b8b6f0c3038823b70b918ea7ab93a8b1299008d` acrescenta documentação de evidência; nenhum código mudou após o SHA local testado.

| Critério | Comando/escopo | Resultado observado |
|---|---|---|
| Formatação, build, testes unitários, arquitetura e integração | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH; ./mvnw -B -ntp -Dmaven.repo.local=/tmp/agendamento-m2 spotless:check verify` (`backend/`) | `BUILD SUCCESS`; 21 testes unitários/arquiteturais e 9 integrações, zero falhas; JDK 21.0.11, Docker 28.5.2, PostgreSQL Testcontainers 16.13. Spotless sem alterações. |
| Banco vazio | `TenantPersistenceIT.appliesV1AndV2ToFreshSchemaAndCanRerunWithoutChanges` | Em schema descartável real: Flyway aplicou V1 e V2, tabela Tenant presente, `validate()` passou e segunda `migrate()` aplicou zero migrações. |
| Upgrade pré-migrado V1 | `TenantPersistenceIT.upgradesV1SchemaToV2AndKeepsMigrationHistoryValid` | Em schema isolado real: aplicou V1, depois somente V2; histórico validado, tabela presente e reexecução aplicou zero migrações. V1 não foi alterada no diff. |
| Isolamento por tenant | `TenantPersistenceIT.permitsOwnTenantOperationsAndConcealsForeignTenantForReadAndMutation` | Contextos de identidade controlados em teste: leitura/renomeação do próprio tenant passou; leitura e tentativa de mutação de outro tenant produziram `TenantNotFoundException` idêntica; registro alheio permaneceu intacto. Isto não comprova identidade real nem HTTP. |
| Corrida de slug | `TenantPersistenceIT.concurrentInsertsOfSameCanonicalSlugHaveOneWinner` | Dois writers simultâneos no PostgreSQL, slug após canonicalização: um sucesso, um conflito; uma única linha. Garantia final por `UNIQUE (slug)` e `ON CONFLICT` dirigido à constraint. |
| Campos, canonicalização e timezone | `TenantDomainTest` | 3 testes passaram: trimming/NFKC/lowercase, formato slug, timezone IANA, campos/status e incremento de version em renomeação. |
| CI remoto e PR | [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1), App CI e CodeQL | PR aberto. No head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d`, App CI passou em [push 38665441992](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38665441992) e [pull_request 38665495754](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38665495754); CodeQL passou em [push 38665441831](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38665441831) e [pull_request 38665495668](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38665495668). Nove jobs do App CI e ambas as linguagens do CodeQL passaram. Avisos de Node.js 20/`ubuntu-latest` não bloquearam. |

### Limites ainda abertos em AGD-002

- `AuthenticatedTenant` é apenas contrato interno; atores dos testes são fabricados. AGD-003 precisa autenticar a identidade, verificar a associação `AdminUser`–tenant no servidor e derivar o tenant do principal.
- As rotas de negócio permanecem deny-all. A resposta HTTP não reveladora ao cruzar tenants, login/logout, expiração/revogação, papéis e eventos de segurança não estão demonstrados.
- Nenhuma tabela de negócio além da raiz `tenant` existe no incremento; entidades futuras devem tornar `tenant_id` obrigatório e impedir referências cruzadas.
- `ACTIVE` não significa onboarding completo, assinatura elegível ou publicação. Política de palavra reservada/liberação de slug e efeito de troca de timezone continuam em `OPEN-QUESTIONS.md`.
- AGD-002 permanece parcial até identidade real e os demais critérios aplicáveis. AGD-001 também continua aberta pelo scan Trivy completo de dependências e homologação/staging.
- O job de segurança remoto verifica configuração somente em `backend/Dockerfile`; seu sucesso não cobre o scan Trivy filesystem completo de dependências, ainda pendente em AGD-001 após HTTP 429 anterior do Maven Central.

## AGD-003 — Sessão JDBC, expiração e preparação HTTPS

**Branch:** `agd-003-autenticacao-autorizacao`; PR #2 aberto sobre a branch do PR #1 (`agd-002-tenant-isolamento`, head `9a4f6b2aba688034e7b38eeb9d9a7c35285a8254`). O incremento abaixo ainda não está publicado; os links de CI listados como baseline são do head anterior `e17a326e1f126498961ebc7f278d616499a475a2`. **Estado:** expiração de 30 minutos comprovada localmente com estado JDBC controlado; CI remoto para o novo commit e homologação HTTPS hospedada pendentes.

| Critério | Comando/escopo | Resultado observado |
|---|---|---|
| Backend, formatação, arquitetura, unitários e integração PostgreSQL | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; export PATH="$JAVA_HOME/bin:$PATH"; ./mvnw -B -ntp spotless:check verify` (`backend/`) | Passou em 15/10/2026, `BUILD SUCCESS`; 21 unitários/arquiteturais + 11 testes de integração, sem falhas; PostgreSQL Testcontainers 16.13. Inclui `AdminAuthorizationIT` e regras ArchUnit. O primeiro intento com JDK 25 foi rejeitado corretamente pelo Enforcer; a execução aprovada usou JDK 21, sem alterar o gate. |
| Configuração real do Spring Session JDBC e cookie | `backend/pom.xml`, `application.yml`; chamada `/api/v1/csrf` e consulta PostgreSQL `agendamento.spring_session` | Corrigido defeito encontrado durante a prova: `spring-session-jdbc` sozinho não ativava a auto-configuração do Spring Boot 4. Dependência agora usa `org.springframework.boot:spring-boot-starter-session-jdbc`; store `jdbc` explícito. Resposta de CSRF emitiu cookie HttpOnly/SameSite=Lax e sessão persistida com intervalo `1800` segundos no schema Flyway. Cookie `Secure` agora é true por padrão; somente o Compose de desenvolvimento local sobrescreve para HTTP (`false`). |
| Expiração e renovação determinísticas com PostgreSQL/Keycloak/Chrome reais | `docker compose -p agendamento --env-file .env --env-file .env.keycloak -f compose.yaml -f compose.keycloak.yaml -f compose.identity.yaml up -d --build backend`; `bash scripts/dev/provision-demo-admin.sh demo-clinica "Clínica Demonstração"`; frontend Vite em `127.0.0.1:5173`; `/home/kronos/.nvm/versions/node/v24.14.1/bin/node scripts/dev/test-keycloak-browser.mjs` | Passou localmente em 15/10/2026. Cookie autenticado identifica a sessão persistida; teste confirma `max_inactive_interval=1800`, `expiry_time` futuro a 25m e GET protegido 200; a mesma requisição atualiza `last_access_time`; estado é então preparado em 31m com `expiry_time` passado e o cookie recebe 401 `AUTHENTICATION_REQUIRED`. A margem de 5m reduz falsos negativos por pausa do runner. Não se espera 30m nem se apaga a linha para simular; limpeza é independente da rejeição do cookie expirado. |
| SSO e UI após expiração local | Mesmo browser E2E | Após 401, UI mostra “Sua sessão expirou ou ainda não foi iniciada” e oferece login. Sem limpar cookies do Keycloak, login OIDC retorna sem formulário de credenciais e gera nova sessão local 200. Portanto, o teste distingue expiração local do SSO ainda ativo conforme ADR-0006. |
| Regressão OIDC/JDBC revelada pela ativação do store | Mesmo browser E2E; `AdminAccess` | Corrigido `AdminAccess` para `Serializable`: com JDBC de fato ativo, o login real havia falhado na persistência do principal por `NotSerializableException`. Após a correção, login, associação, tenant, revogação, CSRF, logout, usuário sem vínculo e varredura dos logs passaram. |
| Frontend | `npm run format:check && npm run lint && npm test && npm run check` (`frontend/`, Node 24.14.1), `node --check scripts/dev/test-keycloak-browser.mjs`, `git diff --check` | Format, lint, 2 testes, TypeScript, Vite build, sintaxe E2E e whitespace passaram. O teste browser também valida status 401 e link para login. |
| Baseline App CI/E2E — PR #2 | Commit `e17a326e1f126498961ebc7f278d616499a475a2`; [run 38744164405](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38744164405) | Passou no head anterior; inclui `oidc-browser-e2e`, Testcontainers/PostgreSQL, frontend, security/Trivy e segredos. O primeiro job security do mesmo head falhou por `unexpected EOF` ao obter layer, sem achado CVE; rerun posterior do conjunto passou sem enfraquecer scanner. |
| Baseline CodeQL — PR #2 | Commit `e17a326e1f126498961ebc7f278d616499a475a2`; [run 38744164349](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38744164349) | Java/Kotlin e JavaScript/TypeScript passaram no head anterior. Novos resultados para o incremento aguardam publicação. |
| App CI/CodeQL do incremento `4b3e9ad158095ec6e6cfede7eee096858075dd06` | Push: App CI [38754518738](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754518738), CodeQL [38754518546](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754518546). PR: App CI [38754528200](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754528200), CodeQL [38754528154](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754528154). | CodeQL Java/Kotlin + JS/TS passou nos dois eventos; App CI `verify`, Trivy security, frontend e secrets passaram. O job `oidc-browser-e2e` falhou em ambos depois da prova de expiração/SSO, ao aguardar logout: o botão aceitava clique antes do fetch CSRF da sessão renovada completar. Correção local aplicada: botão fica desabilitado enquanto CSRF indisponível e browser E2E espera habilitação. E2E local completo passou depois da correção; novo commit/CI ainda pendentes. Não houve alteração de thresholds nem supressão de teste. |
| PR #1 e sequência de integração | [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1), [PR #2](https://github.com/LsaBarbosa/kronos_agendamento/pull/2) | Fetch de 15/10/2026: ambos abertos, `MERGEABLE`, sem review registrada. #1 base `main`, head `9a4f6b2`; App CI runs `38665824078` e `38665827846`, CodeQL `38665824102` e `38665827879` com checks observados verdes. #2 base ainda `agd-002-tenant-isolamento`, head `e17a326`; App CI `38744164405` e CodeQL `38744164349` verdes. Não retargetar nem integrar #2 antes de #1. Após merge #1, conferir ancestralidade: avançar base para `main` se preservada; se squash/rebase, rebase/reaplicar e revisar diff antes de nova publicação, sem force push não autorizado. |
| Homologação HTTPS | [Runbook AGD-003-homologacao-https.md](../plans/AGD-003-homologacao-https.md) | Requisitos de domínio público, issuer, callback, TLS, `Secure`, proxy, secrets/Keycloak e roteiro de smoke documentados. Sem domínio/ambiente hospedado disponível. Nenhum teste HTTPS local nem homologação observada; continua pendente. |

### Limites e pendências

- O commit `4b3e9ad` teve CodeQL verde, mas App CI falhou somente no browser E2E devido à corrida CSRF descrita acima. A correção de UX/teste passou localmente; aguardar nova execução remota antes de considerar o CI aprovado. `38744164405`/`38744164349` são a baseline do head anterior.
- A prova local temporal usa estado persistido preparado em 25/31 minutos, sem esperar 30 minutos; a homologação hospedada deve aguardar 30 minutos reais de inatividade em HTTPS. Sessão SSO Keycloak pode continuar ativa.
- Somente `TENANT_ADMIN` está habilitado; RBAC de suporte e MFA permanecem fechados até decisão de produto. Rotas de negócio/cross-tenant HTTP só serão validadas quando implementadas; atualmente estão deny-all.
- PR #1 deve ser revisado/integrado antes do PR #2. Depois, reconciliar base/diff/checks do #2 e executar testes integrados; não fazer merge sem decisão/revisão.
- AGD-003 permanece **em andamento** pela homologação HTTPS e demais critérios aplicáveis. AGD-002 aguarda integração dos PRs e futuras rotas HTTP de negócio. AGD-001 permanece **em andamento** pelo scan completo de dependências e homologação; scan limitado ao Dockerfile não substitui o scan filesystem completo.
