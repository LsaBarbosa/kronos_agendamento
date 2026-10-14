# Evidências de validação — AGD-001

**Data:** 14/10/2026.

Esta revalidação substitui os resultados do snapshot anterior: baseline Spring Boot 4.1.1, Java 21.0.11, Maven Wrapper 3.9.11, Node 24.14.1/npm 11.12.1, Docker 28.5.2 e Trivy 0.75.0. O `.env` local é ignorado e não foi incluído nos scans nem no Git.

**Commit de código e configuração testado:** `9991345727dad67cda794517aa2c9611ab155a19` (`AGD-001: establish quality and security gates`). As execuções abaixo validaram exatamente essa revisão; as atualizações deste relatório registram os resultados, sem alterar código funcional.

## Execuções locais

| Critério/gate | Comando executado (raiz/escopo) | Resultado observado |
|---|---|---|
| Java format, build, testes e arquitetura | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH; ./mvnw -B -ntp -Dmaven.repo.local=/tmp/agendamento-m2 spotless:check verify` (`backend/`) | `BUILD SUCCESS`; 18 testes unitários/arquitetura + 5 integrações, 0 falhas/erros; Testcontainers iniciou PostgreSQL 16.13. |
| Flyway em banco vazio | Incluso em `verify` (`DatabaseBootstrapIT`) | V1 aplicada e validada em PostgreSQL real. |
| Upgrade de versão anterior | Sem comando executável ainda | Pendente: não há V2 funcional. AGD-002 deve introduzir a próxima alteração de schema e testar V1→V2 e vazio→V1→V2. Nenhuma migration vazia criada. |
| Frontend format/lint/testes/build/dependências | `export PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH; npm ci && npm run format:check && npm run lint && npm test && npm run check && npm audit --audit-level=high` (`frontend/`) | Passou; Prettier e Oxlint sem achados, 2 testes passaram, TypeScript/Vite build passaram, `found 0 vulnerabilities` (48 pacotes instalados). |
| Trivy imagem (vulnerabilidades OS/JAR) | `docker run --rm -v /var/run/docker.sock:/var/run/docker.sock -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 image --exit-code 1 --severity HIGH,CRITICAL --scanners vuln agendamento-backend:ci` | Passou; 0 HIGH/CRITICAL em Ubuntu 24.04 e JAR. Digest local `sha256:5802db603808725c81e9cf0b779eb9bf8a118ea9cef28301a79c726c27603aee`; bases JDK/JRE fixadas por digest no Dockerfile. Não publicado. |
| Trivy configuração | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 config --exit-code 1 --severity HIGH,CRITICAL /workspace` | Passou; arquivo Dockerfile analisado, 0 misconfigurações HIGH/CRITICAL. |
| Trivy segredos | Repetir `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --scanners secret --exit-code 1 --severity HIGH,CRITICAL /workspace/<pasta>` para `backend/src`, `frontend/src` e `.github` | Passou; nenhum segredo encontrado nessas árvores. O `.env` de trabalho foi omitido; `.env.example` é versionável. |
| Compose/health/readiness | `POSTGRES_PASSWORD=local-validation-only DB_PASSWORD=local-validation-only docker compose -p agendamento-validation-healthcheck --profile app up --build --wait --wait-timeout 90 -d`, curls para `/actuator/health` e `/actuator/health/readiness`, seguido por `down --volumes --remove-orphans` | Passou após Boot 4; ambos serviços `healthy`, endpoints `UP`, volume/network temporários removidos. Credencial descartável, não persistida. |
| Trivy filesystem/dependências fonte | `docker run --rm -v "$PWD:/workspace:ro" -v "$HOME/.cache/trivy:/root/.cache/" aquasec/trivy:0.75.0 fs --exit-code 1 --severity HIGH,CRITICAL --scanners vuln,misconfig,secret --skip-files /workspace/.env --skip-dirs /workspace/.git,/workspace/backend/target,/workspace/frontend/node_modules,/workspace/frontend/dist /workspace` | **Não validado**: consulta de metadata Maven Central retornou HTTP 429 (Retry-After 1800s), antes de concluir o scan. Não houve supressão do gate. A imagem executável e as dependências npm foram verificadas separadamente acima; repetir este scan em rede sem rate limit. |
| SAST CodeQL e CI GitHub | Workflows `.github/workflows/codeql.yml` e `backend-ci.yml` | Configurados, mas não executados: sem remote, `gh` instalado ou sessão/token GitHub detectável. Um workflow escrito não é evidência de pipeline aprovado. Repositório privado precisa ser elegível/licenciado para CodeQL. |
| Staging/homologação | Não aplicável localmente | Pendente de destino, autorização e ambiente; nenhum deploy, smoke/E2E de produto ou promoção executado. |

## Git e publicação

- Não foi encontrado repositório válido no diretório nem em diretórios superiores/worktree. `.git` inicializado nesta raiz, branch `main`; identidade global existente: `LsaBarbosa <lsa94.contato@gmail.com>`.
- Não há remote configurado, `gh` instalado ou `GH_TOKEN`/`GITHUB_TOKEN` no ambiente. Nenhuma publicação foi tentada.
- `.env`, `backend/target/`, `frontend/node_modules/` e `frontend/dist/` estão ignorados. `backend/.dockerignore` também exclui `.env*`, chaves privadas e `.git` do contexto da imagem.
- Commit local criado na branch `main`: `9991345727dad67cda794517aa2c9611ab155a19`. O commit subsequente de documentação registra estas evidências; nenhum commit remoto/CI existe.

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

AGD-001 permanece **em andamento**. V1 vazia, build/testes, arquitetura, formatos, lint, auditoria npm e imagem local têm evidência; faltam pipeline limpo remoto/CodeQL, elegibilidade CodeQL se privado, scan Trivy de filesystem sem rate limit, upgrade real V1→V2 e DoD de staging/homologação. A aplicação-base não tem contratos OpenAPI de produto nem fluxo funcional para smoke/E2E; adicionar esses gates quando houver contrato/fluxo aprovado, sem alegar que compilação os substitui.

Próximo incremento recomendado: AGD-002 para tenant e isolamento, com migration V2 funcional e testes PostgreSQL de banco vazio e upgrade a partir de V1.
