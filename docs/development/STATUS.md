# Estado do projeto

**Atualização:** 14/10/2026.
**Entrega:** documentação organizada, backend/frontend base, gates locais de qualidade/segurança e commits locais preparados.
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

Consultar [VALIDATION.md](VALIDATION.md) para comandos, resultados e limites. Backend/Testcontainers (18+5), frontend, format/lint, npm audit, Trivy config/secrets e scan da imagem passaram localmente. O Trivy filesystem para dependências foi bloqueado por HTTP 429 do Maven Central; configuração do workflow não substitui CI remoto.

## Pendências de AGD-001

1. O remote público foi encontrado; CodeQL passou (`38619623025`), mas App CI falhou apenas no job `security` (`38619623257`). A correção Trivy está no commit local `38efcc8`.
2. Publicar a correção e acompanhar novo App CI; falta credencial GitHub local (`git push --dry-run` não obteve username). Não compartilhar token no chat.
3. Demonstrar upgrade V1→V2 com a migration funcional de AGD-002 e aplicar ambas do zero; não criar migration vazia.
4. Definir UX, hospedagem e staging antes do smoke/E2E de produto e homologação.
5. Resolver gates de promoção, aprovação e evidência em staging conforme DoD.

## Próximo passo

O destino é `LsaBarbosa/kronos_agendamento`; os runs e commits constam em [VALIDATION.md](VALIDATION.md). Para publicar o commit local, autenticar `git`/`gh` neste ambiente com acesso de escrita autorizado, sem compartilhar token no chat. Iniciar AGD-002 sem fechar AGD-001; o critério de migration se completa com banco vazio (V1→V2) e upgrade de V1 para V2.

As demais histórias permanecem não implementadas. Nenhuma integração de WhatsApp/cobrança ou implantação foi realizada.
