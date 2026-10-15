# App Agendamento

Pacote de início do produto: especificação completa, orientação do Codex e base do backend Java 21. As funcionalidades de reserva, administração, clientes, WhatsApp, cobrança e exportação ainda serão implementadas pelo backlog.

## Começar com o Codex

Abra esta pasta como raiz do workspace. Leia [o estado atual](docs/development/STATUS.md) e envie:

```text
Leia AGENTS.md e execute docs/prompts/00-continuar-base.md.
Parta do código existente, valide a base e continue as pendências de AGD-001.
Use as skills e os agentes do projeto quando disponíveis e úteis.
```

As skills já estão em `.agents/skills/`; os perfis opcionais estão em `.codex/agents/`. O cliente local precisa reconhecer os recursos. Os arquivos de configuração foram validados estruturalmente; descoberta e seleção dependem da versão instalada do Codex.

## Conteúdo e navegação

| Local | Conteúdo |
|---|---|
| [docs/spec/app-agendamento-README.md](docs/spec/app-agendamento-README.md) | índice dos sete documentos originais, preservados |
| [AGENTS.md](AGENTS.md) | regras permanentes de desenvolvimento |
| [backend/](backend/) | aplicação Spring Boot, Maven Wrapper, migração e testes |
| [compose.yaml](compose.yaml) | PostgreSQL e backend local |
| [compose.keycloak.yaml](compose.keycloak.yaml) | Keycloak local e banco exclusivo do provedor |
| [docs/development/STATUS.md](docs/development/STATUS.md) | implementado, pendente e próximo passo |
| [docs/development/VALIDATION.md](docs/development/VALIDATION.md) | verificações efetivamente executadas e limitações |
| [docs/development/TRACEABILITY.md](docs/development/TRACEABILITY.md) | ligação entre critérios, código e evidência |
| [docs/decisions/ADR-0001-base-backend.md](docs/decisions/ADR-0001-base-backend.md) | escolhas da fundação e trade-offs |
| [docs/decisions/ADR-0002-frontend-vite.md](docs/decisions/ADR-0002-frontend-vite.md) | escolha reversível de React/Vite |
| [docs/decisions/ADR-0003-integracoes-e-experiencia-pendentes.md](docs/decisions/ADR-0003-integracoes-e-experiencia-pendentes.md) | fornecedores e decisões de produto ainda pendentes |
| [docs/development/guia-codex-agendamento.md](docs/development/guia-codex-agendamento.md) | organização, skills, agentes e rotina |
| [frontend/README.md](frontend/README.md) | fundação React/TypeScript com Vite; fluxos de produto pendentes |

## Requisitos locais

- JDK 21 para compilar ou executar pelo Maven.
- Docker Engine com Docker Compose v2 para o fluxo local completo e Testcontainers.
- `curl` ou `wget` no Linux/macOS para o primeiro uso do wrapper; no Windows, PowerShell.
- Acesso à internet no primeiro build para baixar Maven e dependências.

Maven 3.9.11 é obtido pelo wrapper; não é necessário instalá-lo globalmente. O backend fixa Spring Boot 4.1.1, Java 21 e PostgreSQL 16; a alteração da baseline está registrada na ADR-0004. As bases JDK/JRE da imagem backend são fixadas por digest; publicação, promoção e hardening de produção permanecem no backlog.

## Subir tudo com Docker

Na raiz:

```bash
cp .env.example .env
```

Edite `POSTGRES_PASSWORD` em `.env` e então execute:

```bash
docker compose --profile app up --build -d
docker compose logs -f backend
```

O Compose publica as portas somente em `127.0.0.1`. O backend aguarda PostgreSQL saudável e expõe seu próprio estado saudável depois que readiness da aplicação e do banco respondem; o primeiro build pode demorar devido ao download das dependências.

```bash
curl --fail http://localhost:8080/actuator/health
curl --fail http://localhost:8080/actuator/health/readiness
```

Com a aplicação e o banco prontos, a resposta de saúde deve indicar `UP`. Apenas os GETs de health, liveness e readiness são públicos. As APIs administrativas liberadas estão limitadas à sessão e à leitura do tenant associado; demais rotas de negócio continuam fechadas.

## Identidade administrativa local (AGD-003)

O Keycloak local usa realm `agendamento`, imagem `26.8.1` fixada por digest e PostgreSQL/credenciais próprios, independentes dos dados da aplicação. `start-dev` é exclusivo do ambiente local. Não há auto-registro nem usuário associado automaticamente.

1. Gere `.env` da aplicação (se ausente), depois gere credenciais descartáveis e prepare Keycloak:

```bash
bash scripts/dev/prepare-local-env.sh
bash scripts/dev/prepare-keycloak.sh
docker compose -p agendamento --env-file .env --env-file .env.keycloak \
  -f compose.yaml -f compose.keycloak.yaml -f compose.identity.yaml \
  --profile app up --build --wait
```

2. Provisione explicitamente Tenant, usuário `demo-admin` e vínculo ativo. Senha temporária e UUID do tenant são gravados em `.demo-admin.credentials`, ignorado pelo Git:

```bash
bash scripts/dev/provision-demo-admin.sh demo-clinica "Clínica Demonstração"
```

3. Inicie o Vite (`cd frontend && npm ci && npm run dev`) e abra `http://localhost:5173/admin`. O navegador usa `/api` no proxy Vite, obtém CSRF por endpoint JSON e não recebe tokens OIDC. Callback do backend: `http://localhost:8080/login/oauth2/code/keycloak`; issuer e redirects são fixos, sem curingas.

O backend no Compose usa URLs internas para token/JWKS/userinfo e issuer público `http://localhost:8180/realms/agendamento`; a validação `iss` não é desligada. Cookie local usa `HttpOnly`, `SameSite=Lax` e `Secure=false` somente em HTTP local. Produção deve usar HTTPS, `Secure=true`, hostname/issuer fixos, `start` (nunca `start-dev`), redirects exatos e segredos gerenciados.

Logout invalida a sessão local, mas preserva a sessão SSO Keycloak; login seguinte pode não pedir senha. Logout federado/hospedagem permanecem pendentes de homologação.

Para parar Keycloak preservando seus dados: `docker compose -p agendamento -f compose.keycloak.yaml --env-file .env.keycloak down`. Remova volumes apenas em ambiente descartável.

Para encerrar preservando o volume local:

```bash
docker compose down
```

## Executar o Java pela IDE ou Maven

Suba somente o banco:

```bash
docker compose up -d postgres
cd backend
export DB_PASSWORD='a-mesma-senha-do-arquivo-.env'
./mvnw spring-boot:run
```

Configure a variável com a senha efetivamente escolhida. O `.env` da raiz é carregado pelo Compose; Spring Boot executado diretamente não lê esse arquivo automaticamente. Para usuário, porta ou banco diferentes dos padrões, configure também `DB_USERNAME` e `DB_URL` na IDE/terminal. No Windows, use `mvnw.cmd` e as variáveis do PowerShell.

| Variável | Padrão/necessidade |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/agendamento` |
| `DB_USERNAME` | `agendamento` |
| `DB_PASSWORD` | obrigatória; sem senha embutida na aplicação |
| `SERVER_PORT` | `8080` |

## Executar o frontend

Com Node.js compatível com Vite 8, na pasta `frontend/`:

```bash
npm ci
npm run dev
```

Os comandos `npm test` e `npm run check` executam os testes e validam os tipos/build de produção. Copie `frontend/.env.example` para `frontend/.env.local` para configurar uma URL da API diferente do padrão `/api`. A fundação atual não implementa fluxos de produto.

## Verificar e gerar o JAR

Dentro de `backend/`:

```bash
# Testes de domínio, arquitetura, correlação e fronteira de segurança:
./mvnw test

# Build e testes completos, incluindo PostgreSQL real via Testcontainers:
./mvnw verify

# JAR executável com testes da fase test; não executa a fase integration-test:
./mvnw package
```

Na raiz, `./scripts/verify.sh` equivale a `verify` e aceita opções Maven adicionais. Testes de integração falham se Docker estiver indisponível; não são ignorados silenciosamente. O JAR é gerado em `backend/target/agendamento-backend-0.1.0-SNAPSHOT.jar`.

Se já houver um PostgreSQL 16 **descartável e dedicado aos testes**, é possível executar integração sem Testcontainers:

```bash
export TEST_DATABASE_URL='jdbc:postgresql://localhost:5432/agendamento_test'
export TEST_DATABASE_USERNAME='usuario-dos-testes'
export TEST_DATABASE_PASSWORD='senha-dos-testes'
./mvnw verify
```

Esse banco precisa existir, e o usuário precisa criar schema/extensão para a migração. Não aponte os testes para um banco de clientes. Flyway mantém `clean` desabilitado.

## O que a base implementa

- Entrada Spring Boot e separação por módulos e camadas.
- Value object imutável `TimeInterval`, com semântica `[início, fim)` e testes.
- Flyway V1 preparatória e V2 funcional do agregado Tenant, com unicidade global de slug.
- Módulo Tenant com validação IANA/slug, persistência PostgreSQL, operações internas escopadas e versionamento otimista; endpoints administrativos e autenticação seguem fechados até AGD-003.
- Hibernate em `validate`, `open-in-view` desabilitado e referência temporal UTC.
- Fronteira de segurança fechada, sem credencial padrão, e envelope inicial de erro.
- Correlação por `X-Correlation-ID`, com validação do valor e limpeza de MDC.
- Actuator health/readiness, Dockerfile sem usuário root na imagem final e Compose.
- ArchUnit, testes web e suíte de integração com PostgreSQL.
- GitHub Actions executa format/lint, testes e build frontend, auditoria npm, verificação Spotless, testes backend/Testcontainers, scans Trivy de código/dependências/imagem e build local da imagem; CodeQL está separado para Java e TypeScript. Nenhuma imagem é publicada.

Os módulos ainda não implementados mantêm pacotes `package-info.java`. A integração AGD-002 testa acesso próprio/cruzado em casos de uso e constraints PostgreSQL, mas usa atores controlados em teste; não demonstra autenticação HTTP real. Reservas concorrentes, idempotência de negócio e entrega de mensagens continuam pendentes nas histórias correspondentes.

## Versionar

Se esta pasta ainda não estiver dentro de um repositório Git, inicialize-o nela e revise os arquivos antes do primeiro commit. Versione código, documentação, skills e configuração sem segredos. O ZIP não inclui `.git`, caches, JDK, Maven instalado, banco de testes, `.env` ou binários de build.

Os comandos locais estão documentados em `backend/README.md` e `frontend/README.md`. AGD-001 ainda exige scan Trivy completo de dependências e staging/homologação; o upgrade funcional V1→V2 foi exercitado pela suíte AGD-002 local. AGD-002 segue parcial até identidade/associação reais e garantias HTTP integradas em AGD-003; workflows escritos ou builds locais não substituem CI remoto.
