# Estado do projeto

**Atualização:** 14/10/2026.
**Entrega:** documentação organizada, backend/frontend base, gates locais e execução remota bem-sucedida de App CI/CodeQL no commit `3a4653442762abc5b0a64f32c633394b733715de`.
**AGD-001:** em andamento; não marcar como concluída.
**Plano:** [AGD-001-fundacao.md](../plans/AGD-001-fundacao.md).
**AGD-002:** parcial na branch `agd-002-tenant-isolamento`, commit local `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`; domínio Tenant, V2 funcional e testes locais PostgreSQL aprovados; autenticação/associação real, HTTP, push/PR e CI remoto pendentes. Ver [plano AGD-002](../plans/AGD-002-tenant-isolamento.md).

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

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. AGD-001 teve backend/Testcontainers (18+5), frontend, format/lint, npm audit e Trivy local aprovados; App CI e CodeQL também passaram no SHA publicado. O Trivy filesystem completo para dependências permanece pendente por HTTP 429 do Maven Central; a checagem de configuração no workflow cobre somente o Dockerfile e não substitui o scan de dependências. AGD-002 `spotless:check verify` passou em Java 21/PostgreSQL 16.13 com 21 testes unitários/arquiteturais e 9 integrações, incluindo os cenários V1/V2; confira SHA e escopo na validação.

## Pendências de AGD-001

1. App CI (`38663531502`) e CodeQL (`38663531528`) passaram no commit publicado `3a4653442762abc5b0a64f32c633394b733715de`; histórico da falha HTTP 429 no run anterior está em VALIDATION.md.
2. Executar o scan Trivy completo de dependências quando Maven Central aceitar as consultas; o sucesso do scan limitado ao Dockerfile não cobre esse critério.
3. **Concluído localmente em AGD-002:** migration funcional V2 aplicada do zero e como upgrade desde V1; execução remota vinculada ao commit da branch ainda pendente. V1 preservada.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto, homologação e gates de promoção da DoD.

## Próximo passo

O destino `LsaBarbosa/kronos_agendamento` recebeu os commits prévios preservando SHAs; App CI e CodeQL passaram em `3a4653442762abc5b0a64f32c633394b733715de`. AGD-002 tem implementação e testes locais PostgreSQL; falta revisão final, commit, push/PR e CI remoto. `gh auth status` reportou token inválido, embora a autenticação tenha sido concluída anteriormente; reautenticar o usuário `LsaBarbosa` para publicar. Próximo depois da revisão: completar AGD-003 com identidade/sessão, associação administrativa verificada server-side, resolver tenant exclusivamente do principal, proteger endpoints e provar respostas HTTP não reveladoras. Manter AGD-001 e AGD-002 abertas até fechar seus gates.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
