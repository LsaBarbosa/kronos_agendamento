# Evidências de validação — AGD-001

**Data:** 14/10/2026.

Esta revalidação substitui os resultados do snapshot anterior: baseline Spring Boot 4.1.1, Java 21.0.11, Maven Wrapper 3.9.11, Node 24.14.1/npm 11.12.1, Docker 28.5.2 e Trivy 0.75.0. O `.env` local é ignorado e não foi incluído nos scans nem no Git.

**Commit dos gates locais:** `9991345727dad67cda794517aa2c9611ab155a19`. **Commit que disparou o primeiro CI remoto:** `0f06ed92e47345909233fd04b2559a90b9814894`. A correção do job Trivy está no commit local `38efcc8` e aguarda publicação/autenticação para novo run.

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
| Trivy config no alvo isolado | Mesmo comando `fs --scanners misconfig`, com alvo `/workspace/backend/Dockerfile` | Passou; 0 misconfigurações HIGH/CRITICAL. Fix em `.github/workflows/backend-ci.yml`, commit `38efcc8`; aguardando rerun GitHub. |
| Trivy segredos | Repetir `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL /workspace/<pasta>` para `backend/src`, `frontend/src` e `.github` | Passou; nenhum segredo encontrado nessas árvores. O `.env` de trabalho foi omitido; `.env.example` é versionável. |
| Compose/health/readiness | `POSTGRES_PASSWORD=local-validation-only DB_PASSWORD=local-validation-only docker compose -p agendamento-validation-healthcheck --profile app up --build --wait --wait-timeout 90 -d`, curls para `/actuator/health` e `/actuator/health/readiness`, seguido por `down --volumes --remove-orphans` | Passou após Boot 4; ambos serviços `healthy`, endpoints `UP`, volume/network temporários removidos. Credencial descartável, não persistida. |
| Trivy filesystem/dependências fonte | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,misconfig,secret --skip-files /workspace/.env --skip-dirs /workspace/.git,/workspace/backend/target,/workspace/frontend/node_modules,/workspace/frontend/dist /workspace` | **Não validado**: consulta de metadata Maven Central retornou HTTP 429 (Retry-After 1800s), antes de concluir o scan. Não houve supressão do gate. A imagem executável e as dependências npm foram verificadas separadamente acima; repetir este scan em rede sem rate limit. |
| SAST CodeQL e CI GitHub | [CodeQL run 38619623025](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38619623025); [App CI run 38619623257](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38619623257) | CodeQL passou em `0f06ed9`; App CI falhou somente no job `security` (Trivy filesystem, exit 1). Frontend, `verify` e seis matrizes de segredo passaram. Logs detalhados retornaram 403; causa do run remoto não confirmada. Reprodução local do scan amplo retornou HTTP 429 Maven Central. Correção local reduz alvo a Dockerfile e passou, mas aguarda publicação/rerun. |
| Staging/homologação | Não aplicável localmente | Pendente de destino, autorização e ambiente; nenhum deploy, smoke/E2E de produto ou promoção executado. |

## Git e publicação

- `.git` foi inicializado nesta raiz, branch `main`. O remote `origin` aponta para `https://github.com/LsaBarbosa/kronos_agendamento.git`; o remote é acessível e sua branch `main` está em `0f06ed92e47345909233fd04b2559a90b9814894`.
- Push dry-run confirmou ausência de credencial: `fatal: could not read Username for 'https://github.com': No such device or address`. `gh`, `GH_TOKEN` e `GITHUB_TOKEN` não estão disponíveis. O commit local `38efcc8` ainda não foi publicado; nenhum push foi feito.
- `.env`, `backend/target/`, `frontend/node_modules/` e `frontend/dist/` estão ignorados. `backend/.dockerignore` também exclui `.env*`, chaves privadas e `.git` do contexto da imagem.
- Commits locais: `9991345` (gates), `0f06ed9` (evidências), `38efcc8` (correção do escopo Trivy). O remote contém os dois primeiros; aguarda autenticação para receber o terceiro.

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

AGD-001 permanece **em andamento**. CodeQL passou no commit `0f06ed9`, mas o App CI falhou no job de configuração Trivy; a correção está em `38efcc8` e aguarda push e rerun. Também faltam Trivy FS de dependências sem rate limit, upgrade real V1→V2 e DoD de staging/homologação. A aplicação-base não tem contratos OpenAPI de produto nem fluxo funcional para smoke/E2E; adicionar esses gates quando houver contrato/fluxo aprovado, sem alegar que compilação os substitui.

Próximo incremento recomendado: AGD-002 para tenant e isolamento, com migration V2 funcional e testes PostgreSQL de banco vazio e upgrade a partir de V1.
