# AGD-044 — Exportação CSV/PDF: acordo de trabalho paralelo

## Identificação e base

- História: AGD-044 — Exportação CSV e PDF, documento 06 §7, critério E2E-11.
- Base exata: head do PR #25/branch `agd-043-reativacao-renovacao`, `78921069f97e0b590511c99a1e0154e926d88d16`.
- PR #25 continua draft e depende de #24; ambos permanecem sem merge. AGD-044 também dependerá de AGD-043 e terá base `agd-043-reativacao-renovacao`, não `main`.
- Branch de integração: `agd-044-exportacoes`; worktree isolada `/tmp/kronos-agd044`.
- Migração remota mais recente na base: V41; V1–V41 permanecem imutáveis. A implementação acrescenta V42 após conferência da numeração da branch.
- Contrato inicial: `docs/openapi/admin-exports.yaml`, commit publicado antes de liberar a UI.

## Objetivo, referências e limites

Implementar solicitação, snapshot, processamento, armazenamento cifrado, consulta, download autorizado, expiração e auditoria de exportações CSV/PDF. Referências: documento 01 §§13–14; documento 02 §§4.8 e 10; documento 03 seção “Exportações”, eventos `ExportRequested`/`ExportAvailable`/`ExportExpired`, e seção 12; documento 04 §§10 e 13; documento 05 §§10–13 e 22; documento 06 AGD-044/E2E-11.

Inclui filtros documentados de período, status, serviço e categoria disponível; um job ativo por tenant; retries em 30 s, 1 min e 2 min; disponibilidade de 30 min; isolamento, snapshot imutável, criptografia em repouso e auditoria. Não inclui AGD-045, exportação excepcional de suporte, dupla aprovação, novas categorias sem aprovação, retenção/exclusão geral, escolha/contratação de storage ou key-management provider.

## Estado confirmado e questões

- Módulo `exports` existe apenas como `package-info`; não há tabela, OpenAPI específico, worker, storage nem testes da história.
- Reuso: OIDC/associação atual e CSRF; `AuditEventRecorder`; outbox; jobs persistidos com lease/fencing conforme padrões existentes.
- Falta adaptação de produção para storage S3-compatível e gerenciamento real de chaves. A porta falha fechada sem implementação/configuração real; adapters de memória são somente teste/E2E e não comprovam prontidão.
- Decisão explícita do usuário em 23/10/2026: primeira allowlist é somente `APPOINTMENTS`, com appointmentId/serviceId, nome do serviço do snapshot, horários/ocupação, timezone, duração, buffer, status e criação. Sem telefone, clientId/nome do cliente, auditoria ou dados financeiros; outras categorias permanecem fora. Horários e identificadores de atendimento ainda são dados pessoais protegidos por authorization/no-store/cifra; isso não aprova retenção.
- Documento 04 menciona reautenticação para exportação excepcional, não para download administrativo regular. O download comum revalida OIDC + associação atual + tenant + estado/versão/expiração; capacidade de AGD-045 continua fora do escopo. Não haverá URL assinada como autoridade.
- Datas `from`/`to` serão locais inclusivas no timezone IANA confiável do tenant, seguindo as consultas administrativas existentes. O filtro não recebe timezone/tenant do cliente.

## Contrato acordado antes da implementação extensa

OpenAPI em `docs/openapi/admin-exports.yaml`:

- `POST /api/v1/admin/exports`: body `format`, `category`, `filters`; `Idempotency-Key` obrigatória e escopada pelo tenant autenticado. Mesmo corpo/chave recupera `exportId` original; digest divergente retorna `IDEMPOTENCY_KEY_REUSED`; outra exportação ativa retorna `EXPORT_ALREADY_ACTIVE`. Filtros usam datas locais inclusivas, janela máxima 366, lista de status única com no máximo cinco valores.
- `GET /api/v1/admin/exports/{exportId}`: estado minimizado e critérios aceitos; IDs inexistentes/alheios retornam resposta não reveladora.
- `GET /api/v1/admin/exports/active`: consulta tenant-scoped somente leitura; `200` com o mesmo DTO minimizado/ETag forte do GET individual para REQUESTED, PROCESSING, RETRY_WAIT ou AVAILABLE ainda válido; `204` sem corpo quando não houver ativo. Todas as respostas são `no-store`. Uma conclusão entre consultas pode legitimamente resultar em 204; não dispara nova solicitação.
- `POST /api/v1/admin/exports/{exportId}/download-token`: emite handle opaco em corpo `no-store`, vinculado a export/tenant e até `expiresAt`; o handle não substitui sessão/associação atual.
- `GET /api/v1/admin/exports/{exportId}/content`: exige sessão administrativa atual, associação ativa, tenant correspondente, handle válido, estado `AVAILABLE` e `now < expiresAt`. Resposta privada `no-store`; sem acesso após expiração, mesmo se remoção física atrasar.
- O ETag de `/content` é o SHA-256 da representação em claro para validação HTTP; não autentica, não substitui o handle nem a autorização atual.
- GET individual e `/active` retornam ETag forte da versão como decimal positivo canônico entre aspas (por exemplo `"4"`). A UI preserva literalmente o valor recebido e o envia como `If-Match`; ETag de `/content` é digest do artefato e não é versão do job.
- `DELETE /api/v1/admin/exports/{exportId}`: expiração antecipada somente de `AVAILABLE`, CAS por `If-Match` forte exatamente no formato `"<versão decimal positiva canônica>"`; header ausente, malformado, fraco, wildcard, lista, zero, sinal, zeros à esquerda ou whitespace não contornam CAS e são rejeitados como `INVALID_IF_MATCH`. Versão obsoleta retorna 412; retry terminal conserva a semântica idempotente acordada. Não cancela job em processamento.
- Estados externos: `REQUESTED`, `PROCESSING`, `RETRY_WAIT`, `AVAILABLE`, `FAILED_FINAL`, `EXPIRED`. Erros expostos são códigos normalizados, sem payload do storage/PII.
- Em timeout/5xx/resposta inválida de criação, a UI preserva body, tenant-context e chave; retry usa a mesma intenção. Rejeições definitivas não prendem o formulário.
- Job registra snapshot de filtros, `snapshotAt`, row count, digest SHA-256 do artefato cifrado, tamanho, estados/retries e expiração; nunca inclui tenant/ator do request como autoridade.

## Acordo de arquivos e ownership

**Coordenador/backend — este terminal:**

- `backend/**`, V42+, integrações PostgreSQL e testes.
- `docs/openapi/admin-exports.yaml`, `docs/plans/AGD-044-paralelo.md`, `docs/development/OPEN-QUESTIONS.md`, `STATUS.md`, `VALIDATION.md`, `TRACEABILITY.md` e contratos/ADR quando necessários.
- `frontend/src/App.tsx` somente para montar o componente entregue.
- `scripts/dev/test-keycloak-browser.mjs` para E2E compartilhado.
- configuração/CI/dependências somente se necessárias e aprovadas no escopo.

**Implementador frontend no outro terminal — caminhos exclusivos, sem editar os arquivos acima:**

- `frontend/src/exports/ExportCenter.tsx`
- `frontend/src/exports/ExportCenter.test.tsx`
- `frontend/src/exports/types.ts`
- `frontend/src/exports/exports.css`

Assinatura React acordada: `export function ExportCenter(props: ExportCenterProps): JSX.Element`, onde `ExportCenterProps` contém `tenantId: string`, `csrf: { headerName: string; token: string } | null` e `onSessionInvalid: (status: number) => void`. `tenantId` serve apenas para vincular rascunho de retry no navegador; nunca seleciona ou autoriza tenant no servidor. A UI importa `apiBaseUrl` do módulo de configuração existente. O componente gerencia POST/recovery, polling, download e apresentação de estados; App apenas fornece props e o renderiza para sessão admin autenticada.

Qualquer divergência de contrato precisa ser comunicada em novo commit e descrita antes de o outro terminal alterar seu consumidor.

## Estratégia técnica e validação

1. Após este acordo/contrato: criar worktree frontend a partir do commit de contrato e liberá-la. Seguir backend em paralelo.
2. Persistir `ExportJob`, critérios e chaves idempotentes com constraints; índice único parcial garante um ativo por tenant. Claims individuais, lease e fencing em PostgreSQL. Retry do item só nos tempos normativos; falhas definitivas terminam sem repetição.
3. Snapshot de dados e critérios é imutável. Usar transação/isolamento e materialização coerentes por categoria; documentar watermark/semântica de snapshot e evitar transaction aberta durante upload.
4. Gerar CSV com quoting RFC 4180 e neutralização de células iniciadas por `=`, `+`, `-`, `@` mesmo após whitespace/controles; PDF legível, paginação e escape de texto. Sem campos fora da allowlist aprovada.
5. Cifrar artefato com AES-256-GCM, AAD tenant/export e key-id externo; chave ausente/ inválida bloqueia solicitação/processamento. Não gravar plaintext temporário persistente, chave, PII ou conteúdo em logs.
6. Portas para artifact storage e keyring; adapter de teste isolado. Sem storage S3-compatível/segredo real, API de produção responde `503` e não aceita job nem produz falso `AVAILABLE`.
7. Auditoria para solicitação, disponibilidade, download autorizado e expiração; outbox `ExportRequested`, `ExportAvailable`, `ExportExpired` somente quando estado correspondente confirmar. Job/receipt/auditoria/outbox compartilham a transação.
8. Testar PostgreSQL real: filtro/tenant, corrida uma ativa/tenant, snapshot, idempotência, worker/lease/retries, arquivo CSV/PDF, criptografia, download não revelador, expiração, rollback, grants e instalação/upgrade/Flyway. E2E OIDC/Chrome com infraestrutura descartável e adapter sintético; não tocar Compose compartilhado.
9. Revisar diff integrado, executar gates completos, publicar branch e abrir um PR draft único para a branch de AGD-043. Conferir CI push/PR; separar resultados funcionais do security scan Keycloak conhecido.

## Progresso

- [x] Head #25, working tree/worktrees e migração V40 confirmados.
- [x] OpenAPI inicial e acordo de ownership preparados antes de implementação extensa.
- [x] Commit do contrato/acordo e liberação da worktree frontend (`60c55b57bed37f7c45644ac7c0b4ece32f2b117a`).
- [x] Backend funcional, testes direcionados e validação PostgreSQL.
- [x] PR #26 aberto como draft, base `agd-043-reativacao-renovacao`, dependência #25 explicitada.
- [ ] Integração App/E2E com frontend entregue.
- [ ] Integração App/E2E, revisão final e CI no head combinado.

## Progresso técnico do backend

- Implementados os casos de uso, API administrativa, snapshot tenant-scoped, digest idempotente, revalidação da associação, worker de claim individual com lease/CAS e geração CSV/PDF.
- V41 foi criada após confirmar V40 como última migration publicada. A migração cria job/handles, índice parcial de um ativo por tenant, constraints, grants mínimos e estende allowlists de auditoria/outbox preservando as anteriores.
- Snapshots e artefatos são cifrados com AES-256-GCM, AAD de propósito/tenant/export e key-id externo. Sem chave ou storage real configurado, solicitação falha com 503 sem persistir job. `UnavailableExportArtifactStore` é o default de runtime.
- O snapshot inicial omite nome, clientId e telefone do cliente; contém appointmentId/serviceId e campos documentados do atendimento. A allowlist mínima `APPOINTMENTS` foi aprovada pelo usuário em 23/10/2026 e registrada em OPEN-QUESTIONS como decisão resolvida; outras categorias, retenção e gates operacionais permanecem fora/pendentes.
- Fencing do storage usa a versão monotônica do job. O contrato inclui tombstone durável de exclusão: limpeza terminal registra a versão, e escrita atrasada nessa versão ou anterior é rejeitada. Repetição de versão viva/ciphertext é idempotente; adapter de produção ausente permanece fechado.
- A validade do arquivo é `availableAt` até `availableAt + 30 min`; acesso exige `now < expiresAt`. O handle tem digest persistido, mas cada acesso também revalida associação OIDC atual, ator, tenant e estado.
- Retry de falha transitória de storage usa 30 s, 60 s e 120 s, até três retries; falha definitiva termina sem nova tentativa. Storage temporariamente indisponível após aceitação do job também agenda retry; não se registra sucesso. Falha de confirmação transacional deixa claim/lease recuperável, sem marcar o job como falha definitiva. Chamadas e remoção de storage não ocorrem na transação de estado.
- O snapshot é obtido em uma única consulta SQL e guarda filtros, `snapshotAt`, contagem e digest. A geração posterior usa exclusivamente os bytes cifrados, sem reler dados mutáveis do catálogo/agenda.

## Validações e integração paralela

- Testes unitários cobrem quoting RFC 4180, neutralização de fórmula após whitespace, controles, espaço Unicode e caracteres de formatação invisíveis; datas cobrem limite de 366 e janela invertida/excessiva. PDF é validado quanto à legibilidade/texto.
- Testes PostgreSQL/Testcontainers cobrem isolamento, associação revogada, corrida de um job ativo, idempotência/replay, download, expiração, rollback, grants, retry transitório, indisponibilidade de storage e limpeza concorrente com escrita de worker obsoleto. O filtro local considera interseção da ocupação `[startsAt, occupiedUntil)` com a janela pedida, inclusive quando o atendimento começou antes dela.
- A execução Testcontainers inicial foi bloqueada pelo socket no sandbox. Execuções autorizadas alcançaram PostgreSQL 16.13 e aplicaram V1–V41. `TenantPersistenceIT` passou 43 testes e a execução focada final de `ExportLifecycleIT` passou 16 testes, incluindo a concorrência sincronizada do fencing de limpeza. A suíte completa precisa ser repetida após integrar a UI.
- A worktree UI permanece separada em `/tmp/kronos-agd044-ui`, branch `agd-044-exportacoes-ui`, limpa no commit `60c55b57bed37f7c45644ac7c0b4ece32f2b117a`; `git fetch origin agd-044-exportacoes-ui` informa que ainda não há ref remota. Não editar seus quatro arquivos exclusivos. O coordenador só integrará após receber commits e relatório explícito.
- Backend validado localmente em JDK 21: suíte completa `spotless:check verify` passou com 82 testes unitários/arquiteturais e 310 integrações PostgreSQL 16.13; após ajuste final dos adapters de tombstone, `ExportLifecycleIT` passou 16/16 e os unitários de filtro/geração passaram 5/5. A migration V41 também passou em instalação limpa, upgrade V40→V41, validate/rerun e grants (`TenantPersistenceIT` 43/43).
- Revalidação completa posterior no SHA `04c05305280a00acf5089e42f8694bbfa54c7113`: JDK 21 `spotless:check verify` passou com 85 unitários/arquiteturais + 310 integrações PostgreSQL 16.13/Testcontainers; validações V41 e grants incluídas. UI continua não entregue na worktree isolada, então esta evidência é exclusivamente backend.
- OpenAPI explicita envelope de erros, janela de 366 datas, filtros e o ETag de representação como não autorizador. A allowlist de dados permanece limitada a APPOINTMENTS sem nome/telefone de cliente; demais categorias/campos continuam como decisão aberta.
- E2E OIDC compartilhado ainda não foi alterado nem executado. Será atualizado após a entrega UI, com ambiente descartável e adapter controlado apenas no perfil de teste; não usar o Compose compartilhado.
- No head documental `bc19d98d2d6d783fb79b4e56594acb78097f4ec0`, App CI push/PR concluiu todos os jobs: verify, frontend, E2E OIDC e secret scans passaram; security falhou somente no scan Keycloak conhecido. CodeQL push/PR passou com analisadores concluídos. O E2E atual não cobre o módulo de exportação. A worktree frontend ainda não tem alterações e os checks do SHA final integrado continuam pendentes.

### Continuação de coordenação — CI do head 05c4d11

- Estado confirmado via GitHub: PR #26 está aberto/draft, `agd-044-exportacoes` → `agd-043-reativacao-renovacao`, head `05c4d1154ab2623eb9ae49e837731a68aa164216`, base SHA `78921069f97e0b590511c99a1e0154e926d88d16`, mergeable e sem reviews/comentários pendentes. Não houve integração dos PRs anteriores.
- A worktree `/tmp/kronos-agd044-ui` continua limpa no commit contratual `60c55b57bed37f7c45644ac7c0b4ece32f2b117a`; o GitHub ainda não possui ref remota `agd-044-exportacoes-ui`. Os quatro caminhos exclusivos do implementador frontend não foram alterados. Aguardamos handoff explícito para integrar; não presumir entrega a partir do CI frontend genérico.
- No head atual, todos os workflows/jobs/check-runs terminaram: App CI push [39963379453](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39963379453) e PR [39963386217](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39963386217) passaram verify, frontend, E2E OIDC geral e secret scans; ambos falharam exclusivamente no scan Keycloak conhecido. CodeQL push [39963379375](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39963379375) e PR [39963386377](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39963386377) passaram. E2E de exportação e CI do conjunto integrado permanecem pendentes.
- Revisores independentes não puderam iniciar porque o ambiente atingiu o limite de threads. A revisão final disponível é sequencial e não equivale à aprovação humana do GitHub.
- No head `ee4d5a1be97af64746c16a84eb093abd9483b52b`, App CI push [39964457631](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39964457631) e PR [39964466502](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39964466502) fecharam todos os jobs: verify, frontend, E2E OIDC geral e secret scans passaram; security falhou somente no scan Keycloak conhecido. CodeQL push [39964457595](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39964457595) e PR [39964466481](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39964466481) passaram. O E2E OIDC segue sem provar exportação. Esta atualização é documental; revisar os workflows do head emitido após o commit.
- O head documental `b199ef23e9a1058961b82949822d06b9a64f9b50` também concluiu todos os workflows: App CI push [39965370133](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39965370133) e PR [39965376456](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39965376456) passaram verify/frontend/E2E OIDC geral/secret scans e falharam apenas no scan Keycloak; CodeQL push [39965370179](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39965370179) e PR [39965376344](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39965376344) passaram. O commit seguinte desta nota requer nova conferência de CI.
- Após decisão do usuário de 23/10/2026, a pergunta sobre a allowlist inicial foi resolvida: APPOINTMENTS mínimo sem nome/telefone/clientId, auditoria ou finanças. Isto não aprova retenção nem categorias adicionais. No head seguinte `d1eb25b`, App CI push [39966184039](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39966184039) e PR [39966190600](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39966190600) passaram verify/frontend/E2E OIDC geral/secret scans e falharam apenas no scan Keycloak. CodeQL push [39966183959](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39966183959) e PR [39966190655](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39966190655) passaram. A prova browser de exportação ainda depende do módulo UI.
- Handoff FRONTEND LIBERADO emitido para `/tmp/kronos-agd044-ui`, branch `agd-044-exportacoes-ui`, SHA de contrato `60c55b57bed37f7c45644ac7c0b4ece32f2b117a`. Os caminhos exclusivos da UI permanecem sob ownership do segundo terminal. A integração do App/E2E ocorrerá somente após `FRONTEND PRONTO PARA INTEGRAÇÃO`.
- No head `e810d82`, App CI push [39967196980](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39967196980) e PR [39967206406](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39967206406) fecharam todos os jobs: verify, frontend, OIDC E2E geral e secret scans passaram; security falhou apenas no Keycloak. CodeQL push [39967197028](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39967197028) e PR [39967205297](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39967205297) passaram. E2E específico e validação integrada permanecem pendentes.
- No head `638848c`, verify/frontend/secret scans e CodeQL push/PR passaram; o scan Keycloak falhou. App CI PR passou E2E OIDC; push falhou na remarcação legada, com conflito e respostas 200 mas sem texto DOM de sucesso. Causa desconhecida, não atribuída a AGD-044 nem explicada pelo evento PR. O E2E específico aguarda UI.
- No head `f2bda04`, App CI push/PR passou verify/frontend/E2E OIDC geral/secret scans e falhou somente no scan Keycloak. CodeQL PR passou; CodeQL push falhou no build Java porque o Maven Wrapper não baixou Maven 3.9.11; causa do fetch não determinada e sem rerun. E2E push/PR passou, sem explicar a intermitência histórica do SHA `638848c`. Os workflows estão terminais; E2E de exportação aguarda a UI.

## Retomada

Trabalho backend continua em `/tmp/kronos-agd044`, branch `agd-044-exportacoes`. Não trocar a branch nem editar os arquivos de frontend do acordo. Base e PRs predecessores permanecem sem merge.

### Revalidação do backend e CI — head `65e9d2f5c4bc2d7860f245180239f81b349aa898`

- A suíte completa local repetida em JDK 21 passou: 85 testes unitários/arquiteturais e 310 integrações PostgreSQL 16.13/Testcontainers.
- App CI push [39969591995](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39969591995) e PR [39969600408](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39969600408) estão terminais: verify, frontend, OIDC E2E geral e secret scans passaram; security falhou somente no scan Keycloak conhecido. CodeQL push [39969591967](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39969591967) e PR [39969600389](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39969600389) passaram.
- A branch/worktree UI está separada e continua sob ownership do outro terminal; o head observado avançou para `86add0b0a5e4d4a4c1738e086f2cd42f666e7478`, mas o handoff para integração ainda não foi recebido. Este registro não presume integração: aguardar `FRONTEND PRONTO PARA INTEGRAÇÃO`; a prova E2E de exportação permanece pendente.

### Retomada atual — recuperação ativa, CAS e V42 (23/10/2026)

- Estado confirmado: coordenador em `/tmp/kronos-agd044`, branch `agd-044-exportacoes`, PR #26 aberto/draft para `agd-043-reativacao-renovacao`; a branch UI permanece em `/tmp/kronos-agd044-ui`, sem alterações locais do coordenador. O commit UI formalmente entregue `86add0b0a5e4d4a4c1738e086f2cd42f666e7478` foi integrado por merge local `802b007`; commits UI posteriores aguardam o handoff formal de continuação.
- Contrato atualizado em `ac0c86a181d330914f24559e8a536cbe8b47c85c`: `/active` é tenant-scoped, `200` com DTO minimizado + ETag forte/`no-store` ou `204` vazio/`no-store`; GET individual e `/active` usam versão positiva decimal canônica entre aspas; `DELETE` rejeita If-Match ausente/não canônico e retorna 412 para versão obsoleta. A resposta 204 após conflito não inicia novo comando.
- V42 substitui o índice de um job ativo/tenant para incluir `AVAILABLE`. Preflight somente leitura lista possíveis tenants/IDs para resolução explícita; migration falha em duplicidade e não apaga nem reescreve histórico. POST materializa expirações vencidas do tenant sob a transação do comando antes de tentar ocupar o slot; GET `/active` continua somente leitura e filtra `expires_at > now`.
- E2E isolado usa artifact store de filesystem apenas no profile `e2e`, persistindo bytes cifrados e metadata de fencing; produção continua com storage indisponível/fail-closed. O roteiro usa worker real, retry após resposta perdida e verifica job/efeitos, conteúdo CSV sem nome/telefone, ETag, expiração e negação posterior.
- Validação direcionada: `spotless:check verify` com `-Dit.test=TenantPersistenceIT` passou (43 integrações PostgreSQL 16.13, 86 unitários/arquiteturais); `-Dtest=ExportDocumentGeneratorTest,E2eFileExportArtifactStoreTest -Dit.test=ExportLifecycleIT` passou (4 unitários + 24 integrações PostgreSQL). Suíte final `spotless:check verify` passou com 86 unitários/arquiteturais + 318 integrações PostgreSQL 16.13, sem falhas/erros/skips.
- Frontend integrado no SHA inicialmente entregue: Node 24.14.1, `npm run format:check`, `npm run lint`, `npm test` (5 arquivos/30 testes, incluindo DOM em happy-dom), `npm run check` (TypeScript + Vite build) passou. Esse resultado não cobre commits UI posteriores nem browser E2E do componente.
- `node --check scripts/dev/test-keycloak-browser.mjs`, Prettier de `docs/openapi/admin-exports.yaml` e `git diff --check` passaram. Browser E2E de exportação ainda depende do workflow descartável após publicar a branch; o Compose compartilhado não foi iniciado localmente.
- Revisão de código foi sequencial; dois revisores independentes foram solicitados, mas o ambiente recusou novas threads. Isto não equivale a revisão independente nem aprovação humana.

#### CI do head `cf7308c2554d4557997f4ea6c6722fca7afff1f1`

- App CI push [40008192649](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/40008192649) e PR [40008201019](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/40008201019) tiveram todos os jobs terminais. Verify/PostgreSQL, frontend e secret scans passaram; security falhou somente no scanner Keycloak conhecido.
- E2E push e PR falharam no cenário já existente de GET da assinatura após logout, no `waitUntil` antes do fluxo de exportação. Evidência: OIDC autenticado, timeout após 60s, artifact só com log Vite. Causa do estado DOM ainda desconhecida. O roteiro foi instrumentado com estado minimizado ao falhar; essa mudança serve ao diagnóstico, não comprova correção. Não foi repetida execução idêntica.
- CodeQL push [40008192761](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/40008192761) e PR [40008201614](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/40008201614) terminaram success nos dois analisadores.
- Consequentemente, o browser E2E de AGD-044 não foi alcançado e permanece sem evidência remota. O bloqueio de segurança, esta falha OIDC e a antiga intermitência de remarcação são registros separados.
