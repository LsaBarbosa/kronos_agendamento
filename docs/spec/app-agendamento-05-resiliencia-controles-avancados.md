# 05 — Resiliência, evidências e controles avançados

## 1. Finalidade e nível de maturidade

Este documento consolida os controles de alta garantia discutidos para operações ambíguas, recuperação, capabilities, fences, evidências, adjudicação, quarentena, restore e reconciliação. Eles formam um modelo coerente, mas **não devem ser todos implementados no MVP**.

### Classificação

| Nível | Aplicação |
|---|---|
| Fundação MVP | identidade de operação, idempotência vinculada ao digest, CAS de versão, outbox, auditoria, webhook autenticado, reconciliação e resultado externo ambíguo explícito |
| Pós-MVP | capabilities de uso único para ações privilegiadas, grafo de dependência, quarentena granular, ledger externo de exclusões, manifests Merkle |
| Alta garantia | autoridade serializadora, fences geracionais, adjudicação formal, plano out-of-band, emergência orçada, forks de ledger e provas de compactação |

O objetivo é evitar dois erros simétricos:

- declarar “falhou” e repetir uma operação que talvez tenha produzido efeito;
- declarar “sucesso” sem prova suficiente e liberar consequências inseguras.

## 2. Princípios normativos

1. **Efeito ambíguo não é ausência de efeito.**
2. **Ação segura exige segurança em todos os estados ainda possíveis.**
3. **Na impossibilidade de enumerar exatamente, usar sobreaproximação conservadora.**
4. **Fatos históricos nunca são reescritos silenciosamente.**
5. **Nova adjudicação não valida retroativamente decisões ou ações antigas.**
6. **Prova é válida somente dentro de sua linhagem, versão, escopo e dependências.**
7. **Autorização é vinculada ao estado analisado e revalidada no ponto de efeito.**
8. **Evidência e mudança pendentes criam barreiras antes de competir com o consumo.**
9. **Independência deve ser provada; ausência de linhagem implica quarentena.**
10. **Ações emergenciais são mínimas, temporárias, cumulativamente limitadas e fail-closed.**
11. **Privacidade e exclusão não cedem apenas porque a investigação perdeu conteúdo.**
12. **Liberação é granular: preserva-se somente o subconjunto comprovadamente independente.**

## 3. Autoridades e registros

### Autoridade principal

Componente que serializa, por escopo:

- admissão de evidência;
- mudança do grafo de dependências;
- emissão/consumo de capability;
- avanço de fence e geração;
- confirmação de decisão;
- revogação.

Essa autoridade atribui `commit_index` monotônico. “Simultâneo” significa apenas que a ordem ainda não foi observada; no log autoritativo, um evento vence.

### Registros append-only

- `OperationRecord`
- `EvidenceRecord`
- `ProofRecord`
- `DecisionRecord`
- `CapabilityRecord`
- `GraphChangeRecord`
- `FenceRecord`
- `ImpactAssessment`
- `RemediationOperation`
- `DeletionTombstone`
- `ReconciliationManifest`

Atualizações de estado são novos eventos ou mudanças com histórico completo. O registro anterior permanece consultável e marcado como superado, contestado ou afetado.

## 4. Identidade permanente de operação

Uma operação lógica possui identidade estável:

```text
operation_id
tenant_id
operation_kind
target_scope
canonical_request_digest
created_at
authority_epoch
```

Regras:

- `operation_id` nunca é reutilizado para outro digest, tenant, alvo ou tipo.
- Retry usa a mesma identidade lógica.
- Se for necessário comando diferente, criar nova operação com `caused_by_operation_id`.
- A expiração do payload completo não apaga o tombstone que impede reuso incompatível.
- Efeitos externos recebem chave idempotente derivada de identidade lógica estável, não de cada tentativa de transporte.

## 5. Resultados de efeito

| Estado | Significado | Retry automático |
|---|---|---|
| `COMMITTED_VERIFIED` | efeito confirmado pela autoridade | não necessário |
| `ABORTED_NO_EFFECT_VERIFIED` | ausência total de efeito comprovada | permitido como nova tentativa da mesma operação, conforme protocolo |
| `PARTIAL_EFFECT_VERIFIED` | subconjunto de efeitos identificado e comprovado | somente após plano seguro para o restante |
| `EFFECT_OUTCOME_UNKNOWN` | não é possível provar quais efeitos ocorreram | proibido se puder duplicar ou ampliar dano |
| `RECONCILING` | investigação ativa do mesmo efeito lógico | não criar operação concorrente incompatível |

Erro técnico, timeout ou perda de resposta não determinam o estado. A classificação depende de evidência da autoridade que executou ou observou o efeito.

## 6. Operação `UNKNOWN` com efeitos parciais

### 6.1 Conjunto de estados possíveis

Para uma operação ambígua, manter uma abstração `possible_state_set`. Ela inclui cada combinação materialmente alcançável de efeitos, não apenas `{sucesso, nenhum efeito}`.

Exemplo de cobrança mais notificação:

```text
S0: nada ocorreu
S1: cobrança ocorreu, mensagem não
S2: cobrança não ocorreu, mensagem ocorreu
S3: ambos ocorreram
```

Se existirem efeitos intermediários — reserva criada, outbox gravada, cobrança aceita, webhook ainda ausente — eles entram na abstração conforme sua relevância à próxima ação.

### 6.2 Regra formal de autorização

Para ação candidata `a` e sobreaproximação conservadora `Ŝ` dos estados possíveis:

\[
Authorize(a) \iff \forall s \in \hat{S}: Safe(a,s) \land PreservesInvariants(a,s)
\]

Se a prova universal falhar ou não puder ser construída, a ação permanece bloqueada.

### 6.3 Enumeração inviável

Quando o conjunto exato for muito grande ou impossível de reconstruir:

1. calcular sobreaproximação pelo fechamento de dependências e efeitos possíveis;
2. incluir todo estado não excluído por evidência autoritativa;
3. procurar ação monotônica/idempotente segura em toda a sobreaproximação;
4. liberar apenas o escopo cuja segurança foi demonstrada;
5. manter o restante em `UNKNOWN`/quarentena.

Precisão incompleta aumenta o bloqueio; não reduz o padrão de segurança.

### 6.4 Estado atual derivado de histórico em quarentena

Dados produzidos a partir de histórico contestado são não confiáveis para novos claims. Revalidar por:

- rederivação a partir de fontes independentes;
- reconciliação com autoridade externa;
- prova de corte que separe dependências contaminadas;
- nova observação do estado atual.

Onde isso não for possível, manter `UNKNOWN`. Uma observação atual pode autorizar apenas estado atual e futuro; não prova retroativamente o passado.

## 7. Provas de ausência e `PATH_DISABLED_PROVEN`

`PATH_DISABLED_PROVEN` significa que, até uma barreira específica, um caminho capaz de produzir o efeito foi comprovadamente desativado. A prova contém:

- escopo e operação;
- componente, chave e geração;
- fence/epoch;
- barrier commit index;
- dependências e digests;
- validade temporal;
- autoridade que atestou.

### Efeito antigo confirmado depois da barreira

Se surgir prova de que um efeito anterior foi confirmado depois da barreira usada para declarar `PATH_DISABLED_PROVEN`:

1. marcar a prova como `SUPERSEDED_BY_CONTRADICTION`;
2. reabrir contenção e avaliação de impacto;
3. preservar a prova e decisões anteriores no histórico;
4. revogar capabilities pendentes dependentes;
5. verificar defeito compartilhado do mecanismo de barreira;
6. avaliar efeitos já confirmados sem apagá-los.

### Defeito em componente compartilhado

Quarentenar e revalidar todas as provas dependentes do mesmo componente, chave ou geração. Preservar apenas caminhos com prova independente completa.

Se não for possível determinar se uma prova dependia do componente defeituoso, ela permanece em quarentena até que independência seja comprovada por evidência autoritativa.

Se a linhagem foi permanentemente perdida, a prova antiga nunca volta a ser válida. Uma nova prova independente pode sustentar apenas o estado atual e futuro.

## 8. Evidência, admissão e barreiras pendentes

### 8.1 Estados de evidência

| Estado | Uso permitido |
|---|---|
| `INTAKE_UNAUTHENTICATED` | preservação, correlação, investigação; não abre barreira autoritativa |
| `UNVERIFIED` | cadeia de custódia e monitoramento; não suporta claim ou bloqueio autoritativo |
| `ADMITTED_PENDING_COMMIT` | passou autenticação mínima, dedupe e escopo; bloqueia consumo no escopo |
| `CONFIRMED` | registrada no log autoritativo; participa da abstração |
| `REJECTED_AUTHORITATIVELY` | motivo e autoridade registrados; fecha barreira correspondente |
| `EVIDENCE_CONFLICT` | fontes autoritativas incompatíveis; escopo afetado fica `UNKNOWN` |
| `SUPERSEDED` | preservada, mas substituída por evidência posterior válida |
| `COMPROMISED` | não pode sustentar prova; dispara impacto transitivo |

### 8.2 Barreira durável de admissão

Evidência já recebida pela autoridade, mas ainda não confirmada no log, não pode perder a corrida para uma capability simplesmente por estar na fila. Após:

- autenticação mínima;
- validação de formato e escopo;
- deduplicação;
- controles de abuso;

criar `pending_evidence_barrier(scope, admission_id, admitted_at)`. Capabilities conflitantes não podem ser consumidas até confirmação ou rejeição autoritativa.

Timeout sozinho não libera evidência plausível atrasada. Ela é escalada, com SLO e fila prioritária.

### 8.3 Evidência não autenticada

- Preservar em intake com cadeia de custódia.
- Iniciar verificação independente prioritária se plausível.
- Uma constatação autenticada interna pode então abrir a barreira.
- Se for efêmera e irreproduzível, manter `UNVERIFIED`; ela informa investigação e monitoramento, mas não claims ou bloqueios autoritativos.
- Vários relatos não autenticados e consistentes aumentam prioridade/correlação, não autoridade.

### 8.4 Resistência a abuso

- deduplicação semântica;
- limites por origem, tenant, recurso e escopo;
- estimativa de diversidade de fontes;
- filas justas entre escopos;
- teto para o efeito do volume na prioridade;
- análise de identidades falsas/coordenação;
- preservação de todos os relatos originais.

Fingerprint inclui tenant, recurso e intervalo temporal. Clusters podem ser divididos por revisão. Deduplicação muda somente agendamento da investigação, nunca transforma relatos em uma única verdade nem aumenta contagem de fontes independentes.

Um `report_id` pode se vincular a vários incidentes/clusters. A relação é muitos-para-muitos; cada incidente calcula prioridade separadamente, sem duplicar o relato como fonte independente.

### 8.5 Acesso a relatos compartilhados

- Um objeto de conteúdo criptografado.
- Vínculos por incidente/tenant com visões redigidas.
- Autorização avaliada por vínculo.
- Campos fora do escopo não são materializados na visão.
- Auditoria de leitura e de redaction.

## 9. Capabilities de recuperação

### 9.1 Conteúdo mínimo

Uma capability é autorização específica, não uma credencial genérica:

```text
capability_id
operation_kind
target_scope
allowed_effects
possible_state_set_digest
evidence_set_version + digest
dependency_graph_version + digest
policy_version
authority_epoch
fence_version
subject/workload identity
issued_at + expires_at
one_time_nonce
impact_budget
approval_refs
```

### 9.2 Emissão

- Construir abstração conservadora.
- Demonstrar que o efeito autorizado é seguro em todos os estados.
- Registrar evidências, política, grafo e approvals.
- Emitir por CAS contra as versões analisadas.
- Capability nunca autoriza efeito mais amplo do que o explicitamente listado.

### 9.3 Consumo no ponto de efeito

O executor revalida atomicamente:

1. assinatura e identidade do sujeito;
2. uso único e expiração;
3. autoridade/epoch/fence;
4. versões e digests de evidência e abstração;
5. versão/digest do grafo;
6. política congelada aplicável;
7. ausência de barreira pendente no escopo;
8. orçamento de impacto;
9. pré-condições atuais.

Qualquer mudança torna a capability `STALE`/obsoleta e exige nova análise. Não existe “tolerância” para versão divergente em mutação positiva.

### 9.4 Ordenação simultânea

Evidência, mudança de grafo e consumo são serializados na mesma autoridade:

- se evidência ou grafo vencerem, a capability fica obsoleta;
- se o consumo vencer, o efeito permanece confirmado como fato histórico;
- a evidência/mudança posterior abre avaliação de impacto sobre esse efeito;
- não se desfaz o commit fingindo que a ordem foi outra.

Uma mudança já admitida e ainda não confirmada cria `pending_graph_change_barrier` por escopo e bloqueia consumo até confirmação ou rejeição.

## 10. Grafo de dependências e quarentena

### 10.1 Grafo versionado

Nós podem representar operações, decisões, provas, componentes, chaves, gerações, dados, partições, recursos e invariantes. Arestas têm tipo e materialidade:

- `DEPENDS_ON`
- `DERIVED_FROM`
- `AUTHORIZED_BY`
- `EXECUTED_WITH`
- `AFFECTS_INVARIANT`
- `PROVEN_INDEPENDENT_BY`

Mudança de grafo é uma operação autoritativa por CAS.

### 10.2 Nova dependência enquanto capability está em trânsito

Como a capability leva versão e digest do grafo, o executor detecta a mudança no ponto de efeito. Se a alteração do grafo foi confirmada primeiro, a capability fica obsoleta. Se o consumo foi confirmado primeiro, o efeito é histórico e a dependência nova dispara impacto.

### 10.3 Mudanças conflitantes pendentes

Adicionar e remover simultaneamente a mesma dependência:

1. preservar ambas as propostas;
2. usar união conservadora dos riscos enquanto pendentes;
3. bloquear o escopo afetado;
4. serializar cada proposta;
5. resolver por CAS sobre `conflict_set`.

Autoridades com o mesmo mandato exigem adjudicação independente ou política determinística previamente versionada.

### 10.4 Fechamento de invariantes

Conflito localizado que pode violar invariante compartilhada expande bloqueio para o fechamento transitivo das dependências dessa invariante. Recursos são preservados somente com prova de independência.

### 10.5 Cut proofs

Uma `cut_proof` demonstra que um subconjunto está separado do risco em todos os caminhos materiais. Ela registra grafo, evidências e predicado usados.

Se uma evidência da prova for comprometida:

- invalidar a `cut_proof`;
- requarentenar subconjuntos dependentes;
- revogar capabilities pendentes;
- avaliar efeitos já confirmados.

Se outra `cut_proof` independente já existir, ainda é necessário novo CAS que demonstre cobertura completa. Até a confirmação, o subconjunto continua em quarentena e ambas as proveniências são preservadas.

Para reduzir sobreaproximação ampla, coletar evidência autoritativa diretamente nos pontos de corte; liberar apenas subconjuntos comprovados.

## 11. Adjudicação, política e materialidade

### 11.1 Decisão contestada

Uma nova adjudicação que chega à mesma conclusão não valida retroativamente ações executadas com base na decisão contestada. Para cada ação antiga:

- preservar a derivação histórica contestada;
- avaliar segurança, autorização e efeitos separadamente;
- registrar remediação quando necessário;
- usar nova operação independente para consequências futuras.

### 11.2 Política versionada

Adjudicação registra `policy_version` e `policy_digest`. Mudança enquanto a decisão está pendente torna o CAS obsoleto e exige rebase explícito do `conflict_set`, preservando história.

Para evitar rebase infinito:

- governança e rate limit de mudanças de política;
- congelar `policy_version` para o `conflict_set` após rebase;
- exceção apenas por emergência aprovada por autoridade superior.

Se decisão confirmada sob política congelada for depois comprovada insegura:

1. preservar a decisão histórica;
2. marcá-la `AFFECTED`;
3. bloquear novas conclusões derivadas;
4. reavaliar de forma independente sob política corrigida;
5. analisar efeitos já confirmados.

### 11.3 Regras inseguras e materialidade

Quando apenas algumas regras são inseguras, calcular linhagem e fechamento transitivo das decisões que dependeram **materialmente** delas. Preservar demais somente com prova de independência.

Decisões antigas sem registro das regras utilizadas ficam `MATERIALITY_UNKNOWN`:

- novas derivações bloqueadas;
- priorização por impacto e dependências;
- reavaliação determinística em lotes idênticos;
- nenhuma amostragem para liberar o não avaliado.

### 11.4 Evidência durante reavaliação em lote

Cada lote é vinculado à versão/digest das evidências. Nova evidência:

- invalida somente sublotes cujo fechamento possa depender dela;
- preserva resultados independentes já confirmados por CAS;
- se não for possível localizar precisamente, usa sobreaproximação e invalida todos os potencialmente relacionados.

## 12. Efeitos irreversíveis e escopo futuro

### Resultado coincide com nova adjudicação

Quando a ação irreversível veio de derivação contestada, mas o resultado coincide com a nova conclusão:

- preservar o efeito se a reversão for insegura;
- manter a origem marcada como contestada;
- não declarar validação retroativa;
- autorizar consequência futura por nova operação independente.

### Nova adjudicação autoriza escopo menor

- preservar passado irreversível;
- cercar consequências futuras fora do novo escopo;
- permitir apenas parte coberta por nova operação;
- registrar lacuna entre efeito histórico e autorização atual.

### Consequências tecnicamente inseparáveis

Se partes autorizadas e não autorizadas não podem ser separadas, manter o efeito contínuo bloqueado até:

- transformação segura que separe os escopos; ou
- nova autorização independente que cubra o conjunto inteiro.

Coincidência de resultado não substitui autorização do conjunto.

## 13. Emergência e orçamento cumulativo

### Ação emergencial mínima

Se bloquear um efeito inseparável causar dano imediato, permitir somente ação que seja:

- mínima;
- temporária;
- segura em todos os estados possíveis;
- emitida por nova capability;
- aprovada por duas autoridades independentes;
- com expiração curta;
- limitada por impacto;
- completamente auditada.

### Renovação

Expiração com risco persistente não renova automaticamente. Exige:

- análise nova;
- evidência atual;
- dupla aprovação;
- nova capability;
- contabilização do impacto acumulado.

Ao ultrapassar orçamento cumulativo do incidente, proibir nova exceção. Restam:

1. modo temporário formalmente autorizado;
2. correção definitiva;
3. desligamento seguro do escopo.

### Modo temporário formal

Tem prazo, controles, SLOs, limites e autoridade de governança. Para renovar:

- nova revisão;
- evidências atualizadas;
- limites cumulativos explícitos.

Ao atingir hard limit não renovável, executar desligamento seguro.

### Fallback de hard limit

Se o desligamento normal não terminar a tempo, acionar fallback previamente aprovado e testado que:

- fecha admissões;
- avança fences/revoga leases;
- cerca efeitos automaticamente;
- é idempotente e fail-closed.

Partes sem contenção comprovada permanecem em incidente crítico.

## 14. Plano de controle out-of-band

Se o plano principal causou ou sofreu a falha, o fallback precisa de:

- autoridade e infraestrutura independentes;
- credenciais separadas;
- caminho out-of-band testado;
- logs resistentes e reconciliáveis.

Sem isso, não declarar desligamento seguro.

### Estado principal desatualizado

O plano out-of-band só pode executar ações monotônicas e fail-closed:

- avançar fences;
- revogar capabilities/leases;
- fechar admissões;
- isolar caminhos;
- reduzir permissões.

Nunca pode reabrir, reduzir fence, emitir mutação positiva ou reutilizar geração antiga com visão desatualizada.

### Devolução ao plano principal

Exige:

1. reconciliação do estado;
2. revogação de leases antigos;
3. reconhecimento do fence por todos os participantes relevantes;
4. transferência autoritativa para nova geração normal;
5. teste de pré-condições e monitoramento reforçado.

Participante que não reconhece o fence mantém seu caminho e downstream isolados. Retorno parcial é permitido somente para escopos comprovadamente independentes; caso contrário, a devolução global fica bloqueada.

Operações e estado local produzidos antes do fence são não confiáveis. Impedir replay automático e reconciliar cada `operation_id` com evidência autoritativa antes da reentrada.

## 15. Exclusão, retenção e legal hold

### Objeto compartilhado entre tenants/incidentes

Quando um tenant pede exclusão de relato ainda necessário em outro vínculo:

- revogar a visão e o acesso daquele tenant;
- apagar campos exclusivos quando permitido;
- manter somente campos cobertos por outra base válida ou legal hold;
- auditar decisão por campo;
- não usar o vínculo remanescente para ampliar finalidade.

### Hold disputado e prazo de retenção

Aplicar cada base de forma independente:

- sem legal hold ou outra base válida, excluir ao vencer a retenção;
- com hold válido, manter somente o mínimo selado até expiração;
- se hold expira ou é revogado durante a disputa, reavaliar imediatamente e excluir o conteúdo sem outra justificativa;
- preservar auditoria sem o conteúdo apagado.

### Adjudicação após exclusão legítima

Usar apenas digests, metadados e evidências remanescentes. Registrar lacuna probatória e manter conclusão inconclusiva se insuficiente. Nunca restaurar conteúdo sem nova base válida.

Reconstrução de metadados também é tratamento de dados: se violar exclusão ou restrição de finalidade, não reconstruir. Buscar prova compatível com privacidade ou manter `NOT_EVALUABLE`.

## 16. Restore e ledger de exclusões

### Prevenção de ressurreição

Manter ledger/tombstones de exclusão fora do backup restaurado. Sequência:

1. restaurar em ambiente isolado;
2. verificar integridade do backup;
3. recuperar ledger autoritativo;
4. reaplicar tombstones;
5. validar contagens/digests e políticas;
6. somente então liberar leitura/processamento.

### Ledger indisponível ou contestado

Recuperar por réplicas independentes, checkpoints assinados ou quorum. Sem prova autoritativa, manter restore selado.

Se réplicas apresentam checkpoints incompatíveis:

- aceitar apenas o maior checkpoint com certificado de quorum válido e cadeia íntegra;
- se não houver vencedor único, marcar `EVIDENCE_CONFLICT` e manter selado.

Se dois checkpoints incompatíveis têm certificados de quorum aparentemente válidos:

1. tratar como violação crítica de segurança;
2. congelar o ledger;
3. revogar configuração/chaves afetadas;
4. impedir promoção de restore;
5. exigir recuperação por autoridade externa e evidências independentes.

### Evento de exclusão em uma branch do fork

Tratamento conservador:

- manter como restrição e evento disputado;
- impedir exposição do dado até adjudicação externa;
- não incorporá-lo silenciosamente ao novo ledger;
- preservar proveniência de ambas as branches.

## 17. Reconciliação exata com manifests e Merkle

### Backlog grande

Não depender de amostragem probabilística para liberar estado. Usar:

- manifests assinados;
- árvore de Merkle;
- partições determinísticas;
- comparação de raízes para provar subconjuntos idênticos;
- reconciliação exata somente das divergências;
- liberação apenas de partições integralmente provadas.

### Prova de completude

Manifest precisa vincular:

- intervalo contínuo de sequência/offset;
- início e fim inclusivo/exclusivo definidos;
- contagem esperada;
- raiz Merkle;
- checkpoint previamente comprometido;
- versão do formato/algoritmo;
- assinatura e identidade da autoridade.

Qualquer lacuna permanece em quarentena. Uma raiz sobre os itens fornecidos não prova que itens omitidos nunca existiram; por isso sequência, contagem e checkpoint são indispensáveis.

## 18. Compactação e predicados de defeito

### Log compactado após checkpoint

Exigir manifest de compactação assinado que ligue a raiz anterior à nova representação sem perder identidade ou estado material. Sem prova, a partição fica em quarentena.

### Algoritmo de compactação defeituoso

- quarentenar partições produzidas pela versão afetada;
- bloquear novas derivações;
- reconstruir a partir de raízes/logs preservados;
- onde não houver prova, manter `UNKNOWN`.

### Defeito restrito a tipos de registro

Usar predicado verificável sobre entradas e manifests. Só liberar partição quando a independência em relação ao predicado estiver completa. Caso contrário, toda a versão permanece em quarentena.

### Predicado ampliado

- versionar o novo predicado;
- marcar prova anterior como superada;
- requarentenar novo escopo;
- revogar capabilities pendentes dependentes;
- avaliar efeitos já confirmados.

### Predicado estreitado

Não liberar automaticamente. Exigir nova prova de independência e novo CAS para cada partição/lote, preservando histórico de quarentena.

Se metadados necessários não existem:

- tentar reconstrução por fontes independentes e verificáveis, se permitido;
- sem reconstrução completa, marcar `NOT_EVALUABLE` e manter em quarentena;
- não violar exclusão ou finalidade para obter a prova.

## 19. Catálogo consolidado de estados

### Operação

`IN_PROGRESS`, `COMMITTED_VERIFIED`, `ABORTED_NO_EFFECT_VERIFIED`, `PARTIAL_EFFECT_VERIFIED`, `EFFECT_OUTCOME_UNKNOWN`, `RECONCILING`, `REMEDIATED`.

### Capability

`ISSUED`, `CONSUMED`, `STALE`, `REVOKED`, `EXPIRED`, `REJECTED_AT_CONSUMPTION`.

### Prova

`VALID`, `QUARANTINED`, `SUPERSEDED`, `COMPROMISED`, `INVALID`, `NOT_EVALUABLE`.

### Decisão

`PENDING`, `CONFIRMED`, `CONTESTED`, `AFFECTED`, `MATERIALITY_UNKNOWN`, `SUPERSEDED`.

### Escopo

`OPEN`, `CONTAINED`, `QUARANTINED`, `EVIDENCE_CONFLICT`, `UNKNOWN`, `RELEASED_BY_PROOF`, `CRITICAL_INCIDENT`.

Estados não devem ser tratados como enumeração fechada entre versões; leitores desconhecidos falham fechados para mutações.

## 20. Matriz operacional de cenários

| Cenário | Resposta obrigatória |
|---|---|
| `UNKNOWN` pode ter efeito parcial | enumerar estados ou sobreaproximar; agir somente se seguro em todos |
| prova de efeito contradiz barreira | superar prova, reabrir impacto/contenção, preservar história |
| dependência de prova é desconhecida | quarentena até prova autoritativa de independência |
| nova adjudicação coincide com ação antiga | sem validação retroativa; ação avaliada separadamente |
| nova adjudicação tem escopo menor | preservar passado, cercar futuro excedente, nova operação no escopo |
| capability e nova evidência concorrem | serializar; versão/digest e barreira pendente decidem |
| capability e grafo concorrem | mesma regra; consumo anterior vira fato histórico sujeito a impacto |
| fontes independentes conflitam | preservar ambas, marcar escopo local `EVIDENCE_CONFLICT`/`UNKNOWN` |
| conflito atinge invariante compartilhada | bloquear fechamento transitivo, liberar só independentes |
| efeitos futuros são inseparáveis | bloquear até separação ou autorização do conjunto |
| bloqueio causa dano imediato | capability emergencial mínima, dupla aprovação, expiração e orçamento |
| orçamento emergencial esgotou | modo formal, correção ou desligamento; sem nova exceção |
| fallback depende do plano falho | exigir controle out-of-band independente; senão incidente crítico |
| participante não reconhece fence | isolar participante/downstream; retorno parcial só com independência |
| materialidade antiga é desconhecida | bloquear derivações e reavaliar determinística, sem amostragem |
| ledger do restore não é autoritativo | manter restore selado |
| dois quorums incompatíveis | violação crítica; congelar e recuperar externamente |
| compactação não pode provar vínculo | quarentena/`UNKNOWN` |
| conteúdo necessário foi excluído | não restaurar sem base; conclusão pode permanecer inconclusiva |

## 21. Dados mínimos de auditoria

Cada decisão/autorização de alta garantia deve registrar:

- IDs globais e tenant/escopo;
- ator, autoridade e approvals;
- versões/digests de política, evidência, abstração e grafo;
- epoch/fence/generation;
- operação causadora e correlation ID;
- possíveis estados considerados;
- argumento/prova de segurança;
- orçamento e impacto consumido;
- resultado do CAS;
- instante de admissão, confirmação, emissão, consumo e efeito;
- revogações/superações posteriores;
- links de proveniência sem duplicar conteúdo protegido.

O log não deve conter segredos ou conteúdo legitimamente excluído. Digests ajudam a provar integridade, mas não substituem base legal nem provam semântica por si sós.

## 22. Adoção incremental no App Agendamento

### Implementar desde o MVP

- `operation_id` e `Idempotency-Key` ligados ao digest;
- CAS/versionamento em reserva, agenda e assinatura;
- outbox e consumidores idempotentes;
- `OUTCOME_UNKNOWN` para cobrança/mensageria ambígua;
- reconciliação por provedor;
- eventos/auditoria append-only;
- tombstones básicos de exclusão fora do restore;
- isolamento de restore antes de promoção.

### Implementar quando houver suporte operacional dedicado

- capability de uso único para exportação excepcional e correções críticas;
- dupla aprovação;
- graph/evidence digests;
- quarentena granular e cut proofs;
- manifests Merkle para backlog e restore grandes;
- política formal de materialidade.

### Implementar somente com necessidade de alta garantia

- autoridade global serializadora por escopo;
- fences geracionais distribuídos;
- plano out-of-band;
- adjudicação multi-autoridade;
- emergency budget formal;
- ledger replicado com certificados de quorum;
- provas formais de compactação.

## 23. Gates de segurança

Uma funcionalidade que usa estes controles só entra em produção quando demonstra:

1. replay com mesma identidade não duplica efeito;
2. payload diferente com mesma identidade é rejeitado;
3. capability stale falha no ponto de efeito;
4. evidência/mudança admitida abre barreira durável;
5. conflito é localizado e expande apenas pelo fechamento comprovado;
6. revogação alcança executores e impede nova mutação;
7. efeitos confirmados permanecem no histórico após nova evidência;
8. restore sem ledger autoritativo não serve dados;
9. exclusão legítima não é desfeita para completar investigação;
10. operação emergencial expira, consome orçamento e não renova automaticamente.

## 24. Síntese

O sistema deve preferir um bloqueio explicável a uma certeza inventada. Ao mesmo tempo, a quarentena não precisa ser global: versões, digests, grafos, cut proofs e CAS permitem liberar tudo que possua independência demonstrada. O eixo central é simples: fatos históricos são preservados, novas ações dependem de prova atual e a autorização é consumida somente no estado exato que foi analisado.
