# AGD-002 — Tenant e isolamento

## Identificação e objetivo

- História: AGD-002 (P0), incremento local na branch `agd-002-tenant-isolamento`, baseada no `main` em `271569fcb1de0f98c695ecacfeccd19ba6608777`.
- Objetivo observável: persistir Tenant com ID, nome, slug canônico globalmente único, timezone IANA, status, `created_at` e `version`; provar escopo do ator em leitura/alteração e a corrida de slug no PostgreSQL.
- Referências: documento 01, seções 2.2 e 3; documento 02, seções 2, 4.1 e 16; documento 03, seções 1, 2 e 5.1; documento 04, seções 8, 10, 17, 18 e 19; documento 06, critérios AGD-002/003 e DoD.
- Incluído: modelo de domínio, portas e casos de uso internos, adapter PostgreSQL, migration V2 imutável após aplicação, testes reais de Flyway/isolamento/concorrência e registros de evidência.
- Excluído: login, sessão, associação de usuário em produção, rotas administrativas/públicas, API CRUD, mudança de timezone com reservas futuras, RLS e statusização por cobrança.

## Estado e dependências

- Branch própria criada a partir de `main`; o trabalho documental de AGD-001 foi preservado e publicado em `271569f`.
- O backend atualmente contém somente V1 preparatória; não há tabelas de negócio adicionais.
- A fronteira HTTP nega rotas de negócio. AGD-003 deve integrar identidade real, validar a associação `AdminUser`–Tenant no servidor e produzir o contexto autenticado consumido pelos casos de uso.
- O escopo atual usa contexto de ator controlado nos testes; não configura identidade ou rotas de produção. Um `tenant_id` de comando/cliente nunca substitui o tenant do contexto.
- Sem decisão aprovada de provedor de identidade (ADR-0003); sem associação administrativa persistida até AGD-003.

## Estratégia

1. Definir política técnica reproduzível de slug e validar fuso por catálogo IANA da JVM.
2. Adicionar Tenant ao módulo `tenant` respeitando domínio → aplicação → infraestrutura; consultas e mutações administrativas são sempre condicionadas ao `tenant_id` autenticado e alvos ausentes/cruzados retornam resultado não revelador.
3. Criar V2 sem alterar V1; manter unicidade de slug no banco (constraint global não parcial) e limites/status/version no schema.
4. Testar Flyway do zero, upgrade de schema em V1, validação/reexecução e concorrência com PostgreSQL 16 real.
5. Executar Spotless, testes, arquitetura, integração e verificações frontend/CI aplicáveis; revisar diff e atualizar status/evidências.
6. Associar evidência local ao SHA do commit. Publicar branch e abrir PR apenas com autorização e autenticação GitHub válidas; acompanhar App CI/CodeQL quando executáveis.

### Contrato com AGD-003

- A camada de identidade autentica o principal e verifica a associação administrativa no servidor; tenant e papel vêm desse principal/associação, não de `tenant_id` em path/body/query.
- O adapter HTTP deve converter principal autorizado em contexto de ator e chamar os casos de uso; tenant inexistente e tenant alheio devem produzir a mesma resposta externa não reveladora (contrato HTTP fica pendente de AGD-003).
- AGD-003 continua responsável por sessões, expiração/revogação, roles, CSRF se aplicável, eventos de segurança e proteção efetiva das rotas. A segurança atual permanece deny-all.
- Contextos fabricados por testes não são evidência de autenticação em runtime.

## Progresso

- [x] 14/10/2026 — branch `agd-002-tenant-isolamento` criada sobre `main` em `271569f`.
- [x] 14/10/2026 — instruções, status, validação, rastreabilidade, história, especificação relacionada, ADRs, dúvidas e skills lidos.
- [x] 14/10/2026 — implementar domínio, casos de uso, persistência e V2; V1 permanece sem alteração.
- [x] 14/10/2026 — testes de domínio/arquitetura e PostgreSQL 16 real cobrem fresh V1+V2, upgrade V1→V2, validate/rerun sem alterações, isolamento de leitura/mutação e corrida de slug.
- [x] 14/10/2026 — revisão independente somente leitura do diff atual: nenhum achado reproduzível em isolamento, slug concorrente, V2/Flyway, timezone/status ou arquitetura; reviewer executou `-Dit.test=TenantPersistenceIT verify` (21 testes + 4 testes tenant, PostgreSQL 16.13).
- [x] 14/10/2026 — revisão final e commit de código `e6bbcf3b920c1c6c6220d181e10f86d88c11ae32`; working tree de código validado antes do commit.
- [x] 14/10/2026 — publicar branch e abrir [PR #1](https://github.com/LsaBarbosa/kronos_agendamento/pull/1); App CI/CodeQL passaram em push e pull_request no head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d` (runs 38665441992/38665495754 e 38665441831/38665495668).
- [ ] Homologar identidade real e garantias HTTP em AGD-003; manter AGD-002 parcial até os critérios aplicáveis serem satisfeitos.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Campos, slug e fuso IANA | `./mvnw -B -ntp spotless:check verify` em `backend/` | normalização canônica determinística; inválidos rejeitados | passou nos testes de domínio; Java 21.0.11 |
| Identidade/isolamento | `TenantPersistenceIT` em PostgreSQL 16 | acesso próprio permitido; alvo alheio não encontrado em leitura/mutação, sem alterar seu registro | passou com contextos controlados; identidade de produção permanece AGD-003 |
| Unicidade concorrente | `TenantPersistenceIT.concurrentInsertsOfSameCanonicalSlugHaveOneWinner` | exatamente um slug persistido e conflito estável no outro writer | passou; dois writers PostgreSQL simultâneos, 1 linha |
| Fresh V1+V2; upgrade V1→V2 | `TenantPersistenceIT` com schemas isolados no Testcontainers | V1/V2 aplicadas do zero; schema em V1 recebe V2 sem alterar V1 | passou em PostgreSQL 16.13 |
| Histórico/reexecução Flyway | `validate()` + segunda `migrate()` nos dois schemas | valida; segunda execução aplica zero migrations | passou; nenhum delta na reexecução |
| Arquitetura/formatação | `./mvnw -B -ntp -Dmaven.repo.local=/tmp/agendamento-m2 spotless:check verify` | camadas/modularidade preservadas, sem diferenças de formatação | passou: 21 testes unitários/arquitetura + 9 integrações; Docker/Testcontainers |
| CI remoto | GitHub App CI e CodeQL na branch/PR | execuções concluídas em sucesso | passou no head `5b8b6f0c3038823b70b918ea7ab93a8b1299008d`: App CI `38665441992` (push), `38665495754` (pull_request); CodeQL `38665441831` (push), `38665495668` (pull_request). |

## Descobertas e decisões

- ADR-0001 mantém a escolha de PostgreSQL 16/Flyway, com baseline Spring substituída pela ADR-0004. ADR-0003 deixa provedor de identidade pendente.
- A política exata do slug, estado inicial e efeito de timezone precisam ser documentados sem atribuir semântica de produto além dos critérios; dúvidas de produto material serão registradas em `OPEN-QUESTIONS.md`.
- A unicidade da coluna slug será global e persistente mesmo após suspensão, preservando slugs enquanto reservados; não será índice parcial por status.
- Não criar migration vazia: V2 conterá a tabela funcional de tenant.

## Encerramento ou retomada

- Implementação de domínio/persistência/migration e evidência PostgreSQL local concluídas. AGD-002 parcial: não há autenticação real, associação servidor-side nem endpoints HTTP; critérios de resposta HTTP não reveladora aguardam AGD-003.
- Próximo trabalho: AGD-003 deve ligar sessão/principal autenticado à associação verificada de administrador–tenant, adaptar as rotas fechadas e provar respostas HTTP não reveladoras; decisões de provedor permanecem na ADR-0003 até aprovação.
