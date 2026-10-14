# Guia do Codex para o App Agendamento

Preparado para Lucas em 14/10/2026. Raiz local informada: `/home/kronos/Documentos/IMPORTANTE/kronos/agendamento/`.

Este guia organiza o desenvolvimento a partir dos sete Markdown de especificação. O pacote inclui instruções, prompts, skills locais, perfis opcionais de agentes e as fundações do backend e frontend. Consulte STATUS.md e VALIDATION.md para distinguir o que foi implementado do que foi verificado; o plano de cada história registra as mudanças e limitações da execução.

## 1. Onde colocar cada arquivo

Use a pasta `agendamento/` como raiz do projeto e, preferencialmente, do repositório Git. Se ela já estiver dentro de outro repositório, inspecione esse contexto antes de criar um Git aninhado.

Todos os caminhos da tabela abaixo são relativos a essa raiz.

| Caminho | Conteúdo | Função |
|---|---|---|
| `AGENTS.md` | instruções permanentes do projeto | orientar o Codex em cada sessão |
| `docs/spec/` | os sete arquivos `app-agendamento-*.md` | especificação canônica |
| `docs/prompts/` | prompts deste pacote | iniciar, implementar, revisar e retomar |
| `docs/development/PLANS.md` | formato dos planos | tornar trabalho longo retomável |
| `docs/development/STATUS.md` | estado observado, evidências e próximo passo | continuidade entre sessões |
| `docs/development/TRACEABILITY.md` | requisito → código → teste/evidência | demonstrar atendimento do backlog |
| `docs/development/OPEN-QUESTIONS.md` | decisões pendentes com impacto | conter dúvidas sem reabrir todo o escopo |
| `docs/plans/` | plano de cada história complexa | execução, descobertas e checkpoints |
| `docs/decisions/` | ADRs, registros de decisão arquitetural | explicar escolhas e mudanças relevantes |
| `.agents/skills/<nome>/SKILL.md` | skills locais criadas no bootstrap | procedimentos reutilizáveis |
| `.codex/agents/<nome>.toml` | perfis opcionais, se suportados pelo cliente | especializar subagentes |
| `backend/` | Java/Spring, migrations e testes | backend modular |
| `frontend/` | fundação React/TypeScript com Vite; fluxos de produto pendentes | futura web pública, administração e cliente |
| `infra/` | configuração local/infra quando necessária | execução reproduzível |
| `README.md` | como preparar, executar e testar | entrada para qualquer desenvolvedor |

Coloque juntos em `docs/spec/`:

1. `app-agendamento-README.md`
2. `app-agendamento-01-especificacao-mvp.md`
3. `app-agendamento-02-modelo-dominio-estados.md`
4. `app-agendamento-03-apis-eventos-integracoes.md`
5. `app-agendamento-04-arquitetura-implementacao.md`
6. `app-agendamento-05-resiliencia-controles-avancados.md`
7. `app-agendamento-06-backlog-criterios-aceite.md`

Os links existentes funcionam porque os sete arquivos continuam no mesmo diretório. Preserve os nomes. O ZIP original pode ficar guardado fora da árvore de desenvolvimento; o Codex deve trabalhar com os Markdown extraídos e versionados. Evite cópias editáveis divergentes da mesma especificação.

## 2. Instalação e primeiro uso

Se o ZIP deste kit estiver em `~/Downloads/agendamento-projeto-base.zip`, extraia sem sobrescrever arquivos existentes:

```bash
unzip -n "$HOME/Downloads/agendamento-projeto-base.zip" -d "/home/kronos/Documentos/IMPORTANTE/kronos/agendamento"
```

O ZIP contém `AGENTS.md`, backend, infraestrutura local, skills e documentação nas pastas correspondentes. Se já existir um `AGENTS.md`, preserve-o e peça ao Codex para comparar e integrar a versão do kit, extraída à parte; `unzip -n` não fará essa integração.

Depois, abra a pasta inteira na IDE/extensão do Codex ou inicie o CLI nela:

```bash
cd /home/kronos/Documentos/IMPORTANTE/kronos/agendamento
codex
```

No CLI, a pasta deve ser o diretório de trabalho. Na IDE, abra a raiz como workspace. Para usar Codex em ambiente remoto/cloud, disponibilize o repositório nesse ambiente; o caminho `/home/kronos/...` só identifica o arquivo na máquina em que ele existe.

Neste pacote, os sete arquivos já estão em `docs/spec/`. Se sua pasta local também tiver cópias na raiz, peça ao Codex para comparar o conteúdo e manter uma única fonte canônica, sem descartar versões divergentes.

Primeiro comando para colar na conversa do Codex:

```text
Leia o AGENTS.md aplicável e execute a tarefa descrita em
docs/prompts/00-continuar-base.md.

Use a documentação como fonte de requisitos e utilize skills e subagentes
quando disponíveis e úteis, conforme as responsabilidades definidas.
Valide a base existente e continue as pendências de AGD-001 com evidências.
```

Uma nova sessão após a preparação permite conferir as instruções persistentes e os recursos descobertos pelo cliente. Verifique as skills pelo seletor disponível; não basta existir um arquivo para comprovar que a versão instalada o reconheceu.

## 3. O papel de cada mecanismo

`AGENTS.md` contém regras gerais e referências, como arquitetura, fontes de requisitos e critério de conclusão. Codex descobre instruções globais e de projeto; arquivos mais específicos podem complementar ou sobrepor instruções anteriores. Por isso, confira instruções existentes antes de adicionar outras. Coloque o arquivo principal na raiz, e adicione `backend/AGENTS.md` ou `frontend/AGENTS.md` somente se surgirem regras específicas úteis. [Documentação oficial de AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

Skills são procedimentos acionados por tarefa. O Codex carrega inicialmente metadados e lê o conteúdo completo quando utiliza a skill. Na documentação consultada, skills de repositório ficam em `.agents/skills/`; o manifesto `SKILL.md` exige `name` e `description`. No CLI/extensão é possível invocá-las por `$nome`. [Documentação oficial de skills](https://learn.chatgpt.com/docs/build-skills).

Os outros arquivos Markdown não são automaticamente instruções globais. `STATUS.md`, `PLANS.md`, planos, ADRs e especificações são lidos porque o `AGENTS.md`, a skill ou o prompt manda consultá-los. Essa distinção evita depender de uma suposta memória automática de tudo que está no disco.

## 4. Skills recomendadas para este produto

O pacote completo já inclui três workflows locais:

| Skill | Entrada | Resultado |
|---|---|---|
| `agendamento-implementar-historia` | ID AGD e escopo da tarefa | implementação verificável com referências e status |
| `agendamento-verificar-invariantes` | alteração que atinge invariantes | seleção e execução de testes de riscos reais |
| `agendamento-revisar-entrega` | diff e critérios de aceite | achados fundamentados e pendências de verificação |

Cada skill deve conter o procedimento, não uma cópia da regra de negócio. Exemplo: ela manda ler a política de cancelamento no documento 01; não repete o número de horas dentro de vários arquivos.

Use o criador de skills que o cliente realmente disponibilizar. Teste cada uma com uma tarefa pequena. Acrescente novas skills quando aparecer repetição concreta, como investigar CI ou revisar migrações complexas. O benefício é consistência; o trade-off é a manutenção do workflow junto com o produto. Muitas skills parecidas aumentam ambiguidades de seleção.

## 5. Agentes e paralelismo

O coordenador mantém a visão da história, decide a integração e responde pelo resultado. Comece com até dois subagentes além dele, escolhidos pelo tipo de trabalho:

| Responsável | Escopo inicial | Edição |
|---|---|---|
| Coordenador | implementação, contratos, integração e estado da entrega | responsável pelos arquivos compartilhados |
| Revisor de requisitos | rastrear critérios e apontar lacunas/contradições | leitura e relatório |
| Revisor de correção | analisar invariantes, falhas, segurança e testes | leitura e relatório |

Delegue implementação paralela apenas depois de estabilizar contratos e delimitar arquivos. Uma migração, um contrato OpenAPI ou um manifesto de dependências precisa ter um responsável. O coordenador valida o resultado depois de integrar; testes isolados de cada agente não bastam.

Subagentes e worktrees são mecanismos distintos. Não suponha que abrir um subagente cria um checkout isolado. Worktrees permitem checkouts separados de um repositório Git e ajudam quando há frentes de escrita simultâneas. Mesmo separados, os resultados ainda precisam de merge e validação de integração. [Subagentes](https://learn.chatgpt.com/docs/agent-configuration/subagents) e [worktrees](https://learn.chatgpt.com/docs/environments/git-worktrees).

Na documentação atual, perfis locais personalizados podem ser definidos em `.codex/agents/*.toml`. Este pacote inclui `requirements_reviewer` e `correctness_reviewer`, sem fixar um modelo específico nem pressupor acesso a todos os modelos. Confirme a descoberta na versão instalada; se o cliente não suportar esses perfis, use a delegação nativa com as mesmas responsabilidades. As instruções de leitura nos perfis não criam isolamento de permissões.

Ponto positivo: revisão independente e análise em paralelo. Trade-offs: mais consumo, coordenação e risco de conflitos se várias frentes editarem a mesma área. Não é necessário utilizar subagentes em cada pequena alteração.

## 6. Rotina de desenvolvimento

1. Selecione uma história ou incremento pequeno do backlog. Comece por AGD-001 e continue conforme as dependências de AGD-002 a AGD-005; depois valide uma reserva vertical.
2. Use [o prompt de implementação](../prompts/02-implementar-historia.md), informando o ID real.
3. Peça implementação, testes e evidências na mesma tarefa. Registre pendências em vez de aceitar conclusões genéricas.
4. Faça revisão com [o prompt de revisão](../prompts/03-revisar-entrega.md). Para mudanças relevantes, use um contexto de revisão independente e forneça o diff/base corretos.
5. Retome em outra sessão com [o prompt de retomada](../prompts/04-retomar-trabalho.md).

Os planos devem manter o passo exato de continuidade e os resultados observados. A OpenAI apresenta planos persistentes como uma forma de sustentar trabalhos longos; aqui, a convenção foi adaptada em [PLANS.md](PLANS.md). [Referência oficial](https://developers.openai.com/cookbook/articles/codex_exec_plans).

Versione código, especificações, `AGENTS.md`, skills, planos, ADRs, wrappers, lockfiles e configurações do projeto sem segredos. Um commit/PR por história ou fatia coesa facilita revisão e retorno. O Git guarda a evolução; `STATUS.md` explica o estado atual. Escolha o fluxo de commits/push de acordo com a autorização da tarefa e o repositório existente.

## 7. Controles específicos que não devem se perder

O pacote de produto inclui concorrência, idempotência, isolamento por tenant, tempo/fuso, outbox e resultados externos ambíguos. Esses pontos precisam de provas executáveis quando atingidos por uma mudança.

Há uma nuance de escopo no documento 05: o resumo menciona ledger externo entre evoluções, mas a seção 22 e AGD-051 exigem tombstones básicos e restauração isolada desde o MVP. A orientação do kit é preservar esse requisito básico e registrar qualquer dúvida remanescente de escopo, sem introduzir os protocolos avançados de quorum, Merkle ou compactação formal no lançamento.

As versões de framework do documento 04 são baseline de arquitetura. O bootstrap deve verificar versões exatas e suporte em fontes oficiais; mudança de major deve ser explicitada e documentada. O kit não atualiza silenciosamente a stack original.

O backlog exige homologação/staging para conclusão. Se houver apenas ambiente local, registre implementação local validada e a homologação pendente. Aplicação que compila, teste simulado de integração e pipeline apenas escrito são evidências diferentes de teste real, provedor validado e CI executado.

## 8. Recursos adicionais do Codex

| Recurso | Quando ajuda | Custo/limite a considerar |
|---|---|---|
| Terminal e ferramentas locais | compilar, testar, rodar banco e inspecionar diffs | exige ferramentas e permissões adequadas |
| Revisão de código | verificar regressões e lacunas de teste | não substitui execução nem homologação |
| Navegador/automação disponível | verificar o fluxo web real | depende do ambiente e da integração habilitada |
| MCP/documentação | consultar APIs e configuração atual | escolher fontes pertinentes e conexões necessárias |
| GitHub/CI conectado | consultar falhas e trabalhar sobre PRs | exige repositório, conexão e autorização corretos |
| Worktrees | separar implementações paralelas | consome disco e exige integração posterior |
| Planos persistentes | manter continuidade em tarefas longas | precisam refletir o código real |

Para documentação de Codex/OpenAI, o MCP oficial é opcional e somente de leitura. Se quiser configurá-lo no seu cliente:

```bash
codex mcp add openaiDeveloperDocs --url https://developers.openai.com/mcp
codex mcp list
```

Ele oferece documentação de OpenAI, não documentação geral de Java/Spring e não executa chamadas da API por você. Para o produto, use as referências oficiais das versões escolhidas e dos fornecedores. [Documentação do Docs MCP](https://developers.openai.com/learn/docs-mcp).

O princípio de trabalho é manter escopo pequeno, requisito rastreável, execução real e progresso persistido. A configuração aumenta a consistência, mas precisa de revisão, testes e decisões humanas nos pontos de produto ainda abertos.
