# Estado do projeto

**Atualização:** 14/10/2026.
**Entrega:** documentação organizada, backend/frontend base, gates locais e execução remota bem-sucedida de App CI/CodeQL no commit `3a4653442762abc5b0a64f32c633394b733715de`.
**AGD-001:** em andamento; não marcar como concluída.
**Plano:** [AGD-001-fundacao.md](../plans/AGD-001-fundacao.md).

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

## Evidência

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. Backend/Testcontainers (18+5), frontend, format/lint, npm audit e Trivy local passaram; App CI e CodeQL também passaram no SHA publicado. O Trivy filesystem completo para dependências permanece pendente por HTTP 429 do Maven Central; a checagem de configuração no workflow cobre somente o Dockerfile e não substitui o scan de dependências.

## Pendências de AGD-001

1. App CI (`38663531502`) e CodeQL (`38663531528`) passaram no commit publicado `3a4653442762abc5b0a64f32c633394b733715de`; histórico da falha HTTP 429 no run anterior está em VALIDATION.md.
2. Executar o scan Trivy completo de dependências quando Maven Central aceitar as consultas; o sucesso do scan limitado ao Dockerfile não cobre esse critério.
3. Demonstrar upgrade V1→V2 com a migration funcional de AGD-002 e aplicar ambas do zero; não criar migration vazia.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto, homologação e gates de promoção da DoD.

## Próximo passo

O destino `LsaBarbosa/kronos_agendamento` recebeu os dois commits preservando SHAs; App CI e CodeQL passaram. Próximo: repetir o scan completo de dependências após o rate limit, iniciar AGD-002 com tenant/isolamento e migration V2 funcional, e definir ambiente de staging para homologação. Manter AGD-001 aberta até fechar os gates aplicáveis.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
