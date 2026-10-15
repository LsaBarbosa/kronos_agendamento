# Estado do projeto

**Atualização:** 15/10/2026.
**Entrega:** base backend/frontend, migrations, identidade administrativa e provas locais/remotas registradas; incremento de expiração JDBC publicado até `6c46d578740e2590969ce60aaf09f4ab509ba13b`, com App CI e CodeQL aprovados em push e PR.
**AGD-001:** em andamento; não marcar como concluída.
**Plano:** [AGD-001-fundacao.md](../plans/AGD-001-fundacao.md).
**AGD-002:** parcial; PR #1 foi integrado em `main` pelo merge commit `a708dcf358e551304018cde22a428a1275de4d13`. Testes de isolamento de casos de uso passaram; rotas HTTP de negócio/cross-tenant ainda não existem. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).
**AGD-003:** em andamento na branch `agd-003-autenticacao-autorizacao`; PR #2 agora tem base `main` (`a708dcf`) e head `db1387f6b1bbd9aef12cb25689fb09f88bf49c48`. App CI push/PR (`38767072349`, `38767089103`) e CodeQL push/PR (`38767072125`, `38767088614`) passaram. A correção de revogação invalida a sessão quando o banco nega associação/admin; E2E local prova que reativar a identidade não restaura o cookie antigo. Prova local/Testcontainers e browser também comprova expiração 25/31m e SSO preservado. Homologação HTTPS hospedada permanece pendente; consulte VALIDATION.md, [plano AGD-003](../plans/AGD-003-identidade-keycloak.md), [runbook HTTPS](../plans/AGD-003-homologacao-https.md) e [ADR-0006](../decisions/ADR-0006-keycloak-oidc-sessao-administrativa.md).

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
- AGD-003: AdminUser e associação ativos em V3; tabelas Spring Session JDBC no schema Flyway em V4; starter JDBC do Spring Boot 4 ativa persistência real; realm compartilhado Keycloak local 26.8.1; Authorization Code/PKCE; rotas administrativas mínimas e autorização revalidada no banco em cada requisição. V1/V2 preservadas.

## Evidência

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. AGD-001 continua aberta pelo scan filesystem completo de dependências e homologação; scan limitado ao Dockerfile não o substitui. AGD-003: `spotless:check verify` passou em JDK 21 (21 testes unitários/arquiteturais + 11 integrações com PostgreSQL Testcontainers 16.13); browser E2E local com Keycloak/PostgreSQL reais passou para expiração de 30m e revogação não reversível. App CI/CodeQL passaram em push/PR no head `db1387f6b1bbd9aef12cb25689fb09f88bf49c48`.

## Pendências de AGD-001

1. App CI (`38663531502`) e CodeQL (`38663531528`) passaram no commit publicado `3a4653442762abc5b0a64f32c633394b733715de`; histórico da falha HTTP 429 no run anterior está em VALIDATION.md.
2. Executar o scan Trivy completo de dependências quando Maven Central aceitar as consultas; o sucesso do scan limitado ao Dockerfile não cobre esse critério.
3. **Concluído em AGD-002:** migration funcional V2 aplicada do zero e como upgrade desde V1; PR #1 integrado em `a708dcf`. **Validado localmente e no App CI em AGD-003, PR #2 ainda aberto:** V1–V4 aplicadas em banco vazio; upgrade desde V1 e V2 em PostgreSQL real; V1/V2 imutáveis.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto, homologação e gates de promoção da DoD.

## Próximo passo

O PR #1 está integrado. O PR #2 está aberto, mergeável, sobre `main` e com App CI/CodeQL verdes no head `db1387f`; revisões por agentes não equivalem a aprovação humana, e não há review registrada no GitHub. Diff conferido: somente AGD-003, sem arquivos/migration de AGD-002. O próximo passo autorizado é integrar #2 preservando commits; depois criar branch própria de AGD-004 a partir da `main` integrada. Em paralelo, providenciar domínio/issuer/TLS/secrets e executar smoke HTTPS com 30m reais. AGD-003 e AGD-002 permanecem parciais; AGD-001 segue aberta pelo scan completo e homologação.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
