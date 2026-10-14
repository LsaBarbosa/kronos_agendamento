# Prompt — implementar uma história

Substitua `<AGD-xxx>` pelo ID real do documento 06. Especifique uma fatia quando a história for grande; não deixe um placeholder ao executar.

```text
Implemente a história <AGD-xxx> do App Agendamento no repositório atual.

Leia AGENTS.md, docs/development/STATUS.md, as ADRs aplicáveis e a história
completa em docs/spec/app-agendamento-06-backlog-criterios-aceite.md.
Leia as seções relacionadas dos demais documentos antes de editar.

Use as skills pertinentes realmente disponíveis, incluindo as skills locais
do projeto se já foram criadas. Confira o que já existe e as dependências.
Se a história estiver parcialmente implementada, complete as lacunas.

Antes da implementação, indique brevemente objetivo, referências, critérios,
arquivos afetados e abordagem de verificação. Para trabalho com várias etapas,
crie/atualize docs/plans/<AGD-xxx>-descricao.md conforme PLANS.md e prossiga
na execução, sem aguardar aprovação de escolhas técnicas rotineiras.

Utilize até dois subagentes quando houver trabalho independente que justifique:
análise de requisitos e revisão de correção/segurança. Delimite instruções e
arquivos; a integração é responsabilidade do coordenador. Se não houver
suporte, execute sequencialmente e informe essa limitação.

Implemente o comportamento do escopo com testes significativos. Preserve
invariantes, tenant, transações, idempotência e concorrência quando aplicáveis.
Atualize API, migrações, documentação e observabilidade se forem afetadas.
Não amplie para P1/P2 nem altere regras de negócio para facilitar o código.

Execute os comandos reais de validação. Corrija falhas introduzidas e revise
o diff final. Se algum teste não puder executar, registre exatamente o que
faltou e o impacto na conclusão. Não declare staging/CI validado sem execução.

Atualize STATUS.md, TRACEABILITY.md, o plano e eventuais pendências.
Conclua com: resultado, critérios atendidos/pendentes, arquivos relevantes,
testes e resultados, benefícios, trade-offs e próximo passo recomendado.
Continue autonomamente até concluir este escopo local; não publique em
produção nem inicie a história seguinte automaticamente.
```

Exemplo após a fundação: peça `AGD-002`, conferindo suas dependências. Para AGD-015, confirme antes a existência do catálogo, disponibilidade, identidade/idempotência e suporte transacional necessários; não implemente uma reserva isolada do restante do fluxo.
