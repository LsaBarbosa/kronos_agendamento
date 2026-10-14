# Continuar a base entregue

Ler o `AGENTS.md` aplicável, `docs/development/STATUS.md`, `VALIDATION.md`, `TRACEABILITY.md` e o plano de AGD-001. Inspecionar o código existente e o estado do Git.

A documentação já está em `docs/spec/`, as skills em `.agents/skills/` e os perfis opcionais em `.codex/agents/`. Validar sua descoberta no cliente, sem recriar ou substituir o que já estiver correto. Manter a configuração global do usuário.

Usar as skills pertinentes e até dois subagentes quando houver frentes independentes úteis, com ownership por arquivo. Executar a verificação completa do backend em ambiente com Docker ou PostgreSQL 16 descartável e registrar os resultados reais. Corrigir eventuais falhas da base sem reduzir testes.

Continuar as pendências de AGD-001, inclusive frontend, pipeline e evidências de execução. Ler os critérios completos antes de marcar conclusão. Registrar as escolhas ainda abertas em ADRs; decisões de produto permanecem pendentes quando não houver autorização.

Preservar Java 21, a linha Spring Boot definida e o monólito modular. Não implementar todas as histórias numa única execução. Ao concluir o incremento autorizado, atualizar os registros e apresentar critérios atendidos, testes, pendências, pontos positivos, trade-offs e próxima história recomendada.
