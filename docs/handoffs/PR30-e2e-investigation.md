# Revisão de auditoria e diagnóstico do E2E do PR #30

## Corte e isolamento

- Corte revisado: `d87b101bf8d3dbc07bff898f570adfe53445e9be`.
- Branch de diagnóstico: `diagnose/pr30-e2e`.
- `AuditTrailIT` foi inspecionado em leitura na worktree do Terminal 2; nenhum arquivo dessa frente foi alterado.
- Alteração local limitada a `scripts/dev/test-keycloak-browser.mjs` e este handoff.

## Revisão de `AuditTrailIT`

Não encontrei comparação global de contagem total da tabela de auditoria usada como prova de rollback. O rollback da criação consulta a mutação e o evento pelo `tenant_id` do tenant criado; a renomeação compara estado, versão e quantidade de eventos pelo tenant alvo.

O teste de falha transacional correlaciona a ausência do evento por `correlation_id` ou `trace_id` exclusivo da operação. O trigger rejeita somente linhas que carregam essa correlação; tenants criados antes e durante o trigger são controles positivos e seus eventos legítimos são conferidos pelo próprio `tenant_id`. Há também um controle positivo dedicado que persiste um evento com a correlação e comprova que a asserção de ausência falha.

Não identifiquei bloqueador na revisão em leitura. Não executei `AuditTrailIT` nesta frente e não alterei o teste. A execução do Terminal 2 (seu resultado) não é atribuída a esta revisão.

## Diagnóstico do timeout E2E

Run de referência: `41262630415`, job `124718965616`; o job terminou em `failure`. O log confirma que a falha ocorreu em `waitUntil` no ponto do roteiro que espera a primeira requisição, após o cenário anterior de trial e a preparação `MONTHLY`. O erro histórico é somente `Timeout aguardando estado do navegador`; o run não capturou `typeof window.__checkoutRequests` nem seu comprimento. Portanto não é possível concluir retrospectivamente se a fila estava ausente, vazia ou acima de um.

### Mecanismo reproduzido

Um harness determinístico de protocolo, sem Compose ou credenciais, reteve a conclusão da navegação após o ACK de `Page.reload`. Ele reproduziu a sequência antiga: os textos e o botão do documento anterior satisfizeram as condições visuais antes de o script do próximo documento instalar o interceptador; nesse estado a fila era `undefined`. Ao liberar a barreira, o novo documento instalou o interceptador. O harness passou e demonstrou que o sequenciamento do roteiro admite essa corrida.

Limite da prova: a reprodução foi um harness com navegação controlada, não uma execução do Chrome real com a aplicação. Uma tentativa adicional com Chrome local mudou o contexto antes de observar o documento antigo e não demonstrou esse estado; não a conto como prova positiva. O mecanismo reproduzido identifica uma falha de ordenação possível no roteiro, mas não prova qual estado exato ocorreu no runner histórico nem atribui a falha passada exclusivamente a essa causa.

### Correção no roteiro

Antes do reload o roteiro guarda `performance.timeOrigin`. O script injetado no próximo documento define um marcador explícito. Antes de esperar textos ou clicar, o roteiro agora aguarda simultaneamente:

- `timeOrigin` diferente do documento anterior;
- marcador de instalação do interceptador;
- `document.readyState === 'complete'`.

Se a espera pela primeira requisição expirar, a nova mensagem de diagnóstico informa somente origem/caminho, prontidão do documento, marcador, tipo/comprimento da fila, presença booleana dos campos capturados, existência da intenção e respostas recentes limitadas a CSRF/checkout. Não registra chave, contexto, corpo, cookies, credenciais ou conteúdo da intenção. As assertions originais de chave, contexto, corpo, receipt e efeito único permanecem intactas.

## Validação e limites

- `node --check scripts/dev/test-keycloak-browser.mjs`: aprovado.
- `git diff --check`: aprovado.
- Harness determinístico do interleaving ACK-before-navigation com barreira explícita: aprovado; não substitui navegador/aplicação E2E.
- E2E completo do Keycloak/PostgreSQL não executado nesta worktree; o Compose compartilhado não foi operado. A reprodução não substitui esse E2E.
- A execução de Chrome/servidor localhost adicional foi tentada isoladamente, mas não completou uma prova browser reproduzível do cenário específico; não é contabilizada como validação aprovada.

## Estado

Esta é uma correção local para revisão na branch isolada. Não altera `AuditTrailIT`, produção, Clock, migrations ou workflows. Não houve push, comentário no GitHub, merge ou deploy.
