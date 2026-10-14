# Prompt inicial — preparar o projeto e iniciar AGD-001

Cole o texto abaixo no Codex aberto na raiz do projeto. Se estiver usando este arquivo diretamente, peça ao Codex para lê-lo e executar as instruções de `Tarefa` em diante.

## Uso no pacote completo

Este prompt é preservado para bootstrap geral. No ZIP com backend, comece por `00-continuar-base.md`: documentos, skills e perfis já existem e devem ser inspecionados, não recriados.

## Tarefa

Você desenvolverá o App Agendamento com base na documentação deste repositório. O projeto local informado é `/home/kronos/Documentos/IMPORTANTE/kronos/agendamento/`. Se estiver em um worktree, use a raiz do checkout atual. Leia primeiro o `AGENTS.md` aplicável, sem presumir que o histórico do ChatGPT esteja disponível.

Prepare o repositório para desenvolvimento orientado à especificação e implemente o escopo local da história AGD-001. Ao concluir esta tarefa, apresente evidências e o próximo passo; não inicie automaticamente todas as histórias do MVP.

## 1. Reconhecer e organizar

Inspecione a árvore, Git, mudanças locais, instruções já existentes e ferramentas disponíveis. Preserve código e trabalho em andamento. Não inicialize um repositório dentro de outro nem mude branch com alterações sem avaliar o estado.

Localize os sete Markdown `app-agendamento-*`. O destino canônico é `docs/spec/`, mantendo nomes e links. Se já estiverem lá, não duplique. Se houver cópias na raiz ou em `app-agendamento-spec/`, compare conteúdos antes de consolidar; diante de versões divergentes, reporte a diferença e mantenha a parte afetada intacta. Valide os links após a organização. Não reescreva requisitos durante uma movimentação.

Leia o índice e os seis documentos. Dê atenção ao documento 05, seção 22: parte da resiliência é fundação do MVP; o restante tem adoção posterior. O documento 06 contém a prioridade e o aceite por história.

Registre as pendências concretas já previstas: fornecedores de identidade, WhatsApp e cobrança; UX; regra excepcional de dez minutos; validações operacionais/jurídicas. Prossiga com a fundação que não depende dessas decisões. Não invente fornecedor, contrato comercial ou interpretação da regra de dez minutos.

## 2. Preparar instruções, skills e agentes

Confira se o `AGENTS.md` da raiz reflete o pacote. Se já houver outro, integre as orientações pertinentes preservando restrições existentes; não substitua cegamente. Mantenha-o conciso, com links para os detalhes.

Verifique a versão/capacidades reais do Codex e consulte suas instruções oficiais antes de configurar recursos dependentes de versão. Use `$skill-creator` se disponível para completar ou criar somente as skills locais que ainda estiverem ausentes; se não estiver disponível, siga o formato oficial suportado pelo cliente:

| Skill proposta | Quando usar | Conteúdo esperado |
|---|---|---|
| `agendamento-implementar-historia` | Implementar uma história AGD ou um incremento autorizado | Carregar referências, critérios, dependências, planejar, implementar e manter rastreabilidade |
| `agendamento-verificar-invariantes` | Alterar persistência, reservas, autorização, tempo, retry ou efeitos externos | Selecionar riscos relevantes e executar testes reais de invariantes |
| `agendamento-revisar-entrega` | Revisar um diff de entrega | Comparar requisitos, código, contratos e evidências; classificar achados sem alterar requisitos |

Cada skill fica em `.agents/skills/<nome>/SKILL.md`, com `name`, `description` e instruções focadas, se esse caminho for suportado pelo cliente instalado. Referencie `AGENTS.md` e `docs/spec/`, sem duplicar a especificação nem substituir autorização. Inclua scripts somente se houver uma operação repetível real. Valide descoberta e uso das skills; não afirme que estão disponíveis sem verificar. Mantenha as skills versionáveis no Git do projeto.

Utilize subagentes quando houver frentes independentes úteis, começando com até dois além do coordenador, dentro do limite do ambiente. Para esta preparação, uma frente pode mapear requisitos/pendências e outra avaliar limites arquiteturais e critérios de teste, ambas sem editar os mesmos arquivos. O coordenador realiza a integração.

Se o cliente suportar agentes personalizados por projeto, crie perfis pequenos de revisão em `.codex/agents/`, usando o schema oficial atual: `requirements_reviewer` para aderência à especificação e `correctness_reviewer` para correção, concorrência, autorização e falhas. Reutilize o modelo da sessão quando apropriado; não fixe modelos inexistentes. Os perfis pedem leitura e relatório, mas não são um mecanismo de isolamento de permissões. Confirme que o cliente os reconhece. Se não houver suporte, use delegação nativa com instruções explícitas ou execute sequencialmente; não bloqueie o projeto por isso.

Não adicione configuração global nem opções de permissões. Se precisar editar uma configuração local já existente, preserve os valores não relacionados e explique a mudança. MCP e plugins devem ser utilizados somente quando resolverem uma necessidade concreta; não são pré-requisito para a primeira compilação.

## 3. Criar memória de trabalho versionada

Prepare ou atualize:

- `docs/development/STATUS.md`: estado observado do projeto, incremento corrente, tarefas concluídas com evidência, bloqueios e próximo passo.
- `docs/development/TRACEABILITY.md`: história, referência da especificação, implementação, teste/evidência e pendências.
- `docs/development/OPEN-QUESTIONS.md`: dúvidas de decisão, impacto, recomendação e quando impedem avanço.
- `docs/decisions/`: ADRs para decisões relevantes, com status, contexto, decisão, alternativas, benefícios e trade-offs.
- `docs/plans/AGD-001-fundacao.md`: plano vivo conforme `docs/development/PLANS.md`.

Preencha esses arquivos a partir do que foi observado. Não declare histórias concluídas porque os documentos existem. O plano contém o detalhe; `STATUS.md` aponta para ele. Registre versões e comandos reais no README de desenvolvimento.

## 4. Executar a primeira entrega

Se já houver implementação, identifique as lacunas de AGD-001 e complete-as sem recriar o projeto. Se o repositório contiver apenas documentação, implemente a fundação descrita em AGD-001 e no documento 04.

Preserve Java 21 e o monólito modular. O documento 04 propõe Spring Boot 3.x, PostgreSQL 16+ e React/TypeScript; verifique suporte e compatibilidade das versões exatas antes de fixá-las. Não migre versões principais silenciosamente. Escolha dentro da arquitetura as alternativas ainda abertas, registre a justificativa e o trade-off, e isole o que precisar de decisão posterior.

Entregue estrutura backend/frontend, build reproduzível, ambiente local, migrações iniciais estritamente necessárias, PostgreSQL para testes de integração, verificação de dependências modulares e pipeline adequado ao repositório. Não crie entidades vazias ou abstrações para todo o backlog. Não crie automaticamente repositório remoto ou recursos pagos.

Escreva comandos reais de bootstrap, execução e verificação no README. Execute os testes, builds e verificações disponíveis, incluindo o que comprova os critérios de AGD-001. Se Docker, rede ou alguma ferramenta bloquear um teste, informe o comando e a falha, mantenha o critério pendente e conclua o restante possível. Não substitua a prova exigida por um mock e declare sucesso.

Solicite revisão por subagente, quando disponível e útil, sobre o diff concluído. Corrija achados materiais, valide a integração e atualize o plano e a rastreabilidade. Uma revisão simulada no texto não conta como revisão independente.

## 5. Formato de conclusão

Informe:

1. O que foi organizado e implementado.
2. História e critérios atendidos; critérios ainda pendentes.
3. Arquivos principais e decisões técnicas.
4. Skills/agentes realmente criados e reconhecidos, ou limitações encontradas.
5. Comandos executados e resultados observados.
6. Pontos positivos e trade-offs relevantes.
7. Estado real de AGD-001 e próxima história recomendada por dependência.

Trabalhe autonomamente nas ações locais autorizadas até concluir esse escopo. Faça pergunta somente quando uma decisão material bloquear uma parte, e continue as frentes independentes. Não publique em produção como parte desta tarefa.
