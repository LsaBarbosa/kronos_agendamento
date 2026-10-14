# Prompt — revisão independente da entrega

Informe o ID real e a referência de comparação. Exemplos: alterações locais não commitadas; uma faixa de commits identificada; ou branch da tarefa contra a branch base existente. Não presuma que a base se chama `main`.

```text
Revise a entrega de <AGD-xxx>.
Escopo do diff: <alterações locais ou referências Git reais>.

Leia AGENTS.md, a história e os documentos de especificação relacionados.
Compare requisitos, invariantes, implementação, contratos e testes.
Nesta etapa, produza achados e evidências; não edite os arquivos.

Use a skill de revisão do projeto se disponível. Se houver suporte e o diff
justificar, delegue duas revisões independentes: uma de aderência aos requisitos
e outra de correção/concorrência/autorização. O coordenador consolida achados
duplicados e valida as conclusões sobre o diff integrado.

Procure problemas concretos: regra implementada incorretamente, vazamento
entre tenants, transição inválida, corrida, retry que duplica efeitos,
migração insegura, divergência de API e falta de teste para comportamento crítico.
Avalie apenas riscos pertinentes à alteração. Execute validações relevantes
quando o ambiente permitir e identifique testes que não foram executados.

Para cada achado, informe severidade, arquivo/local, condição que o provoca,
consequência, requisito violado e correção sugerida. Diferencie defeito
confirmado, hipótese que precisa de teste e simples oportunidade de melhoria.

Informe critérios de aceite comprovados, pendências e evidência faltante.
Se não encontrar problemas, diga que não encontrou achados no escopo e
registre os limites da verificação. Não transforme ausência de achados em
garantia de ausência de defeitos.
```

Após analisar os achados, uma tarefa de correção pode autorizar a implementação e a atualização dos registros. Preservar esse limite permite uma revisão independente sem mutações concorrentes no mesmo diff.
