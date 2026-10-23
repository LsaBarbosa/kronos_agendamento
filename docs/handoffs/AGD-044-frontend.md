# Handoff frontend — AGD-044

## Base e integração

- Contrato utilizado: `60c55b57bed37f7c45644ac7c0b4ece32f2b117a` (`docs/openapi/admin-exports.yaml`).
- Branch de entrega: `agd-044-exportacoes-ui`.
- Componente: `frontend/src/exports/ExportCenter.tsx`, export nomeado `ExportCenter`.
- Montagem: importar `{ ExportCenter, type ExportCenterProps }`; a assinatura é `export function ExportCenter(props: ExportCenterProps): ReactElement`. Passe `tenantId`, `csrf` (`{ headerName, token } | null`) e `onSessionInvalid`. `tenantId` é usado somente como contexto local de recuperação; não é enviado à API. O coordenador monta em `App.tsx` para sessão administrativa.
- Base de API: `apiBaseUrl` de `frontend/src/config.ts`; endpoints em `/v1/admin/exports`.

## Comportamento implementado

- Formato CSV/PDF; categoria permitida `APPOINTMENTS`; filtros de data local inclusiva, status e UUID de serviço.
- Solicitação com `Idempotency-Key`; timeout, 408, 5xx exceto 503 e resposta 202 inválida preservam body/chave como resultado incerto. Repetição usa a mesma intenção. HTTP 503 do contrato é tratado como rejeição definitiva sem job.
- Recuperação após reload guarda somente tenant-context, intenção/chave e exportId em `sessionStorage`. Não guarda handle, bytes, blob, conteúdo ou snapshot completo. Sessão ausente/logout e mudança de tenant invalidam estado e contexto armazenado.
- Polling para estados em andamento; revalida a geração, tenant, sessão e exportId antes de aplicar resposta.
- Download pede handle e usa-o somente no header da leitura de conteúdo com cookie atual e `no-store`; o arquivo vive em Blob/URL temporária em memória. Handles não são persistidos.
- Expiração automática desabilita download e não afirma remoção física. Expiração antecipada chama DELETE apenas para `AVAILABLE`, com `If-Match` baseado na versão consultada.

## Integração pendente

- O contrato de `EXPORT_ALREADY_ACTIVE` informa o código, mas não oferece busca/listagem nem promete `exportId` na resposta. A tela informa o conflito, porém não consegue acompanhar o job que já existe. Se o backend devolver um `ExportJob` no conflito, o componente poderá ser adaptado para consumi-lo; alternativa é endpoint de consulta da exportação ativa por sessão/tenant.
- O OpenAPI não define a serialização textual exata do ETag. A UI envia `If-Match: "<version>"`; confirmar que corresponde ao backend.
- O contrato não define limites de período ou semântica de status vazio. A UI omite `statuses` quando nada é selecionado e valida apenas data inicial menor/igual à final.
- `ExportJob` requer `operationId`; validar que respostas 202 e GET sempre o incluem conforme o schema.

## Validação e limites

Validações executadas com Node 24.14.1 e lockfile do frontend:

- `npm ci` — concluído; 48 pacotes auditados, sem vulnerabilidades reportadas.
- `npm run format:check` — passou.
- `npm run lint` — passou.
- `npm test` — 23 testes existentes passaram.
- Teste dedicado `.test.tsx` executado via Vitest programático com `include: ['src/**/*.test.tsx']` — 7 testes passaram. A configuração padrão do repositório inclui apenas `*.test.ts`, portanto o teste TSX não entra em `npm test` padrão.
- `npm run check` — TypeScript e build de produção passaram.

Os 7 testes dedicados usam dados sintéticos, helpers de contrato/estado e renderização server-side; não simulam o transporte nem exercitam cliques/polling em DOM. Criação ponta a ponta, recuperação real após reload, polling concorrente, troca de sessão, download e expiração permanecem para o E2E integrado pelo Terminal 1. A validação local não demonstra integração com o backend real.

Seletores sugeridos para E2E:

- `getByRole('heading', { name: 'Exportar agendamentos' })`
- `getByLabel('Formato')`, `getByLabel('Data inicial')`, `getByLabel('Data final')`, `getByLabel('ID do serviço (opcional)')`
- `getByRole('group', { name: /Status/ })` e checkbox pelos nomes Confirmada/Concluída etc.
- `getByRole('button', { name: 'Solicitar exportação' })`
- `getByRole('button', { name: 'Recuperar solicitação pendente' })`
- `getByRole('article')` dentro de `.export-center__job`, heading de estado (`Solicitada`, `Em processamento`, `Arquivo disponível`, etc.), botões `Atualizar estado`, `Baixar arquivo`, `Encerrar acesso agora`
- alertas `role=alert` e mensagens de acompanhamento `role=status`.

## Arquivos atribuídos

- `frontend/src/exports/ExportCenter.tsx`
- `frontend/src/exports/ExportCenter.test.tsx`
- `frontend/src/exports/types.ts`
- `frontend/src/exports/exports.css`
- `docs/handoffs/AGD-044-frontend.md`
