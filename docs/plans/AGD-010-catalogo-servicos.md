# AGD-010 — Catálogo de serviços

## Identificação e objetivo

- História P0 AGD-010, implementada na branch `agd-010-catalogo-servicos` a partir da `main` atualizada em `1090b44`.
- Entregar gestão administrativa de serviços isolada por tenant, com criação, edição, ativação e inativação, sem antecipar agenda, disponibilidade ou reservas.
- Requisitos: documento 01 §4; documento 02 §§2 e 4.2; documento 03 §5 (rotas administrativas), §7 (`ServiceCreated`) e §15 (testes); documento 04 §§4–5; documento 06 critério AGD-010.

## Escopo e exclusões

- Inclui domínio, V7, casos de uso e rotas administrativas da tabela de Serviços, UI autenticada, validação, CAS/versionamento, auditoria e idempotência transacionais, e evento `ServiceCreated` da outbox por estar explicitamente listado no documento 03.
- Tenant/ator vêm exclusivamente do principal `AuthenticatedAdmin` e da associação revalidada em banco. Leituras/mutações por ID alheio retornam a mesma resposta não reveladora de recurso inexistente.
- Não inclui agenda, disponibilidade, reservas/snapshots de reserva, pagamento, fornecedores, publicação de endpoint público nem alteração de status comercial do tenant.
- Preço informativo e atributos opcionais de cor/ordenação ficam omitidos da UI/API deste recorte: a moeda é definida pelo produto, mas não há decisão de moeda/formato. A omissão respeita o caráter opcional do preço; registrar decisão antes de expor preço.

## Critérios verificáveis

| Critério | Evidência esperada |
|---|---|
| Criar, editar, ativar e inativar | Casos de uso, API protegida e fluxo administrativo UI |
| Duração múltipla de 15 e positiva; buffer múltiplo de 15 e não negativo | Validação de domínio, constraints PostgreSQL e testes; decisão do usuário em 15/10/2026 confirma buffer zero |
| Nome único entre ativos do tenant por comparação normalizada | Índice único parcial em PostgreSQL e corrida concorrente |
| Isolamento e não revelação HTTP | chamadas autenticadas para próprio/alheio/inexistente; sem mudança ou evento de sucesso em negação |
| Mutação auditável/idempotente | sucesso, replay, digest divergente e rollback em PostgreSQL real |
| Evento previsto | `ServiceCreated` gravado atomicamente com criação; não inventar eventos de update/ativação |
| Migração segura | instalação V1–V7 limpa, upgrade V6→V7, validate e rerun sem mudar V1–V6 |
| Inativo fora de novas ofertas públicas sem tocar reservas existentes | estado de domínio é implementado; endpoint público e prova ponta a ponta ficam pendentes de prontidão/publicação, agenda de AGD-011 e decisão sobre o gate |

## Decisões, dependências e riscos

- NFKC + trim + lowercase `Locale.ROOT` é a canonicalização técnica do nome para unicidade, mantendo nome de apresentação; índice parcial `WHERE status='ACTIVE'` resolve concorrência. Registrar em ADR.
- Cada alteração usa `expectedVersion` e incrementa a versão; chamadas mutáveis exigem `Idempotency-Key`; digest inclui tenant confiável, alvo, versão e campos canônicos. Resultado replay sempre revalida associação e existência.
- Criação recebe status explícito; não definir ativação/default implicitamente. `buffer_after_minutes = 0` é permitido pelo modelo 02 e campo opcional do documento 01, e foi confirmado pelo usuário em 15/10/2026; AGD-010 foi alinhada à decisão.
- Documento 01 exige link público oculto até a configuração mínima válida, mas essa configuração inclui capacidade/agenda futura não existente. Até esclarecer o gate e implementar a dependência AGD-011, rotas públicas continuam deny-all; não inferir prontidão de `Tenant.ACTIVE`.
- Sem outbox artificial: usar somente `ServiceCreated` que o documento 03 declara. Evento usa ID estável do serviço, tenant, versão e operation/correlation IDs; payload allowlisted sem request nem nome/descrição.
- Sem prazo ou expurgo novo; pendência geral de retenção segue vigente.

## Estratégia de implementação e testes

1. Domain value object/aggregate `Service`, comandos e contratos de repositório no módulo catalog.
2. V7 com FK tenant, status, checks e índice parcial; grants mínimos ao role runtime conforme V6.
3. JDBC repository e casos de uso transacionais integrando `AdminAccessPolicy`, auditoria, idempotência e outbox apenas na criação.
4. Controller DTO-only sob `/api/v1/admin/services`; React usa sessão/cookie backend e CSRF existentes.
5. Testes de domínio, API autenticada (próprio, cross-tenant, inexistente, sem CSRF), Testcontainers PostgreSQL para migration/uniqueness/concorrência/CAS/replay/rollback/grants, e Vitest para fluxo UI.

## Progresso e validação

- [x] 15/10/2026 — `origin/main` confirmou merge #3 (`1138395`) e #4 (`1090b44`); working tree limpa; branch derivada da main atualizada.
- [x] Implementação funcional, revisão independente em leitura e validação local; achados pertinentes corrigidos. Código testado em `4678f2481b11b427b77502523eab9638f0013b30`.
- [x] App CI e CodeQL push/PR passaram no head documental `5d64bf8ed88feb727af7757c666bda6b7bc39eeb` (runs `38801099775`, `38801112602`, `38801099641`, `38801112548`); browser E2E real passou após a correção CSRF `eaddf0f`. A implementação funcional testada permanece idêntica ao head de código `d88f175`; links em `VALIDATION.md`.
- [x] PR [#5](https://github.com/LsaBarbosa/kronos_agendamento/pull/5) aberto para `main`; integração autorizada em 15/10/2026 após revisão/checks. Sem deploy.
- [ ] Homologação e critérios dependentes de agenda/publicação.

| Critério | Comando/procedimento | Esperado | Observado |
|---|---|---|---|
| Backend e PostgreSQL | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:check verify` em `backend/` | Esperado: spotless, arquitetura e testes passam | Passou: `BUILD SUCCESS`, 27 unitários/arquiteturais e 41 integrações em PostgreSQL 16.13/Testcontainers, no SHA `4678f2481b11b427b77502523eab9638f0013b30`. |
| Frontend | `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH npm run format:check && npm run lint && npm test -- --run && npm run check` em `frontend/` | Esperado: format, lint, teste e build passam | Passou no commit `4678f2481b11b427b77502523eab9638f0013b30`: Prettier/Oxlint, 2 testes Vitest, TypeScript e build Vite. |
| Dependências frontend e sintaxe browser | `npm audit --audit-level=high` e `node --check scripts/dev/test-keycloak-browser.mjs` | Passaram no commit de código: 0 vulnerabilidades; sintaxe válida. Audit precisou de rede autorizada após DNS indisponível no sandbox. |
| Qualidade de patch | `git diff --cached --check` antes do commit; scanner de secrets existente no App CI | Diff sem whitespace inválido. Trivy não está instalado localmente; scan de secrets/imagem fica pendente do CI, sem alegar aprovação local. |
| Migração | suíte `verify` em DB limpo e upgrade V6→V7 | Esperado: aplicação limpa/upgrade, validate e rerun idempotente | Passou em PostgreSQL real: fresh V1–V7, upgrade V6→V7, `validate` e reexecução com zero mudanças; V1–V6 preservadas. |
| CI remoto | App CI/CodeQL push e PR | esperado: todos os jobs passam | Passou em `d88f1751004803d766691a82efdaf3692ad52757`: App CI `38798696307`/`38798702298`, CodeQL `38798696210`/`38798702362`. Browser E2E passou depois de corrigir a ordem de obtenção do CSRF. A falha anterior em `cd61e60` e a correção estão registradas em `VALIDATION.md`. |

## Encerramento ou retomada

- Estado permanece parcial: o gate público depende de decisão e da prontidão de agenda; snapshots só podem ser comprovados com reservas em AGD-015. Buffer foi resolvido em 15/10/2026; apresentação/moeda de preço seguem em `OPEN-QUESTIONS.md`.
- `STATUS.md`, `VALIDATION.md`, `TRACEABILITY.md` e `OPEN-QUESTIONS.md` registram SHAs, runs, decisões e limites. CI está verde no head publicado; registrar eventual novo head documental separado.
- Próxima dependência funcional: AGD-011 — Grade semanal e políticas.

## Revisão para integração — 15/10/2026

- Usuário autorizou executar as recomendações e integrar após validação: buffer zero ou positivo em múltiplos de 15 confirmado; publicação pública continua bloqueada até condição explícita aprovada e implementada. Requisito e ADR atualizados.
- Revisão independente em leitura do backend administrativo não identificou bloqueadores reproduzíveis de isolamento/CAS/atomicidade. Revisão por agente não é aprovação humana no GitHub.
- Corrigido vínculo de Idempotency-Key na UI: retry do mesmo conteúdo mantém chave; alteração de conteúdo/versão/ação/alvo usa outra chave. Chaves são descartadas após sucesso confirmado ou logout; não são persistidas no navegador.
- Regressões verificam resposta perdida/retry e troca de comando. Backend/migrations não alterados nesta revisão; V1–V7 preservadas.
- Limites de revisão: sem JDK 21 ou Docker neste ambiente, backend depende do CI remoto do head entregue. Listagem sem paginação e endurecimento adicional da constraint SQL de detalhes de auditoria (CHECK com NULL) são melhorias registradas; API Java aplica allowlist. A revisão não demonstra homologação.
- Gate público e snapshots persistidos em reservas continuam pendentes. O bloqueio público aprovado não equivale à implementação do gate.

Regressão browser adicional: perde deliberadamente a resposta HTTP após commit de criação e repete o submit com a mesma chave; consulta autenticada exige exatamente um serviço. Sintaxe `node --check scripts/dev/test-keycloak-browser.mjs` passou; execução depende do App CI do head entregue.
