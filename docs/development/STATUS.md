# Estado do projeto

**Atualização:** 15/10/2026.
**Entrega:** documentação organizada, backend/frontend base, gates locais e execução remota bem-sucedida de App CI/CodeQL no commit `3a4653442762abc5b0a64f32c633394b733715de`.
**AGD-001:** em andamento; não marcar como concluída.
**Plano:** [AGD-001-fundacao.md](../plans/AGD-001-fundacao.md).
**AGD-002:** parcial no [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1), branch `agd-002-tenant-isolamento`; código `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`, evidências no head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d`. AGD-003 demonstra identidade OIDC real, associação server-side e contexto no GET do tenant próprio; essa resolução aguarda merge dos PRs #1/#2. Isolamento de futuros endpoints de negócio depende das histórias correspondentes. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).
**AGD-003:** em andamento na branch `agd-003-autenticacao-autorizacao`, PR #2 aberto sobre `agd-002-tenant-isolamento`, dependente do PR #1. Implementação e browser E2E real passaram localmente e remotamente; App CI `38742515664` e CodeQL `38742515500` passaram no commit `1b509c12b94c7bcbd841265f7edeb4abfe749eff`. Permanecem pendentes expiração temporal, hospedagem/TLS e homologação; ver evidências em VALIDATION.md, [plano AGD-003](../plans/AGD-003-identidade-keycloak.md) e [ADR-0006](../decisions/ADR-0006-keycloak-oidc-sessao-administrativa.md).

## Já disponível

- Sete documentos originais em `docs/spec/`, sem alteração de conteúdo.
- `AGENTS.md`, prompts, planos, três skills locais e dois perfis de revisão.
- Backend Java 21/Spring Boot 4.1.1, Maven Wrapper e estrutura modular; baseline de segurança registrada na ADR-0004.
- `TimeInterval`, correlação e fronteira inicial de segurança com testes.
- Migração Flyway preparatória, PostgreSQL no Compose e suíte de integração.
- Dockerfile com bases Java 21 Ubuntu 24.04 fixadas por digest; workflows incluem format/lint, npm audit, Trivy de configuração/segredos/imagem e CodeQL Java/TypeScript.
- Frontend React/TypeScript com Vite, testes da configuração de API e comandos documentados.
- Workflow de CI com job isolado de teste e build do frontend.
- Compose identifica a prontidão da aplicação pelo endpoint Actuator readiness.
- AGD-002: tabela Tenant em V2, slug canonicalizado e globalmente único, timezone IANA, status/version e casos de uso de leitura/renomeação limitados pelo `tenant_id` do contexto; sem rotas de negócio liberadas.
- AGD-003: AdminUser e associação ativos em V3; tabelas Spring Session JDBC no schema Flyway em V4; realm compartilhado Keycloak local 26.8.1; Authorization Code/PKCE; rotas administrativas mínimas e autorização revalidada no banco em cada requisição. V1/V2 preservadas.

## Evidência

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. AGD-001 teve backend/Testcontainers (18+5), frontend, format/lint, npm audit e Trivy local aprovados; App CI e CodeQL passaram no SHA publicado. O Trivy filesystem completo de dependências segue pendente após HTTP 429; a checagem de configuração verde cobre somente o Dockerfile. AGD-002 `spotless:check verify` passou em Java 21/PostgreSQL 16.13 (21 testes unitários/arquiteturais + 9 integrações), e App CI/CodeQL passaram no head do PR.

## Pendências de AGD-001

1. App CI (`38663531502`) e CodeQL (`38663531528`) passaram no commit publicado `3a4653442762abc5b0a64f32c633394b733715de`; histórico da falha HTTP 429 no run anterior está em VALIDATION.md.
2. Executar o scan Trivy completo de dependências quando Maven Central aceitar as consultas; o sucesso do scan limitado ao Dockerfile não cobre esse critério.
3. **Concluído em AGD-002:** migration funcional V2 aplicada do zero e como upgrade desde V1; testes locais e App CI passaram. V1 preservada. **Validado localmente e no App CI em AGD-003; aguardando integração do PR #1:** V1–V4 aplicadas em banco vazio; upgrade desde V1 e V2 em PostgreSQL real; V1/V2 imutáveis.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto, homologação e gates de promoção da DoD.

## Próximo passo

Os [PRs #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1) e [#2](https://github.com/LsaBarbosa/kronos_agendamento/pull/2) estão abertos; o #2 depende do #1. App CI, E2E real e CodeQL passaram no SHA registrado em VALIDATION.md. Próximo: revisar/integrar PRs em ordem; homologar expiração e infraestrutura HTTPS; evoluir endpoints de negócio e testes cross-tenant HTTP. AGD-003 e AGD-002 permanecem parciais; AGD-001 segue aberta pelo scan completo e homologação.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
