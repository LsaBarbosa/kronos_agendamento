# Handoff frontend — AGD-044

## Base e integração

- Contrato inicial: `60c55b57bed37f7c45644ac7c0b4ece32f2b117a`.
- Contrato atualizado utilizado: `8c5affa7e73bc15a850ac37d8619d62a94aba32e` (`origin/agd-044-exportacoes`); lido diretamente, sem incorporar a branch backend.
- Branch de entrega: `agd-044-exportacoes-ui`; SHA de partida desta continuação: `86add0b0a5e4d4a4c1738e086f2cd42f666e7478`.
- Commit da implementação desta continuação: `2689b376ed7859cf5636cb4ca3b55b967755d395` (`feat(frontend): recover active exports and bind ETags`).
- Commit de preservação da intenção em todo 5xx e novos casos de sessão/download: `57ac5db397aac22615cde6a8bee6b75bbb161bff`.
- Configuração DOM sincronizada exatamente do snapshot `2ce63feb1f41d9ed607ff63102061d72ea58b8c0` nos três arquivos autorizados; commit local: `dae59e8032bd37d36ff6f3406e91f932d7faad2a`.
- Commit dos testes DOM executados: `49df83d5d829317a3f41ed0fcc36b170444c0d41`.
- Correção de `safeFilename`, regressões de nomes e retry de download após 503: `2fe6dd390a78c705fc993560480c219ad4dc7b13`.
- Correção P3 para limpar o aviso antigo ao iniciar retry de download: `c7eb9734306d3f02f324a7cb97d721eb80d7669f`.
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
- Resultado HTTP de todos os status 500–599 permanece incerto e conserva body/chave; resposta perdida após simulação de commit também é recuperada com retry da mesma intenção.
- Os testes DOM usam `fireEvent.change` do Testing Library para alterar campos controlados por React; respostas simuladas existem somente na suíte.
- O fallback de `safeFilename` compara diretamente o `ExportFormat` e escolhe a extensão CSV/PDF correta; a sanitização do header continua aplicada antes de preservar o nome proposto.
- Cada tentativa válida de download limpa o erro e o aviso anterior após passar pelos guards; o aviso de sucesso só é definido após iniciar o clique do link temporário.

## Testes e validação

- Configuração importada de `2ce63feb1f41d9ed607ff63102061d72ea58b8c0`: `npm ci` passou e instalou 110 pacotes.
- `npm test -- src/exports/ExportCenter.test.tsx` — 1 arquivo, 36 testes passaram; Vitest descobriu e executou a suíte DOM `happy-dom` dedicada.
- `npm test` — 5 arquivos, 59 testes passaram, incluindo os `.test.tsx` e os testes `.test.ts` existentes.
- `npm run format:check` — passou após formatar o teste atualizado.
- `npm run lint` — passou.
- `npm run check` — `tsc --noEmit -p tsconfig.json` e Vite build passaram.
- `git diff --check` — passou antes da sincronização; os commits incluem somente arquivos sob responsabilidade frontend e os três arquivos compartilhados autorizados.

## Cenários DOM executados

- Filtros e POST; duplo clique não duplica comando; polling chega a AVAILABLE e para após terminal.
- Reload com job conhecido busca GET individual; reload sem job/intenção busca `/active`; reload com intenção incerta repete POST original sem consultar `/active`.
- `EXPORT_ALREADY_ACTIVE` seguido de `/active` 200 ou 204, sem POST automático na resposta 204.
- Falha de transporte, resposta perdida após commit simulado, 408, 502, 503 e 202 inválido preservam intenção; os status 500–599 são verificados exaustivamente; retry mantém chave/body; 400 conhecido libera intenção.
- ETag forte de `/active`/GET é enviado literal em `If-Match`; sem ETag, o comando aguarda GET atualizado; 412 recarrega ETag e só envia nova mutação após ação do usuário.
- Download autorizado via handle em memória, falha 503, expiração 410, cleanup de URL e resposta tardia de `/content` após logout sem iniciar download.
- CSV e PDF sem `Content-Disposition`, nome inseguro rejeitado pela sanitização e nome de header válido preservado; resposta 503 de `/content` conserva estado disponível, não inicia download e permite retry bem-sucedido.
- Sequência DOM sucesso → retry pendente → 503: aviso antigo some ao iniciar, erro aparece sem criar novo URL/anchor e job permanece disponível; tentativa seguinte baixa normalmente e restaura aviso de sucesso. O teste original 503 seguido de sucesso também permanece.
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

- E2E integrado com backend real permanece com Terminal 1; esta entrega não declara integração concluída.
- `/active` e ETag foram validados contra o commit de contrato acima, não contra o backend em execução.

## Arquivos atribuídos

- `frontend/src/exports/ExportCenter.tsx`
- `frontend/src/exports/ExportCenter.test.tsx`
- `frontend/src/exports/types.ts`
- `frontend/src/exports/exports.css`
- `docs/handoffs/AGD-044-frontend.md`
