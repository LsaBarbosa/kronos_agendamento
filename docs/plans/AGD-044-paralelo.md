# AGD-044 — Exportação CSV/PDF: acordo de trabalho paralelo

## Identificação e base

- História: AGD-044 — Exportação CSV e PDF, documento 06 §7, critério E2E-11.
- Base exata: head do PR #25/branch `agd-043-reativacao-renovacao`, `78921069f97e0b590511c99a1e0154e926d88d16`.
- PR #25 continua draft e depende de #24; ambos permanecem sem merge. AGD-044 também dependerá de AGD-043 e terá base `agd-043-reativacao-renovacao`, não `main`.
- Branch de integração: `agd-044-exportacoes`; worktree isolada `/tmp/kronos-agd044`.
- Migração remota mais recente verificada: V40; primeira livre prevista V41. Confirmar novamente antes de criar.
- Contrato inicial: `docs/openapi/admin-exports.yaml`, commit publicado antes de liberar a UI.

## Objetivo, referências e limites

Implementar solicitação, snapshot, processamento, armazenamento cifrado, consulta, download autorizado, expiração e auditoria de exportações CSV/PDF. Referências: documento 01 §§13–14; documento 02 §§4.8 e 10; documento 03 seção “Exportações”, eventos `ExportRequested`/`ExportAvailable`/`ExportExpired`, e seção 12; documento 04 §§10 e 13; documento 05 §§10–13 e 22; documento 06 AGD-044/E2E-11.

Inclui filtros documentados de período, status, serviço e categoria disponível; um job ativo por tenant; retries em 30 s, 1 min e 2 min; disponibilidade de 30 min; isolamento, snapshot imutável, criptografia em repouso e auditoria. Não inclui AGD-045, exportação excepcional de suporte, dupla aprovação, novas categorias sem aprovação, retenção/exclusão geral, escolha/contratação de storage ou key-management provider.

## Estado confirmado e questões

- Módulo `exports` existe apenas como `package-info`; não há tabela, OpenAPI específico, worker, storage nem testes da história.
- Reuso: OIDC/associação atual e CSRF; `AuditEventRecorder`; outbox; jobs persistidos com lease/fencing conforme padrões existentes.
- Falta adaptação de produção para storage S3-compatível e gerenciamento real de chaves. A porta falha fechada sem implementação/configuração real; adapters de memória são somente teste/E2E e não comprovam prontidão.
- A categoria `APPOINTMENTS` aparece no exemplo canônico. A especificação enumera outras classes de conteúdo, mas não aprova enum nem colunas/PII por categoria. Pergunta aberta registrada em `OPEN-QUESTIONS.md`: proposta inicial é derivar uma allowlist mínima do `AdminAppointmentResponse`, sem telefone, detalhes integrais de auditoria ou dados financeiros; não materializar outras categorias até decisão.
- Documento 04 menciona reautenticação para exportação excepcional, não para download administrativo regular. O download comum revalida OIDC + associação atual + tenant + estado/versão/expiração; capacidade de AGD-045 continua fora do escopo. Não haverá URL assinada como autoridade.
- Datas `from`/`to` serão locais inclusivas no timezone IANA confiável do tenant, seguindo as consultas administrativas existentes. O filtro não recebe timezone/tenant do cliente.

## Contrato acordado antes da implementação extensa

OpenAPI em `docs/openapi/admin-exports.yaml`:

- `POST /api/v1/admin/exports`: body `format`, `category`, `filters`; `Idempotency-Key` obrigatória e escopada pelo tenant autenticado. Mesmo corpo/chave recupera `exportId` original; digest divergente retorna `IDEMPOTENCY_KEY_REUSED`; outra exportação ativa retorna `EXPORT_ALREADY_ACTIVE`. Filtros usam datas locais inclusivas, janela máxima 366, lista de status única com no máximo cinco valores.
- `GET /api/v1/admin/exports/{exportId}`: estado minimizado e critérios aceitos; IDs inexistentes/alheios retornam resposta não reveladora.
- `POST /api/v1/admin/exports/{exportId}/download-token`: emite handle opaco em corpo `no-store`, vinculado a export/tenant e até `expiresAt`; o handle não substitui sessão/associação atual.
- `GET /api/v1/admin/exports/{exportId}/content`: exige sessão administrativa atual, associação ativa, tenant correspondente, handle válido, estado `AVAILABLE` e `now < expiresAt`. Resposta privada `no-store`; sem acesso após expiração, mesmo se remoção física atrasar.
- O ETag de `/content` é o SHA-256 da representação em claro para validação HTTP; não autentica, não substitui o handle nem a autorização atual.
- `DELETE /api/v1/admin/exports/{exportId}`: expiração antecipada somente de `AVAILABLE`, CAS por `If-Match`; retry terminal é idempotente. Não cancela job em processamento.
- Estados externos: `REQUESTED`, `PROCESSING`, `RETRY_WAIT`, `AVAILABLE`, `FAILED_FINAL`, `EXPIRED`. Erros expostos são códigos normalizados, sem payload do storage/PII.
- Em timeout/5xx/resposta inválida de criação, a UI preserva body, tenant-context e chave; retry usa a mesma intenção. Rejeições definitivas não prendem o formulário.
- Job registra snapshot de filtros, `snapshotAt`, row count, digest SHA-256 do artefato cifrado, tamanho, estados/retries e expiração; nunca inclui tenant/ator do request como autoridade.

## Acordo de arquivos e ownership

**Coordenador/backend — este terminal:**

- `backend/**`, V41+, integrações PostgreSQL e testes.
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
- O snapshot inicial omite nome e telefone do cliente; contém apenas identificadores e campos de atendimento já documentados. A allowlist de PII permanece questão de produto aberta; nenhuma categoria adicional foi criada.
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

## Retomada

Trabalho backend continua em `/tmp/kronos-agd044`, branch `agd-044-exportacoes`. Não trocar a branch nem editar os arquivos de frontend do acordo. Base e PRs predecessores permanecem sem merge.
