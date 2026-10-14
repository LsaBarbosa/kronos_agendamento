---
name: agendamento-revisar-entrega
description: Revisar em leitura uma entrega, diff ou história AGD do App Agendamento contra requisitos, invariantes e evidências. Usar para revisão de implementação; não implementar correções sem autorização da tarefa.
---

# Revisar entrega

1. Identificar a raiz, o escopo exato do diff e a história. Se não houver Git, revisar os arquivos indicados e declarar essa delimitação. Ler `AGENTS.md`, a história e as seções relacionadas.
2. Conferir comportamento, contratos, migrações e testes. Não assumir que `STATUS.md` comprova sucesso. Separar código implementado, resultado executado e requisito pendente.
3. Priorizar defeitos reproduzíveis: acesso entre tenants, corrida, efeitos duplicados, estado inválido, quebra de API, migração destrutiva, vazamento de dados e ausência de validação relevante.
4. Usar subagentes em leitura se houver frentes independentes úteis; consolidar sem duplicar achados. Não afirmar revisão independente se ela não ocorreu.
5. Executar verificações pertinentes quando o ambiente permitir. Não alterar arquivos durante a revisão.
6. Informar cada achado com severidade, arquivo/local, condição, impacto, requisito e sugestão. Identificar hipóteses que precisam de teste.
7. Informar pontos positivos, trade-offs e pendências de evidência. Sem achados, declarar escopo e limitações; não garantir ausência de defeitos.

Avaliar a base separadamente das funcionalidades futuras. Uma história explicitamente pendente não é automaticamente um defeito no scaffold; uma promessa de conclusão sem evidência é uma inconsistência.
