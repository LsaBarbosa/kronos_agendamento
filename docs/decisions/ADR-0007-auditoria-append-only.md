# ADR-0007 — Trilha de auditoria append-only no PostgreSQL

**Data:** 15/10/2026  
**Status:** adotada para o incremento AGD-004; não encerra os critérios futuros de auditoria.  
**Referências:** documentos 01 seção 14, 02 seções 2/15, 04 seções 7/14, 05 seções 15/21/22, AGD-004 e ADR-0005/0006.

## Contexto

As mutações Tenant existentes precisam registrar sucesso na mesma transação e preservar dados mínimos sem oferecer ao login comum meios de alterar ou excluir a trilha. A retenção e a cobertura de operações futuras ainda dependem de produto/operação/jurídico.

## Decisão

- Persistir eventos de domínio no PostgreSQL como JSONB restrito por allowlist, com IDs globais, tenant, ator/tipo/referência controlada, ação/alvo, resultado, `occurred_at`/`recorded_at` do banco, correlation/trace IDs e detalhes mínimos.
- A auditoria de sucesso participa da transação da mutação. A falha de inserção aborta a operação; rollback não deixa evento de sucesso.
- Não usar FKs de auditoria para tenant/ator/alvo, evitando cascata de exclusão da trilha. Correções geram eventos novos; não atualizar linhas existentes pela aplicação comum.
- Separar `agendamento_app` (login runtime), `agendamento_runtime` (NOLOGIN e privilégios de operação) e `agendamento_audit_owner` (NOLOGIN, proprietário da tabela). A identidade Flyway administra roles e schema; ela não é credencial de execução do backend. V5 falha se o login runtime tiver superuser/atributos/memberships não aprovados.
- O runtime tem `SELECT` e `INSERT` sobre a tabela; não tem `UPDATE`, `DELETE`, `TRUNCATE`, ownership ou associação ao owner. A query interna deriva tenant do principal autenticado e filtra por tenant; não há endpoint/UI de auditoria nesta história.
- Ações de sistema/provisionamento usam `actor_type=SYSTEM`, `actor_id=NULL` e referências enumeradas; provisionamento demo escreve entidades e eventos em transação única e falha em conflito, sem atualizar estado privilegiado silenciosamente.
- Eventos de mutação aceitos nesta fundação são somente sucessos `COMMITTED`. Falhas/negações/login/logout não são gravados como sucesso; a cobertura do canal de segurança atual é parcial e permanece pendente. Tracing distribuído, `span_id` e `operation_id` também não são introduzidos aqui.
- Administradores PostgreSQL, migrator, backup/restore e processos privilegiados podem alterar a tabela e pertencem a outra fronteira de confiança.
- Não definir prazo de retenção nem executar exclusões até aprovação explícita de política, base legal, categorias, legal hold e descarte.

## Consequências e trade-offs

- A transação única impede que a auditoria de sucesso sobreviva sem a mutação, mas indisponibilidade da trilha também bloqueia mutações críticas.
- Restringir detalhes no código e no schema diminui risco de PII/segredos, ao custo de exigir mudança deliberada para cada novo campo/evento.
- O papel de migration precisa de capacidade administrativa para criar roles, conceder membership e transferir ownership; ele deve ser separado e protegido como credencial privilegiada.
- `SELECT` runtime é de tabela e a fronteira multi-tenant é reforçada pelo caso de uso/query que deriva o tenant do principal. Não existe isolamento contra comprometimento total do backend.
- Retenção, eventos de autenticação/negação e cobertura das operações de negócio futuras permanecem fora desta decisão.
