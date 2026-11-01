# Handoff — isolamento da assertion de AuditTrailIT

## Base e escopo

- Worktree: `/tmp/kronos-audit-trail-it`
- Branch: `fix/audit-trail-it-isolation`
- Base inspecionada: `main` em `86c1bf0bb6e0bb1d76df3eda4a99f4c15094bdf8`.
- PR #29 foi consultado antes da publicação em `88ded37ef9bf208aefc83673fb47c1f1371ef8fd`; segue aberto/draft com base `main`, sem merge. `origin/main` permaneceu em `86c1bf0bb6e0bb1d76df3eda4a99f4c15094bdf8`. Não há merge de #29 a incorporar nesta base; repetir a conferência imediatamente antes de publicar.
- Alteração de código restrita a `backend/src/test/java/com/appagendamento/audit/AuditTrailIT.java`. Este handoff é o único documento novo; nenhum documento geral de status foi tocado.

## Causa e reprodução

`auditInsertFailureRollsBackCriticalTenantMutation` lia `count(*)` de toda `agendamento.audit_event` antes de instalar um trigger `BEFORE INSERT` global que rejeita todas as novas linhas. A mutação e o `TENANT_CREATED` sob teste são uma única transação, mas a observação do teste incluía todos os tenants e operações.

A regressão reproduz deterministicamente a divergência sem depender de corrida: depois do baseline, executa uma criação legítima de outro tenant pelo caminho `CreateTenant.execute` → `AuditEventRecorder` → `JdbcAuditEventRecorder`, que persiste `TENANT_CREATED`; então instala a falha de auditoria e tenta criar o tenant alvo. A linha independente permanece, o total global aumenta em um, enquanto a linha do tenant alvo e qualquer evento com o `correlation_id`/`trace_id` exclusivo da tentativa permanecem ausentes. O delta visível é consultado e identificado por tenant e ação.

A assertion do cenário de rollback agora delimita a evidência: slug do tenant alvo não persistido e nenhum evento com a correlação exclusiva da operação. Um controle positivo cria um `TENANT_CREATED` real usando aquela correlação e verifica que a mesma assertion falha se o evento estiver presente. Isso preserva a prova de rollback e prova que o novo escopo detecta um efeito indevido.

## Limite da conclusão histórica

A evidência antiga `189 → 206` não contém os IDs, ações ou tenants das 17 linhas, nem o dump/log diferencial do banco usado no verify. Portanto não é possível atribuir aqueles registros históricos ao writer, scheduler ou módulo específico. A reprodução confirma a causa estrutural que torna a comparação global instável, não prova que esses mesmos 17 registros foram gerados pelo caminho da reprodução.

O Maven/Failsafe não configura execução paralela. Os testes de integração usam uma base PostgreSQL compartilhada por JVM (ou URL explicitamente configurada), e o contexto Spring habilita schedulers de trial/dunning/outbox/exportações/notificações. Esses são caminhos plausíveis de gravações concorrentes durante uma suíte, não evidência de que participaram do episódio histórico.

## Validação

Executado com JDK 21.0.11 e PostgreSQL 16.13 criado e removido pelo Testcontainers, sem Compose compartilhado:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./backend/mvnw -f backend/pom.xml spotless:check
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./backend/mvnw -f backend/pom.xml -Dit.test=AuditTrailIT test-compile failsafe:integration-test failsafe:verify
```

Resultado: `AuditTrailIT` 5/5, zero falhas/erros/skips; build Failsafe sucesso. O teste focal passou também antes da alteração em PostgreSQL Testcontainers, confirmando que era uma fragilidade de assertion e não uma falha determinística do caminho transacional de produção.

Uma tentativa intermediária no namespace restrito falhou antes de executar os testes porque Java não podia abrir `/var/run/docker.sock` (`Operation not permitted`). A repetição com acesso ao daemon autorizado encontrou Testcontainers PostgreSQL e passou; a falha intermediária não é tratada como resultado do teste/código.

## Entrega

Sem alteração de produção, migrations, constraints, workers, timeout, política transacional ou regra de negócio. Ainda pendentes: confirmar novamente o corte remoto de `main`/#29 antes de publicar, fazer commit, abrir PR draft separado para `main` e acompanhar App CI/CodeQL no SHA publicado.
