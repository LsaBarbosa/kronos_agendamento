# Revisão e integração dos PRs #3 e #4

Data: 15/10/2026. O usuário solicitou administrar os PRs e merges.

## Revisão executada

- PR #3: diff `81ce55d..23731b8`, auditoria transacional, migração V5, privilégios, consulta por tenant e provisionamento. Revisão independente em leitura sem bloqueadores concretos. Observação não bloqueadora: o CHECK JSON da V5 pode aceitar NULL quando faltam chaves; a validação Java impede esse caso no caminho atual. Endurecimento posterior deve usar nova migration.
- PR #4: diff `23731b8..e7a4d41`, idempotência, replay autorizado, atomicidade, leases, recuperação e deduplicação. Identificado bloqueador: resultado ambíguo após expiração do lease não era persistido, permitindo nova publicação automática do mesmo item.
- App CI e CodeQL push/PR foram conferidos como aprovados nos heads originais dos dois PRs; esses resultados não validam a correção local posterior.
- `git diff --check` passou nos dois diffs originais. As revisões inspecionaram testes existentes; não executaram novamente PostgreSQL, Docker ou homologação.

## Correção local preparada para o PR #4

- `markOutcomeUnknown` preserva estado CLAIMED + claim_token e permite registrar reconciliação após vencimento, desde que não tenha ocorrido reassunção.
- As confirmações de entrega/retry/terminal continuam exigindo lease válido.
- Worker reivindica um item por vez, imediatamente antes de publicar, e respeita o limite de itens por ciclo. Uma transição rejeitada encerra o ciclo com diagnóstico seguro.
- Regressões preparadas com PostgreSQL real: ambiguidade após expiração, proteção contra token antigo e ausência de claims antecipados para o lote.
- Os testes de regressão ainda precisam de execução no ambiente Java 21/PostgreSQL ou CI. Não estão declarados aprovados neste registro.

## Bloqueio observado e retomada

Na primeira tentativa, o conector retornou HTTP 403 `Resource not accessible by integration` nas operações de atualização e merge do PR #3. Nenhum merge ou retarget ocorreu nessa tentativa. O ambiente da revisão não possui `gh` autenticado nem credencial disponível ao Git CLI; `git push --dry-run` falhou com `could not read Username`.

Permissão `push` nos metadados do usuário não comprovou autorização de escrita do token da integração. Nenhuma restrição foi contornada, e não houve aprovação humana registrada pela conta conectada.

Após o usuário solicitar nova tentativa, a escrita do conector funcionou. O PR #3 foi integrado por merge commit `1138395acb4a5f50979461fce3894db60bd5b761`. A base do PR #4 foi atualizada para `main`; a correção de lease deve passar pelo CI antes da decisão de merge desse PR. A integração foi autorizada pelo mantenedor na sessão, sem representar uma review humana registrada no GitHub.

## Continuidade

1. Disponibilizar acesso autorizado de escrita a PRs/merge e conteúdo.
2. Integrar #3 por merge commit com head esperado `23731b8036861683e0353e18a1b164fe0ce9a47b`.
3. Aplicar a correção preparada sobre a branch do #4, executar Spotless e os testes PostgreSQL, publicar e acompanhar o CI.
4. Atualizar a base do #4 para main, conferir que o diff contém apenas AGD-005 e a correção, e validar o commit de integração proposto.
5. Integrar #4 com head esperado e conferir CI final de main. Manter homologação, retenção e demais pendências funcionais explicitamente abertas.
