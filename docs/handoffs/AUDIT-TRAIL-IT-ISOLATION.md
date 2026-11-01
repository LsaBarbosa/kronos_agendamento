# Handoff — isolamento da assertion de AuditTrailIT

## Base e escopo

- Worktree: `/tmp/kronos-audit-trail-it`
- Branch: `fix/audit-trail-it-isolation`
- Base inicial: `main` em `86c1bf0bb6e0bb1d76df3eda4a99f4c15094bdf8`.
- Atualização: merge normal de `main` em `4edf01373871e9b7e338f3e42f0c2c50e0e2e56b`, registrada pelo merge commit `9e294e0ab713f298fbcfe3be4990f7d24f51db38`. Isso incorpora a integração de #29; não houve rebase nem reescrita dos commits publicados.
- Antes desta atualização, #30 estava aberto/draft em `863ab519787b92b998a9d1cc94ff4e005c4deb62`.
- Alteração de código restrita a `backend/src/test/java/com/appagendamento/audit/AuditTrailIT.java`. Este handoff é o único documento novo; nenhum documento geral de status foi tocado.

## Causa e reprodução

`auditInsertFailureRollsBackCriticalTenantMutation` lia `count(*)` de toda `agendamento.audit_event` antes de instalar um trigger `BEFORE INSERT` global que rejeita todas as novas linhas. A mutação e o `TENANT_CREATED` sob teste são uma única transação, mas a observação do teste incluía todos os tenants e operações.

A regressão evita comparar contagens globais. Instala um trigger que rejeita apenas eventos cuja correlação ou trace seja igual ao UUID exclusivo da operação em teste. Com o trigger ativo, cria outro tenant legitimamente pelo caminho `CreateTenant.execute` → `AuditEventRecorder` → `JdbcAuditEventRecorder`, que persiste `TENANT_CREATED`; depois tenta criar o tenant alvo com a correlação bloqueada. A checagem por slug prova que a mutação alvo foi revertida, a checagem por correlação prova a ausência de seu evento, e consultas por tenant/ação provam que os eventos legítimos criados antes e durante o trigger continuam persistidos. A prova não depende de gravações de outros tenants entre os checkpoints.

A assertion do cenário de rollback delimita a evidência: slug do tenant alvo não persistido e nenhum evento com a correlação exclusiva da operação. O controle positivo cria um `TENANT_CREATED` real usando aquela correlação e verifica que a mesma assertion falha se o evento estiver presente. As duas criações independentes no teste de rollback demonstram que eventos legítimos adicionais não causam falha na nova prova.

## Limite da conclusão histórica

A evidência antiga `189 → 206` não contém os IDs, ações ou tenants das 17 linhas, nem o dump/log diferencial do banco usado no verify. Portanto não é possível atribuir aqueles registros históricos ao writer, scheduler ou módulo específico. A reprodução confirma a causa estrutural que torna a comparação global instável, não prova que esses mesmos 17 registros foram gerados pelo caminho da reprodução.

O Maven/Failsafe não configura execução paralela. Os testes de integração usam uma base PostgreSQL compartilhada por JVM (ou URL explicitamente configurada), e o contexto Spring habilita schedulers de trial/dunning/outbox/exportações/notificações. Esses são caminhos plausíveis de gravações concorrentes durante uma suíte, não evidência de que participaram do episódio histórico.

## Validação da entrega inicial

Executado com JDK 21.0.11 e PostgreSQL 16.13 criado e removido pelo Testcontainers, sem Compose compartilhado:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./backend/mvnw -f backend/pom.xml spotless:check
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./backend/mvnw -f backend/pom.xml -Dit.test=AuditTrailIT test-compile failsafe:integration-test failsafe:verify
```

Resultado: `AuditTrailIT` 5/5, zero falhas/erros/skips; build Failsafe sucesso. O teste focal passou também antes da alteração em PostgreSQL Testcontainers, confirmando que era uma fragilidade de assertion e não uma falha determinística do caminho transacional de produção.

Uma tentativa intermediária no namespace restrito falhou antes de executar os testes porque Java não podia abrir `/var/run/docker.sock` (`Operation not permitted`). A repetição com acesso ao daemon autorizado encontrou Testcontainers PostgreSQL e passou; a falha intermediária não é tratada como resultado do teste/código.

## Publicação e CI da entrega inicial

- Commit inicial publicado: `878aa5cadbf4383f296690849c18e03957b9750c` (`test: isolate audit rollback assertion`).
- PR draft separado para `main`: https://github.com/LsaBarbosa/kronos_agendamento/pull/30.
- No SHA `878aa5c`, `verify`, CodeQL, security, frontend, keycloak-image, análises Java/TypeScript e verificações de segredos passaram nos eventos observados.
- Um dos dois jobs `oidc-browser-e2e` passou. O outro falhou no cenário de remarcação, esperando “Remarcação confirmada e persistida” e recebendo conflito HTTP 409. Essa ocorrência é alheia aos dois arquivos deste PR e não foi investigada ou alterada nesta frente, conforme o escopo. O E2E completo, portanto, não está verde neste SHA.
- Atualização de handoff publicada em `71e059db60d36b684f8a761cac89a9c46f28ed11`; nesse SHA todos os checks dos eventos push e PR passaram, incluindo `verify`, os dois `oidc-browser-e2e`, CodeQL, security, frontend, keycloak-image, análises Java/TypeScript e verificações de segredos. O 409 acima não se repetiu; não foi investigado como outra intermitência nem alterado nesta frente.

## Entrega

### Atualização após integração de AGD-052

- Merge normal de `4edf01373871e9b7e338f3e42f0c2c50e0e2e56b` em `9e294e0ab713f298fbcfe3be4990f7d24f51db38`; o diff da atualização funcional continua restrito a este teste.
- O teste agora mantém eventos legítimos antes e durante o trigger específico por correlação, e verifica sua persistência por tenant/ação sem contagem global.
- `spotless:apply`, `spotless:check` e `git diff --check` passaram após a mudança.
- `AuditTrailIT`: 5 testes, 0 falhas, 0 erros, 0 skips, PostgreSQL 16.13 via Testcontainers.
- Comando do teste focal: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./backend/mvnw -f backend/pom.xml -Dit.test=AuditTrailIT test-compile failsafe:integration-test failsafe:verify`.

Sem alteração de produção, migrations, constraints, workers, timeout, política transacional ou regra de negócio. A evidência histórica `189 → 206` permanece sem atribuição linha a linha. O PR draft continua aberto; checks do novo head serão acompanhados e reportados separadamente.
