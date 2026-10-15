# Revisão e integração dos PRs #3 e #4

Data: 15/10/2026. O usuário solicitou administrar os PRs e merges.

## Revisão executada

- PR #3: diff `81ce55d..23731b8`, auditoria transacional, migração V5, privilégios, consulta por tenant e provisionamento. Revisão independente em leitura sem bloqueadores concretos. Observação não bloqueadora: o CHECK JSON da V5 pode aceitar NULL quando faltam chaves; a validação Java impede esse caso no caminho atual. Endurecimento posterior deve usar nova migration.
- PR #4: diff `23731b8..e7a4d41`, idempotência, replay autorizado, atomicidade, leases, recuperação e deduplicação. Identificado bloqueador: resultado ambíguo após expiração do lease não era persistido, permitindo nova publicação automática do mesmo item.
- App CI e CodeQL push/PR foram conferidos como aprovados nos heads originais dos dois PRs; esses resultados não validam a correção local posterior.
- `git diff --check` passou nos dois diffs originais. As revisões inspecionaram testes existentes; não executaram novamente PostgreSQL, Docker ou homologação.

## Correção publicada no PR #4

- `markOutcomeUnknown` preserva estado CLAIMED + claim_token e permite registrar reconciliação após vencimento, desde que não tenha ocorrido reassunção.
- As confirmações de entrega/retry/terminal continuam exigindo lease válido.
- Worker reivindica um item por vez, imediatamente antes de publicar, e respeita o limite de itens por ciclo. Uma transição rejeitada encerra o ciclo com diagnóstico seguro.
- Regressões adicionadas para PostgreSQL real: ambiguidade após expiração, proteção contra token antigo e ausência de claims antecipados para o lote.
- App CI push `38789680677` passou no SHA `9852453`: 25 testes unitários/arquiteturais e 31 integrações, incluindo as regressões. CodeQL push/PR também passou. Evidências e limites em VALIDATION.md.

## Bloqueio observado e retomada

Na primeira tentativa, o conector retornou HTTP 403 `Resource not accessible by integration` nas operações de atualização e merge do PR #3. Nenhum merge ou retarget ocorreu nessa tentativa. O ambiente da revisão não possui `gh` autenticado nem credencial disponível ao Git CLI; `git push --dry-run` falhou com `could not read Username`.

Permissão `push` nos metadados do usuário não comprovou autorização de escrita do token da integração. Nenhuma restrição foi contornada, e não houve aprovação humana registrada pela conta conectada.

Após o usuário solicitar nova tentativa, a escrita do conector funcionou. O PR #3 foi integrado por merge commit `1138395acb4a5f50979461fce3894db60bd5b761`. A base do PR #4 foi atualizada para `main`; a correção de lease deve passar pelo CI antes da decisão de merge desse PR. A integração foi autorizada pelo mantenedor na sessão, sem representar uma review humana registrada no GitHub.

## Continuidade

1. Acesso de escrita restabelecido e PR #3 integrado em `1138395`.
2. PR #4 atualizado para main; histórico incorporado sem force push.
3. Correção e testes publicados em `01f3c62`; Spotless apontou somente o layout de uma linha de log, corrigido em `9852453`.
4. Acompanhar App CI e CodeQL do head corrigido antes do merge protegido pelo SHA esperado.
5. Conferir CI final de main. Homologação, retenção e demais pendências funcionais permanecem abertas.
