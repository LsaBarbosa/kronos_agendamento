# ADR-0008 — Idempotência e outbox transacionais

**Data:** 15/10/2026
**Status:** adotada para o incremento AGD-005; limites e critérios de operação permanecem nos contratos das histórias.

## Contexto

Comandos mutáveis precisam sobreviver a retry após resposta perdida sem duplicar mutação, auditoria ou evento. Integrações não podem compartilhar a transação PostgreSQL e podem retornar falha ambígua. A fundação AGD-004 já fornece auditoria append-only e deve permanecer intacta.

## Decisão

- Idempotência é escopada por `(tenant_id, command_scope, key_digest)`, com constraint única PostgreSQL. O digest da chave é SHA-256; o valor bruto `Idempotency-Key` não é armazenado.
- O digest do comando usa canonicalização versionada de campos validados em ordem fixa e UTF-8 length-prefixed. O comando `tenant.rename.v1` inclui tenant confiável, alvo, versão esperada e nome normalizado. Mudanças na semântica geram nova versão de canonicalização/scope.
- Cada identidade lógica possui `operation_id` UUID criado no primeiro processamento; correlation ID pertence a cada requisição e não substitui operationId. Estado e resposta persistida usam enumeração/JSON allowlisted, sem request, cookies, tokens ou headers.
- No comando síncrono local desta fundação, apenas `IN_PROGRESS` e `COMPLETED` são estados do registro. Uma falha/rollback remove a chave junto com os demais writes, permitindo retry sem efeito parcial; erro determinístico não é cacheado. `OPERATION_IN_PROGRESS` fica reservado a operação assíncrona/duravelmente visível e não dispara uma segunda execução.
- Registro inicial, mutação, auditoria de sucesso, evento de outbox e resposta `COMPLETED` são uma única transação. Chaves concorrentes bloqueiam pela constraint única e o perdedor reconsulta o registro confirmado. Operação em andamento só é retornada se houver registro durável ainda não concluído; não iniciar efeito concorrente.
- Mesmo digest concluído reproduz o resultado lógico original, após revalidar autenticação/autorização e escopo/alvo no estado atual. Digest divergente retorna conflito `IDEMPOTENCY_KEY_REUSED`. Resultado incerto de integração não pode ser repetido cegamente.
- Outbox PostgreSQL oferece entrega pelo menos uma vez. Claims são curtos, com `FOR UPDATE SKIP LOCKED`, lease e token de claim. Publicação ocorre fora da transação; sucesso é confirmado apenas com token/lease atuais. Retry usa backoff e limite configuráveis. Resultado ambíguo requer reconciliação, não retry automático. Reprocessamento interno conserva eventId/operationId.
- Consumidores locais deduplicam por `(consumer_name,event_id)` e confirmam receipt junto ao efeito na mesma transação. Destinos externos ainda precisam de idempotência própria ou reconciliação; não há garantia exactly-once entre sistemas.
- Auditoria segue V5, owner e grants append-only existentes; V6 concede apenas grants às tabelas novas e não altera grants da auditoria.
- Não fixar prazo de retenção nem excluir registros automaticamente até decisão produto/jurídico/operação. Sem essa política, manter idempotency/tombstones e histórico outbox terminal.

## Consequências

- Uma transação local garante resultado recuperável mesmo se a resposta HTTP se perder depois do commit.
- Uma chave ocupada por operação concluída tem resultado estável; operações externas são distintas e podem exigir reconciliação após timeout.
- Registros sem expurgo aumentam armazenamento; isso é preferível a reabrir keys/duplicar efeitos antes da política aprovada.
- Retry at-least-once exige consumer idempotente. Um claim antigo não confirma evento reassumido por token mais novo.
- Integrações reais permanecem fora do escopo; o consumidor persistente de teste valida infraestrutura local, não entrega a fornecedores.
