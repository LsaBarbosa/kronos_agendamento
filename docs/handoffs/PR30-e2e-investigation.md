# Revisão de auditoria e diagnóstico do E2E do PR #30

## Corte e isolamento

- Corte revisado: `d87b101bf8d3dbc07bff898f570adfe53445e9be`.
- Branch de diagnóstico: `diagnose/pr30-e2e`.
- `AuditTrailIT` foi inspecionado em leitura na worktree do Terminal 2; nenhum arquivo dessa frente foi alterado.
- Alterações nesta continuação limitadas ao roteiro E2E, helper/harness próprios e este handoff.

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

Antes do reload o roteiro guarda `performance.timeOrigin` e inicia um único orçamento monotônico de 60 segundos. A espera combina, sem reiniciar esse orçamento:

- `timeOrigin` diferente do documento anterior;
- marcador de instalação do interceptador;
- `document.readyState === 'complete'`;
- textos `Trial encerrado` e `Operação preparada: MONTHLY`;
- botão habilitado;
- exatamente uma requisição após o clique.

Reload, avaliações CDP, clique, polls de estado e espera da primeira requisição compartilham a mesma deadline. Cada chamada CDP recebe timeout restante e sinal de cancelamento. Ao expirar, uma requisição CDP pendente é retirada da fila local e seu timer cancelado; timers de deadline e polling são limpos/consumidos. O erro identifica a etapa e imprime apenas estado minimizado: caminho, prontidão, marcador, tipo/contagem da fila, presença booleana dos campos, existência da intenção e respostas recentes limitadas a CSRF/checkout. Não registra chave, contexto, corpo, cookies, credenciais ou conteúdo da intenção. As assertions originais de chave, contexto, corpo, receipt e efeito único permanecem intactas.

O helper de deadline preserva os defaults dos demais chamadores de `command`/`evaluate` (30 segundos por comando); cancelamento é opcional e só é usado neste cenário. O orçamento do percurso não excede os 60 segundos anteriores.

## Validação e limites

- `node --check scripts/dev/test-keycloak-browser.mjs`: aprovado.
- `node --check scripts/dev/checkout-retry-deadline.mjs`: aprovado.
- `node --check scripts/dev/test-checkout-retry-deadline.mjs`: aprovado.
- `node scripts/dev/test-checkout-retry-deadline.mjs`: aprovado; fake clock prova consumo do orçamento entre estágios, timeout total e ausência de timers ativos, sem esperar 60 segundos reais.
- O mesmo harness força uma avaliação e um comando de clique pendentes; ambos recebem abort e deixam zero timers ativos.
- `git diff --check`: aprovado.
- E2E completo do Keycloak/PostgreSQL não executado nesta rodada; o Compose compartilhado não foi operado. O harness temporal não substitui navegador/aplicação E2E.
- A execução de Chrome/servidor localhost adicional foi tentada isoladamente, mas não completou uma prova browser reproduzível do cenário específico; não é contabilizada como validação aprovada.

## Diagnóstico da revogação administrativa

No run `41265515999`, job `124727532529`, o E2E falhou em `scripts/dev/test-keycloak-browser.mjs:2565` com `Administrador desativado não teve a sessão revogada.` O log histórico foi preservado localmente em `/tmp/pr30-run-41265515999/oidc-browser-e2e.log` (SHA-256 `cd3c75d1cea117401f1cd950c5b46743f8af887457e699b7cbdcfb248c4ecbcf`). O artifact `oidc-browser-e2e-logs` contém somente `agendamento-vite.log`. Nenhum deles registra o status HTTP efetivamente recebido nessa chamada; o valor histórico não pode ser recuperado.

O cenário agora registra somente método, ordem, fase, status, redirecionamento, caminho final e estado concluído/pendente das chamadas a `/api/v1/admin/session`; erros de transporte ficam limitados ao nome da classe. A instrumentação confirma por booleanos se `admin_user` estava desativado, se a linha da sessão existia antes/depois da chamada e se continua ausente após a reutilização do cookie original. Também registra status/redirecionamento de um recurso protegido. Não lê ou escreve esses valores sensíveis nos logs: cookie e ID de sessão permanecem apenas em memória para consultar a persistência e restaurar o cookie antigo pelo CDP após reativar o usuário.

A ordem instrumentada é explícita: marca a fase de desativação, aplica e confirma o estado no PostgreSQL, executa o fetch de sessão rotulado como explícito, verifica o recurso protegido, reativa o usuário em `finally`, restaura o cookie antigo sem registrá-lo e verifica que `/session` e o recurso protegido continuam negados. Requisições automáticas observadas no intervalo aparecem separadamente na sequência, inclusive se ainda estiverem pendentes. Esta instrumentação/controlada sequência ainda precisa executar no E2E CI; não demonstra retrospectivamente a causa do run antigo nem conclui se a resposta inesperada é sincronização do teste ou defeito do backend.

## Estado

Esta é uma correção local para revisão na branch isolada. Não altera `AuditTrailIT`, produção, Clock, migrations ou workflows. Não houve push, comentário no GitHub, merge ou deploy.
