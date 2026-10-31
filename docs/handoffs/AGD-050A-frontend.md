# Handoff frontend — AGD-050-A

## Base e branch

- Base compartilhada: `ff6622d347f6db06c5aabf06bcfc5b635b03f9cb`.
- Branch de UI: `agd-050a-retention-assessment-ui`.
- Worktree: `/tmp/kronos-agd050a-ui`.
- Escopo deste handoff: componente, tipos, CSS e testes próprios em `frontend/src/retention/`; nenhum ajuste em `App.tsx`, OpenAPI, configuração compartilhada ou roteiro E2E.

## Componente e montagem

Componente exportado: `frontend/src/retention/RetentionAssessmentPanel.tsx`.

```tsx
<RetentionAssessmentPanel
    key={`${session.tenantId}:${session.role}`}
    enabled={Boolean(session)}
    tenantId={session?.tenantId ?? null}
    onSessionInvalid={handleNoShowPolicySessionInvalid}
/>
```

Props: `enabled: boolean`, `tenantId: string | null` e `onSessionInvalid(status: number): void`. `tenantId` é contexto local para particionar a instância e descartar respostas antigas; ele não é enviado no path, query, body ou headers. A consulta usa `apiBaseUrl + '/v1/admin/retention/assessment'`, `credentials: 'same-origin'` e `cache: 'no-store'`.

O painel apresenta categorias e estados de avaliação, contagens exatas e limitadas, motivos, fatos allowlisted, estado da política, bloqueios informativos, `referenceAt` em UTC e a indicação de `NON_ATOMIC_READ_COMMITTED`. Zero, indisponibilidade e categoria não consultada têm apresentação distinta. Não há armazenamento de resultado, endpoint de mutação ou controle para descarte/hold/política.

Mudança de tenant ou logout troca a chave da instância React e desmonta o contexto anterior; o cleanup aborta a requisição. Uma flag local de ciclo de vida valida a resposta após `fetch` e novamente após `response.json()`. A trava síncrona impede refresh concorrente, além do botão ficar desabilitado durante a consulta. Respostas 401/403 invocam `onSessionInvalid` e removem o resultado da sessão atual.

## Arquivos

- `frontend/src/retention/RetentionAssessmentPanel.tsx`
- `frontend/src/retention/RetentionAssessmentPanel.test.tsx`
- `frontend/src/retention/types.ts`
- `frontend/src/retention/retention.css`
- `docs/handoffs/AGD-050A-frontend.md`

## Evidências locais

Ambiente: Node `v24.14.1`, dependências instaladas por `npm ci` do lockfile sem alteração de dependências.

- `npm test -- src/retention/RetentionAssessmentPanel.test.tsx`: 7 testes passaram.
- `npm run format:check`: passou.
- `npm run lint`: passou sem warnings.
- `npm test`: 66 testes em 6 arquivos passaram.
- `npm run check`: TypeScript e build Vite passaram.
- `git diff --check`: executado antes do commit.

Os testes DOM usam respostas sintéticas; comprovam renderização, estados, recovery de consulta, não envio do tenant, e descarte de respostas atrasadas após troca de tenant, logout e unmount. Não são E2E com backend/PostgreSQL nem evidência de operação ou conformidade. A integração em `App.tsx` e o E2E compartilhado continuam com o Terminal 2.
