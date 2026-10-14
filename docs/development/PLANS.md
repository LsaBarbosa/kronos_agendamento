# Planos de execução do App Agendamento

Use planos versionados para histórias com várias etapas, mudanças transacionais, integrações ou refatorações relevantes. Este arquivo é uma convenção do projeto: `AGENTS.md` determina sua leitura. Ele não é descoberto automaticamente apenas pelo nome.

Um plano deve permitir que outra sessão retome o trabalho a partir do repositório. Mantenha-o proporcional ao risco e ao tamanho da mudança. Não copie a especificação inteira.

Crie `docs/plans/AGD-xxx-descricao.md` com os campos abaixo:

## Identificação e objetivo

- História(s), incremento autorizado e resultado observável.
- Referências por arquivo e seção da especificação.
- Escopo incluído e exclusões relevantes.
- Branch/commit de referência, quando existirem.

## Estado e dependências

- O que já existe, confirmado por inspeção.
- Pré-requisitos e decisões pendentes.
- Contrato de API/evento e ownership dos arquivos envolvidos.

## Estratégia

- Sequência de mudanças, limites transacionais e migrações.
- Abordagem de teste e possíveis falhas relevantes.
- Subtarefas delegadas, responsável e arquivos exclusivos, se houver.
- Decisões técnicas, motivo, benefício e trade-off.

## Progresso

Use checkboxes com data e evidência curta. Separe implementação, verificação local, revisão e homologação. Não marque execução futura como concluída.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Referência exata do documento 06 | Comando real, quando disponível | Comportamento verificável | Pendente, passou, falhou ou não executado + motivo |

## Descobertas e decisões

Registre surpresas que alteraram o plano, evidência e solução. Mudanças arquiteturais relevantes referenciam uma ADR em `docs/decisions/`. Dúvida de produto vai para `docs/development/OPEN-QUESTIONS.md`; plano não redefine requisito.

## Encerramento ou retomada

- Resultado entregue e critérios ainda pendentes.
- Arquivos principais, testes executados e limitações.
- Passo exato para continuar.
- Atualizações correspondentes em `STATUS.md` e `TRACEABILITY.md`.

O plano guarda o detalhe desta execução. `STATUS.md` é o índice do estado atual, com links para os planos; `TRACEABILITY.md` associa requisitos a implementação e evidência. Evite repetir longas descrições nos três locais.
