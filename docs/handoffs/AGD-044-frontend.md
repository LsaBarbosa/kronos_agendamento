# Handoff frontend — AGD-044

## Base e integração

- Contrato inicial: `60c55b57bed37f7c45644ac7c0b4ece32f2b117a`.
- Contrato atualizado utilizado: `8c5affa7e73bc15a850ac37d8619d62a94aba32e` (`origin/agd-044-exportacoes`); lido diretamente, sem incorporar a branch backend.
- Branch de entrega: `agd-044-exportacoes-ui`; SHA de partida desta continuação: `86add0b0a5e4d4a4c1738e086f2cd42f666e7478`.
- Commit da implementação desta continuação: `2689b376ed7859cf5636cb4ca3b55b967755d395` (`feat(frontend): recover active exports and bind ETags`).
- Componente: `frontend/src/exports/ExportCenter.tsx`, export nomeado `ExportCenter`.
- Montagem: importar `{ ExportCenter, type ExportCenterProps }`; passar `tenantId`, `csrf` (`{ headerName, token } | null`) e `onSessionInvalid`. O tenant serve somente para contexto local e nunca é enviado à API.
- API relativa a `apiBaseUrl`: `/v1/admin/exports`, `/v1/admin/exports/active` e endpoints por `exportId`.
- O SHA final da branch é o HEAD após este handoff commit e está repetido na mensagem de entrega.

## Alterações desta continuação

- Consulta `/active` ao iniciar sem exportId ou intenção incerta; job encontrado é persistido apenas pelo ID e recuperado pelo GET individual após reload.
- `EXPORT_ALREADY_ACTIVE` limpa a intenção definitivamente rejeitada e consulta `/active`. Resposta 200 abre acompanhamento; 204 informa que não há ativo e permite ação manual sem criar outra solicitação automaticamente. Falha na descoberta mantém a criação bloqueada até nova consulta.
- Intenção incerta tem precedência: reload não consulta `/active`; exibe a intenção local e o retry repete o POST com o mesmo body e `Idempotency-Key`.
- GET individual e `/active` capturam literalmente o ETag forte no formato `"<decimal>"`. A expiração antecipada envia esse valor em `If-Match`; ETag do conteúdo nunca é usado. Sem ETag forte, o comando fica desabilitado até nova consulta.
- Em 412, a UI informa conflito, consulta o estado/ETag atual e aguarda nova ação explícita; não repete DELETE automaticamente.
- Contexto/sessão invalidados abortam requisições pendentes, liberam locks, removem estado privado e impedem resposta tardia de iniciar download. Timers e URL temporária do Blob são limpos no unmount/context change.

## Testes e validação

- `npm ci` — passou (47 pacotes adicionados localmente; sem mudanças em manifest/lockfile).
- `npm run format:check` — passou.
- `npm run lint` — passou.
- `npm test` — 23 testes padrão passaram; o include compartilhado não encontra o arquivo `.test.tsx`.
- `npm run check` — `tsc --noEmit` e Vite build passaram.
- Comando tentado: `node --input-type=module -e "import { startVitest } from 'vitest/node'; await startVitest(['src/exports/ExportCenter.test.tsx'], { run: true, root: process.cwd(), include: ['src/**/*.test.tsx'], environment: 'happy-dom' });"` — não iniciou a suíte: `MISSING DEPENDENCY Cannot find dependency 'happy-dom'`.
- A suíte padrão do Vitest continua com `include: ['src/**/*.test.ts']`; ela não descobre `ExportCenter.test.tsx`.
- Há 20 casos de interação (incluindo quatro variantes de falha) escritos no arquivo atribuído, usando `@vitest-environment happy-dom`, React DOM e respostas/fetch controlados; nenhum foi executado neste checkout por falta de ambiente DOM e exclusão de TSX pela configuração compartilhada.
- Os testes SSR/helpers anteriores permanecem no arquivo. Build/TypeScript inclui a verificação estática do arquivo de teste, mas não demonstra interação em navegador.

## Pedido ao coordenador — arquivos compartilhados

Para executar a suíte de interação, o Terminal 1 precisa, em arquivos sob seu ownership:

1. adicionar `happy-dom` como devDependency e atualizar `frontend/package.json`/`package-lock.json`;
2. ampliar `frontend/vite.config.ts` para descobrir `src/**/*.test.tsx` (mantendo também `.test.ts`). O arquivo de teste já seleciona `happy-dom` por diretiva.

Não alterei dependências nem configuração comum nesta branch. Depois dessa integração, executar `npm test` e corrigir eventuais diferenças específicas do DOM instalado.

## Cenários preparados para DOM

- Filtros e POST; duplo clique não duplica comando; polling chega a AVAILABLE e para após terminal.
- Reload com job conhecido busca GET individual; reload sem job/intenção busca `/active`; reload com intenção incerta repete POST original sem consultar `/active`.
- `EXPORT_ALREADY_ACTIVE` seguido de `/active` 200 ou 204, sem POST automático na resposta 204.
- Falha de transporte, 408, 502 e 202 inválido preservam chave/body; retry mantém ambos; 400 conhecido libera intenção.
- ETag forte de `/active`/GET é enviado literal em `If-Match`; 412 recarrega ETag sem novo DELETE automático.
- Download autorizado via handle em memória, falha 503, expiração 410, cleanup de URL e interrupção por logout.
- Polling, criação e download em voo não restauram dados após logout/troca; abort de requests pendentes no logout/unmount, timers e URL temporária no ciclo de vida.
- As respostas de teste são sintéticas e ficam exclusivamente nos testes.

## Seletores sugeridos para E2E

- `getByRole('heading', { name: 'Exportar agendamentos' })`
- `getByLabel('Formato')`, `getByLabel('Data inicial')`, `getByLabel('Data final')`, `getByLabel('ID do serviço (opcional)')`
- `getByRole('group', { name: /Status/ })`; checkboxes Confirmada, Concluída etc.
- `getByRole('button', { name: 'Solicitar exportação' })`
- `getByRole('button', { name: 'Recuperar solicitação pendente' })`
- `getByRole('button', { name: 'Consultar exportação ativa' })`
- Job: `locator('.export-center__job')`, heading de estado e botões `Atualizar estado`, `Baixar arquivo`, `Encerrar acesso agora`.
- Mensagens: `role=alert` para erro e `role=status`/`output` para atualização e expiração.

## Limitações

- Interações DOM ainda não executadas até o coordenador instalar `happy-dom` e incluir TSX no Vitest.
- E2E integrado com backend real permanece com Terminal 1; esta entrega não declara integração concluída.
- `/active` e ETag foram validados contra o commit de contrato acima, não contra o backend em execução.

## Arquivos atribuídos

- `frontend/src/exports/ExportCenter.tsx`
- `frontend/src/exports/ExportCenter.test.tsx`
- `frontend/src/exports/types.ts`
- `frontend/src/exports/exports.css`
- `docs/handoffs/AGD-044-frontend.md`
