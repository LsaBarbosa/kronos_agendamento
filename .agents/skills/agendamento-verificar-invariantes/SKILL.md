---
name: agendamento-verificar-invariantes
description: Selecionar e executar verificações das invariantes do App Agendamento ao alterar reservas, tenant, persistência, tempo, autenticação, idempotência, outbox ou provedores. Não impor testes funcionais a mudanças de texto sem efeito funcional.
---

# Verificar invariantes

1. Ler `AGENTS.md`, o diff real, a história AGD e as invariantes relacionadas no documento 02. Consultar os contratos do documento 03 e o aceite do documento 06.
2. Selecionar os riscos atingidos: isolamento entre tenants; sobreposição e adjacência; transições e CAS; replay/digest; outbox transacional; timezone/DST; autorização; webhooks duplicados/fora de ordem; ambiguidade de provedor; privacidade/restore.
3. Vincular cada risco a um teste existente ou cenário novo com resultado observável. Cobrir falhas e concorrência quando necessário; não testar apenas chamadas internas ou setters.
4. Usar PostgreSQL real para SQL, constraints e migrações específicos dele. Não substituir por H2 ou mock e declarar a mesma garantia. Em `verify`, usar Testcontainers ou `TEST_DATABASE_*` para um PostgreSQL 16 descartável autorizado.
5. Executar build e testes pertinentes. Uma edição de documentação não exige teste de comportamento; uma alteração transacional pode exigir integração e corrida concorrente.
6. Diferenciar regressão, falha preexistente e bloqueio de ambiente. Não remover teste, usar skip silencioso nem relaxar requisito para obter verde.
7. Relatar comando, ambiente, resultado e cobertura pendente. Atualizar `docs/development/TRACEABILITY.md` sem afirmar CI, Docker ou staging quando não executados.

Consultar `docs/development/VALIDATION.md` para conhecer a verificação entregue no pacote e seus limites. Ela não substitui nova execução após alterações no código.
