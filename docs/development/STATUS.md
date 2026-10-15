# Estado do projeto

**Atualização:** 14/10/2026.
**Entrega:** documentação organizada, backend/frontend base, gates locais e execução remota bem-sucedida de App CI/CodeQL no commit `3a4653442762abc5b0a64f32c633394b733715de`.
**AGD-001:** em andamento; não marcar como concluída.
**Plano:** [AGD-001-fundacao.md](../plans/AGD-001-fundacao.md).
**AGD-002:** parcial no [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1), branch `agd-002-tenant-isolamento`; código `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`, evidências no head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d`. App CI e CodeQL passaram em push/PR; identidade real e garantias HTTP permanecem pendentes de AGD-003. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).

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

## Evidência

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. AGD-001 teve backend/Testcontainers (18+5), frontend, format/lint, npm audit e Trivy local aprovados; App CI e CodeQL passaram no SHA publicado. O Trivy filesystem completo de dependências segue pendente após HTTP 429; a checagem de configuração verde cobre somente o Dockerfile. AGD-002 `spotless:check verify` passou em Java 21/PostgreSQL 16.13 (21 testes unitários/arquiteturais + 9 integrações), e App CI/CodeQL passaram no head do PR.

## Pendências de AGD-001

1. App CI (`38663531502`) e CodeQL (`38663531528`) passaram no commit publicado `3a4653442762abc5b0a64f32c633394b733715de`; histórico da falha HTTP 429 no run anterior está em VALIDATION.md.
2. Executar o scan Trivy completo de dependências quando Maven Central aceitar as consultas; o sucesso do scan limitado ao Dockerfile não cobre esse critério.
3. **Concluído em AGD-002:** migration funcional V2 aplicada do zero e como upgrade desde V1; testes locais e App CI passaram. V1 preservada.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto, homologação e gates de promoção da DoD.

## Próximo passo

O [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1) está aberto. App CI `38665441992`/`38665495754` e CodeQL `38665441831`/`38665495668` passaram no head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d`; detalhes em VALIDATION.md. O scan Trivy filesystem completo segue pendente e não é substituído pelo scan de imagem/configuração. Próximo: completar AGD-003 com identidade/sessão, associação administrativa verificada server-side, resolver tenant do principal, proteger endpoints e provar respostas HTTP não reveladoras. Manter AGD-001 e AGD-002 abertas até fechar os gates.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
