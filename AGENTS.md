# App Agendamento — instruções para desenvolvimento

Estas instruções orientam o trabalho neste repositório. Respeite as instruções da sessão e as permissões do ambiente. Responda em português do Brasil, com resultado, evidências, pontos positivos e trade-offs relevantes.

## Documentação e estado do trabalho

A especificação canônica está em `docs/spec/`. Preserve seus nomes e links relativos.

| Referência | Responsabilidade |
|---|---|
| `docs/spec/app-agendamento-README.md` | visão geral, convenções e mapa da especificação |
| `docs/spec/app-agendamento-01-especificacao-mvp.md` | comportamento e limites funcionais |
| `docs/spec/app-agendamento-02-modelo-dominio-estados.md` | entidades, estados e invariantes |
| `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` | contratos, eventos e integrações |
| `docs/spec/app-agendamento-04-arquitetura-implementacao.md` | arquitetura e organização técnica |
| `docs/spec/app-agendamento-05-resiliencia-controles-avancados.md` | fundamentos de resiliência e evolução; consultar a seção 22 para a adoção incremental |
| `docs/spec/app-agendamento-06-backlog-criterios-aceite.md` | IDs `AGD-xxx`, prioridades, aceite e gates |

Na primeira preparação, leia o pacote completo. Nas tarefas seguintes, leia o índice, a história alvo e somente as seções relacionadas, além de `docs/development/STATUS.md` e das decisões aplicáveis em `docs/decisions/`, quando existirem.

Não considere uma preferência, proposta ou pendência como decisão aprovada. Se houver conflito material, registre as referências e a dúvida em `docs/development/OPEN-QUESTIONS.md`, proponha uma resolução e prossiga nas partes independentes. Não escolha silenciosamente o requisito mais fácil. Alteração explícita de produto feita pelo usuário deve ser registrada nos documentos afetados; uma ADR proposta não substitui requisito vigente.

## Escopo e arquitetura

- Conduza o MVP por histórias P0 e suas dependências. P1/P2 exigem estar no escopo da tarefa.
- Preserve a arquitetura documentada: monólito modular, backend Java 21/Spring, frontend TypeScript/React e PostgreSQL autoritativo. Verifique versões exatas e compatibilidade no bootstrap. Mudança de versão principal da baseline deve ser explicitada e registrada.
- Respeite os limites dos módulos e a divisão `api`, `application`, `domain`, `infrastructure`; não introduza acesso indiscriminado a repositórios internos de outro módulo.
- Uma agenda por tenant; reservas concorrentes não podem sobrepor ocupação, inclusive buffer. Validação de disponibilidade é prévia; a garantia final deve existir no banco.
- Tenant autenticado é derivado no servidor; acesso e testes devem impedir cruzamento entre tenants.
- Mutação, idempotência, histórico/auditoria essencial e outbox compartilham a transação quando especificado. Chamadas externas ficam fora dela.
- Timeouts de provedores não comprovam ausência de efeito. Preserve a identidade lógica e reconcilie quando houver resultado ambíguo.
- Respeite snapshots, CAS/versionamento, instantes UTC e zona IANA conforme a especificação.
- O documento 05 não é uma lista integral de tarefas para o MVP. Seus fundamentos da seção 22 e os critérios P0 continuam obrigatórios, incluindo tombstones básicos e restore isolado em AGD-051. Merkle, cut proofs e quorum distribuído pertencem às evoluções indicadas.
- Mantenha desabilitada a regra excepcional de dez minutos enquanto sua semântica não for decidida.

## Ciclo de implementação

1. Inspecione o estado real do código, os arquivos de instrução aplicáveis, a branch e as mudanças locais. Preserve trabalho existente.
2. Identifique a história `AGD-xxx`, referências, critérios de aceite, dependências e limites da tarefa. Resolva escolhas técnicas reversíveis dentro da arquitetura e registre as relevantes.
3. Para trabalho com múltiplas etapas, crie/atualize um plano em `docs/plans/` seguindo `docs/development/PLANS.md`. Para ajuste pequeno, basta um plano breve na tarefa.
4. Implemente um incremento verificável e execute as validações relacionadas aos riscos reais. Não pare em scaffolding quando a tarefa exigir comportamento funcional.
5. Revise o diff, corrija defeitos, atualize contrato/documentação quando necessário e registre as evidências em `docs/development/TRACEABILITY.md` e o estado em `docs/development/STATUS.md`.
6. Encerre com o que mudou, comandos executados e resultados, pendências, riscos/trade-offs e próxima história recomendada.

Não amplie silenciosamente o escopo nem reabra a elicitação inteira. Dúvidas de negócio que alteram cobrança, autorização, privacidade ou comportamento observável precisam de decisão explícita apenas para a parte afetada.

## Skills e subagentes

Verifique as skills realmente disponíveis. Use as pertinentes; não invente nomes instalados. Quando criadas pelo bootstrap, use `agendamento-implementar-historia`, `agendamento-verificar-invariantes` e `agendamento-revisar-entrega` nos respectivos fluxos. Elas devem referenciar a documentação, não copiar regras que possam divergir.

Fica solicitado o uso de subagentes em tarefas com frentes independentes úteis. Comece com até dois subagentes além do coordenador, respeitando o limite real do ambiente. Priorize análise de requisitos e revisão de correção/segurança em leitura. Para tarefa pequena ou sem suporte, execute sequencialmente e informe a limitação.

Cada delegação informa objetivo, documentos, história, arquivos permitidos, restrições e resultado esperado. Defina um único responsável por cada arquivo. Não suponha isolamento entre subagentes; quando houver escritores paralelos, separe os arquivos ou use worktrees com branches próprias e integração pelo coordenador. O coordenador consolida decisões, contratos, migrações, status e valida o resultado integrado. Não declare revisão independente se ela não ocorreu.

## Verificação e conclusão

- Descubra os comandos no repositório e mantenha os comandos reproduzíveis no README. Use wrappers e lockfiles existentes.
- Testes de integração de persistência usam PostgreSQL real quando exigido pelo backlog; banco em memória não comprova constraints específicas do PostgreSQL.
- Cubra, quando atingidos: isolamento por tenant, transições inválidas, concorrência, retry/idempotência, outbox, limites temporais e webhooks duplicados/fora de ordem.
- Mantenha migrações aplicadas imutáveis; corrija por nova migração quando necessário.
- Não enfraqueça critérios ou testes para obter resultado verde. Diferencie erro preexistente, regressão e limitação de ambiente.
- Validação não executada permanece pendente com motivo. Compilar não comprova todos os critérios de aceite. Validação local não equivale a staging ou produção.
- `DONE` exige a Definition of Done e os gates aplicáveis do documento 06. Use estado intermediário se faltar homologação, credencial, provedor ou infraestrutura.

## Integrações e autorização

Use documentação oficial atual quando configuração ou comportamento da ferramenta/biblioteca depender de versão. Para dúvidas do Codex/OpenAI, use o Docs MCP se disponível ou as fontes oficiais. Registre escolhas de infraestrutura/fornecedor pendentes; adapters simulados são apenas para desenvolvimento e não comprovam integração real.

Este fluxo autoriza preparação e desenvolvimento local. Publicação, contratação de serviços e ações nos ambientes reais seguem o escopo e a autorização da tarefa. Não altere configurações globais, permissões ou credenciais para contornar um bloqueio. Nunca grave segredos ou dados reais de clientes no repositório, nos testes ou nos relatórios.
