# AGD-050 — Retenção, exclusão e legal hold

## Identificação e objetivo

- **Histórias:** AGD-050 (P0) e coordenação obrigatória com AGD-051 (P0).
- **Preparação original:** inventário e plano. **Continuação autorizada:** AGD-050-A, avaliação administrativa somente leitura. Nenhuma exclusão, anonimização, expurgo, hold, scheduler destrutivo ou migration foi implementado.
- **Resultado esperado da história:** políticas/base por categoria registradas; retenção inicial pós-cancelamento/suspensão aplicada subordinada a pedido de exclusão e hold válido; exclusão de conteúdo sem base remanescente; hold preservando o mínimo e com validade/autoridade; trilha residual sem conteúdo excluído. AGD-051 precisa reaplicar tombstones externos ao backup antes de permitir leituras.
- **Snapshot de referência/base:** `e8dedd0fa8c9d94e28bbe5bdd0b7c18d87d71cdf`.
- **Branch de implementação do incremento:** `agd-050a-retention-assessment`, criada sobre a base indicada. Commit funcional: `157807312dfff7e0fba140599e6664c3b51b2611` (`feat(agd-050a): add retention assessment API`).
- **Worktree/branch original preservadas:** `/tmp/kronos-agd044-ui`, `agd-044-exportacoes-ui`; nenhum arquivo dessa worktree foi alterado.
- **Limites:** não presumir lei, base legal, duração além do texto vigente, autoridade aprovadora, finalidade, consentimento ou direito a excluir evidências necessárias. Documento 05 descreve alguns controles de alta garantia; somente os fundamentos MVP da seção 22 e os critérios P0 são tratados como obrigatórios.

## Referências canônicas e decisões

- Índice e convenções: [docs/spec/app-agendamento-README.md](../spec/app-agendamento-README.md).
- Critérios: [documento 06, AGD-050/051](../spec/app-agendamento-06-backlog-criterios-aceite.md#agd-050--retenção-exclusão-e-legal-hold), linhas 415–437.
- Regras de cancelamento/suspensão, exportação, auditoria e privacidade: [documento 01 §§12.4, 13, 14](../spec/app-agendamento-01-especificacao-mvp.md), linhas 379–385, 409–426, 435–455.
- Retenção e idempotência: [documento 02 §15](../spec/app-agendamento-02-modelo-dominio-estados.md), linhas 312–314. As 24 horas para reservas são uma **proposta inicial**, não política aprovada.
- Tombstones/restore: [documento 04 §16](../spec/app-agendamento-04-arquitetura-implementacao.md), linhas 375–397; [documento 05 §§15–16 e 22](../spec/app-agendamento-05-resiliencia-controles-avancados.md), linhas 512–554 e 709–733.
- Pendências: [OPEN-QUESTIONS.md](../development/OPEN-QUESTIONS.md), em especial retenção de auditoria, idempotência/outbox/receipts, trial, retenção/SLO/operação (linhas 60–71). Não há linha específica para AGD-050/051; estes registros são as pendências operacionais aplicáveis.
- ADRs aplicáveis: [ADR-0003](../decisions/ADR-0003-integracoes-e-experiencia-pendentes.md) não aprova retenção; [ADR-0007](../decisions/ADR-0007-auditoria-append-only.md) mantém auditoria append-only e proíbe decidir descarte sem política/base/hold; [ADR-0008](../decisions/ADR-0008-idempotencia-outbox.md) mantém idempotência e outbox sem expurgo até decisão; [ADR-0015](../decisions/ADR-0015-recuperacao-publica-e-digest-hmac.md) separa validade técnica de recuperação da retenção; [ADR-0019](../decisions/ADR-0019-codigo-acesso-e-sessao-cliente.md), [ADR-0020](../decisions/ADR-0020-pipeline-de-notificacoes.md), [ADR-0021](../decisions/ADR-0021-lembrete-unico.md), [ADR-0025](../decisions/ADR-0025-trial-comercial-fail-closed.md) e [ADR-0026](../decisions/ADR-0026-integracao-de-cobranca-fail-closed.md) também não definem política de expurgo para seus dados.

## Estado confirmado no snapshot

As tabelas abaixo foram confirmadas nas migrations `backend/src/main/resources/db/migration/V1`–`V43`. A base não contém tabela de pedido de exclusão, legal hold ou ledger geral de tombstones. Há um tombstone/fence do armazenamento de artefatos de exportação no adapter, restrito a essa finalidade; ele não é o ledger autoritativo de AGD-051.

| Categoria persistida | Dados/tabelas confirmados | Dependência ou sensibilidade relevante |
|---|---|---|
| Dados de negócio e identidade | `tenant` (V2), `admin_user` e `admin_tenant_membership` (V3); `service` (V7); `weekly_schedule`, `weekly_period`, `availability_policy` (V8); `schedule_exception`, `schedule_exception_period`, `blocked_period`, `schedule_revision` (V10); `tenant_publication` (V12); `client`, `appointment`, `appointment_history` (V13 e evoluções); `client_no_show_policy` (V26); `client_booking_restriction`, `client_booking_restriction_history` (V28). | Cliente e reserva contêm nome/telefone/identificadores e estado. Reservas armazenam `client_name_snapshot`, snapshot do serviço/preço e instantes/timezone. FKs e históricos são `RESTRICT`; cancelamento é em geral lógico, não exclusão física. Existem reservas futuras e futuras dependências operacionais. |
| Snapshots de negócio/contrato | Campos snapshot em `appointment` (V13+); `billing_dunning_attempt` contém snapshot de plano/valor/moeda/termos/oferta (V40 e anteriores); `billing_subscription_contract` (V37); `export_job.snapshot_ciphertext`, chave e digest (V41). | Um snapshot pode conter conteúdo que já não aparece na entidade de origem. Excluir apenas a origem não elimina cópias em job/exportação nem resolve evidência contratual. Digests não equivalem a anonimização. |
| Desafios, limites e sessões | `spring_session`, `spring_session_attributes` (V4); `client_access_phone_limit`, `client_access_challenge`, `client_access_request_intent`, `client_access_send_intent`, `client_access_session` (V19 e V20). | Há sessão administrativa/OIDC e estado de cliente, fingerprints, telefone cifrado, verificador/salt e referências de entrega. Expiração/revogação de sessão/challenge é limite de acesso, não expurgo. Chaves antigas podem continuar necessárias enquanto registros as referenciam (ADR-0019). |
| Idempotência e receipts | `idempotency_record` (V6, alterada por V14); `public_booking_intent` com digest de recuperação e request HMAC digest (V14); `processed_event` (V6). | Dedupe depende de manter identificadores/digests vinculados a `operation_id` e ao resultado pelo horizonte ainda não decidido. Apagar ou reabrir uma chave pode duplicar reserva, efeito externo, auditoria ou evento. A capability de recuperação de 7 dias (ADR-0015) não é retenção. |
| Outbox e inbox | `outbox_event`, `processed_event` (V6); `notification_evidence_inbox` (V23); `billing_event_inbox` (V32). | Outbox pode estar pendente, em retry, em resultado desconhecido ou terminal; inbox contém identificadores/sequências de dedupe e evidência autenticada, inclusive ainda não correlacionada. O payload da outbox é allowlisted nesta versão, mas suas referências sustentam replay/reconciliação. |
| Notificações | `notification_intent`, `notification_attempt`, `notification_delivery_evidence` (V22); `notification_evidence_inbox` (V23); a intenção também representa lembrete (V24–V25). | Referências a reserva/cliente, versões, tentativas, chave externa, status desconhecido e sequência de evidência impedem reenvio incorreto e provam estado de transporte. A integração real não está configurada; isso não elimina os dados existentes. |
| Pagamentos e assinatura | `subscription`, `subscription_history` (V30–V39); `billing_checkout_operation`, `billing_event_inbox` (V32); `billing_dunning_cycle`, `billing_dunning_attempt` (V35–V40); `billing_subscription_contract`, `billing_annual_renewal_notice` (V37). | Operações e eventos externos têm referências, contas, sequência, valor/moeda, período pago, termos/oferta, estado desconhecido e histórico monotônico. Não há PAN/CVV segundo ADR-0026. Efeito externo ambíguo e dedupe de provider precisam permanecer reconciliáveis. |
| Exportações | `export_job`, `export_download_handle` (V41); objeto de artefato fora do PostgreSQL no storage configurado. | O snapshot cifrado e o artefato exportado são cópias de dados; `expires_at` limita acesso e agenda limpeza, mas não prova remoção física. Chave/digest/metadata, auditoria e tombstone/fence do objeto devem ser coordenados. |
| Auditoria | `audit_event` (V5 e allowlists expandidas em migrations seguintes). | Append-only para o runtime, sem FKs para tenant/ator/alvo. Tem tenant, ator, ação, alvo UUID, tempos, correlation/trace e detalhes allowlisted. Um FK ou cascata não deve ser introduzido para “resolver” exclusão. Remanescente precisa deixar de identificar/revelar conteúdo removido, conforme política aprovada. |
| Ledger de trial | `commercial_trial_grant` (V30), além de assinatura/histórico. Guarda `commercial_identity_digest`, único globalmente e por tenant. | A chave sustenta não concessão de novo trial após recriação; retenção/expurgo está explicitamente pendente (ADR-0025 e OPEN-QUESTIONS). Não assumir retenção perpétua nem remover o digest sem política e estratégia de dedupe aprovadas. |

### Dependências que a exclusão deve preservar ou resolver

1. **Integridade e ocupação:** appointments futuros não podem desaparecer silenciosamente nem liberar intervalos que ainda são compromisso válido. Antes de apagar cliente/tenant, definir cancelamento/comunicação e como preservar reservas e sua evidência; FKs `ON DELETE RESTRICT` e snapshots atuais sinalizam que cascata não é estratégia.
2. **Retry sem efeito duplicado:** manter identidade lógica (`operation_id`, escopo/digest de idempotência, receipts de consumidor, `event_id`) ou tombstone equivalente após apagar payload. Não apagar enquanto uma repetição válida ainda puder causar efeito; a duração desse horizonte depende de decisão por categoria e integração.
3. **Outbox/notificação e pagamentos ambíguos:** não excluir claims/tentativas `OUTCOME_UNKNOWN`, referências provider, sequências ou inboxes até reconciliação/decisão. Resultado externo pode ter ocorrido apesar de timeout, cancelamento, suspensão ou remoção da conta.
4. **Evidência e histórico:** preservar somente evidência com base independente aprovada; `appointment_history`, `subscription_history`, eventos de entrega/pagamento e auditoria não devem ser apagados por cascata. Quando conteúdo for removido, minimizar referências e detalhes sem reescrever histórico fora da política/ADR.
5. **Cópias:** localizar snapshots de reserva/contrato, export jobs e objetos, backups e logs antes de declarar conclusão. Expiração de acesso, revogação de sessão, perda de chave ou `artifact_deleted_at` não é por si só prova de destruição de todas as cópias.
6. **Hold versus exclusão:** serializar ativação/revogação/expiração de hold e início do descarte sobre o mesmo alvo/escopo/versionamento. Um hold contestado ou sem prova válida deve fechar o fluxo de destruição até decisão, sem conceder acesso ampliado ao conteúdo.
7. **Trial:** o digest único global em `commercial_trial_grant` evita concessões duplicadas entre tenants. A política deve decidir o que fica como dedupe residual e por quanto tempo, sem inferir direito de retenção perpétua.
8. **AGD-051:** tombstone precisa sobreviver fora do conjunto de backup restaurado; operação isolada reaplica tombstones e mantém o ambiente selado quando o ledger estiver ausente/inválido. Após restore, tombstone tem precedência sobre conteúdo antigo e não pode ser revertido por reconstrução de metadados.

## Requisitos vigentes, pendências e propostas

### Requisitos vigentes no pacote canônico

- AGD-050 exige política e base registradas por categoria; política inicial de 12 meses após cancelamento/suspensão, subordinada a pedido de exclusão e legal hold válido; pedido de exclusão remove conteúdo sem base remanescente; hold preserva somente o mínimo e precisa de validade/autoridade; auditoria residual não contém conteúdo excluído (documento 06, AGD-050; documento 01 §§12.4 e 14).
- Cancelamento/suspensão não apaga os dados existentes por si só; os 12 meses são política **inicial** e condicionada, sem constituir declaração de base legal universal (documento 01 §12.4).
- Excluir conteúdo sem base válida e registrar finalidade/base por categoria; restore respeita tombstones antes da liberação de leituras (documento 01 §14; documento 04 §16).
- AGD-051 exige backup com alerta em falha, restore isolado exercitado, reaplicação de tombstones externos antes da leitura, restore selado se o ledger não for íntegro/autoritativo e registro de RPO/RTO observados (documento 06, AGD-051).
- Documento 05 §22 inclui tombstones básicos externos e restore isolado no MVP; quorum, Merkle/cut proofs e controles avançados não entram automaticamente no incremento.

### Decisões operacionais/produto ainda pendentes

- Política/base por categoria e região/contrato, incluindo retenção de auditoria (OPEN-QUESTIONS linha 60); sem isso a ADR-0007 proíbe configurar retenção/descarte da auditoria.
- Horizonte de replay/dedupe e expurgo de idempotência, outbox e receipts (OPEN-QUESTIONS linha 61; ADR-0008). A proposta de 24h para reserva no documento 02 §15 não está aprovada; para webhooks financeiros há dependência do período contratual/auditável, ainda indefinido.
- Retenção do digest de elegibilidade de trial (OPEN-QUESTIONS linha 68; ADR-0025). Não está aprovada retenção perpétua.
- Autoridade elegível de hold, delegação, escopo, evidência verificável, validade, revisão, contestação, revogação e expiração. Critério exige “validade/autoridade”, mas não nomeia quem nem define o protocolo.
- Resultado de pedido de exclusão com reserva futura, vínculo compartilhado, obrigação contratual ou resultado externo incerto; a especificação exige não apagar dado com base remanescente, mas não define a decisão operacional dessas situações.
- Autoridade/localização operacional do ledger externo, backup cadence, RPO/RTO-alvo, retenção de backups e processo para recuperar divergência/indisponibilidade. O aceite pede registrar valores observados, não fixa metas.
- Escopo de sujeito e solicitante (cliente, administrador do tenant, encerramento integral do tenant ou outra origem) e método de verificação/contestação. Não inferir autorização do login administrativo para agir em nome de um titular.

### Propostas técnicas, não decisões aprovadas

- Política declarativa versionada por categoria com `purpose`, base aprovada/referência da decisão, âncora temporal, duração, ação no vencimento, escopo regional/contratual, exceções e referência da aprovação. O motor rejeita execução se algum campo obrigatório não estiver aprovado/configurado.
- Pedido e avaliação duráveis, tenant-scoped e idempotentes; exclusão com outbox para armazenamento externo; operações de conteúdo usam CAS/versionamento e processamento repetível. Não apagar receipts de forma que reabra efeitos.
- Hold como entidade independente, escopada, temporal e versionada, com autoridade/evidência referenciada e auditoria minimizada. Estado ausente, expirado, revogado ou contestado não é convertido silenciosamente em hold válido.
- Tombstone append-only fora dos backups do domínio, contendo identificador opaco mínimo, escopo, sequência/versão, instante e prova de política/decisão — sem payload excluído. Persistência, assinaturas, replicação e controle de acesso do ledger exigem decisão de operação; não se presume quorum do modelo avançado.
- Separar “acesso revogado”, “conteúdo marcado para descarte”, “descarte lógico concluído” e “cópias externas/backups sob política” no status. Não declarar destruição completa enquanto artefatos externos necessários não confirmarem a ação ou o processo de backup não tiver um estado explícito aprovado.

## Decisões mínimas que bloqueiam execução de exclusão

| Decisão | Recomendação para deliberação (não aprovada) | Consequência se ficar em aberto |
|---|---|---|
| Prazos e bases por categoria | Produto, jurídico e operação aprovarem uma matriz categoria × finalidade/base × prazo × exceção × local/cópias. Tratar os 12 meses pós-cancelamento/suspensão como a regra inicial textual a validar; não transplantar validade técnica, 24h de proposta, 30–90 dias de logs ou 2 anos de auditoria de exportação para outras categorias. | Não habilitar job de expurgo nem concluir elegibilidade temporal das categorias sem regra aprovada. |
| Marco inicial e eventos que reiniciam/param o prazo | Aprovar por categoria o instante do evento autoritativo. Para a regra inicial de tenant, deliberar se cancelamento efetivo ou suspensão terminal é âncora e como reativação/novo vínculo afeta o contador; não presumir que suspensão por inadimplência equivale a encerramento. | Não calcular vencimento destrutivo automaticamente; consultas podem mostrar “política/âncora pendente”. |
| Pedido com reservas futuras e efeitos pendentes | Definir atendimento parcial, cancelamento/comunicação e ordem de tratamento; preservar reservas futuras até haver decisão explícita e resolver tentativas/outbox/provider em voo ou desconhecidas antes de remover referências necessárias. | Pedido fica bloqueado por dependência explícita; sem cascata ou cancelamento implícito de compromisso/efeito externo. |
| Autoridade e validade do legal hold | Designar os papéis/limites autorizados, evidência de autoridade, alvo/finalidade, data de início, validade, revisão, contestação e revogação; exigir verificação server-side e log de decisão minimizado. Sem presumir que o tenant admin ou um operador isolado é aprovador válido. | Hold não pode autorizar retenção, nem ausência de hold deve autorizar descarte, até o processo ser verificável. Conteúdo em disputa permanece bloqueado para destruição e acesso segue least-privilege vigente. |
| Minimização da auditoria residual | Aprovar campos que podem permanecer e o método de desvincular alvo/ator (por exemplo, identificadores opacos/irreversíveis somente se aprovados), preservando evento, integridade e obrigação de auditoria; definir como tratar JSONB legado com conteúdo identificável. | Não excluir/reescrever `audit_event` nem declarar que auditoria foi minimizada. Evitar novas gravações de conteúdo no fluxo de exclusão enquanto a allowlist não estiver deliberada. |

**Decisão técnica-operacional adicional de AGD-051:** designar fonte autoritativa e independente do backup para tombstones, custodiante, acesso, verificação de integridade, recuperação e comportamento fail-closed. Sem ledger íntegro, nenhum restore é promovido para leitura/processamento. Registrar RPO/RTO medidos; metas não foram inferidas.

## Contrato, estados e incremento testável propostos

Contrato abaixo é uma proposta para congelar com backend, frontend e operação antes de implementação; não existe contrato de API AGD-050 nesse snapshot.

### Comandos/leitura propostos

- `POST /api/v1/admin/privacy/deletion-requests`: cria ou recupera pedido por `Idempotency-Key`; servidor deriva tenant da sessão/autorização corrente. Corpo restrito a escopo opaco (`targetType`, `targetRef`), categorias solicitadas e referência da solicitação; sem tenant confiável vindo do browser e sem descrição livre contendo PII. Não executar se política ou autoridade estiver pendente.
- `GET /api/v1/admin/privacy/deletion-requests/{requestId}`: estado minimizado, categorias/ações avaliadas, bloqueios enumerados, versão/ETag e próximos passos; sempre revalida tenant e autorização.
- Hold: contrato separado para registrar/consultar/encerrar hold, com escopo opaco, evidência/autorização referenciada fora de conteúdo livre, validade e versão CAS. A rota, papéis e fluxo de aprovação ficam **indefinidos até decisão de autoridade**; não publicar comando ativo antes disso.
- Preview/read-only pode ser exposto para avaliar dependências e categorias, sem marcar destruição nem retornar conteúdo; resultados agregados e `no-store`.
- Comandos mutáveis idempotentes, tenant-scoped, com resultado estável e rejeição de chave reutilizada com payload divergente; respostas de mutação nunca afirmam eliminação física de backup/objeto até confirmação aplicável. Não usar assinatura de URL/capability para substituir autorização atual.

### Estados propostos

- Pedido: `RECEIVED` → `EVALUATING` → `BLOCKED` (motivos enumerados: hold válido/contestado, política/base pendente, reserva futura, operação externa desconhecida, dependência de evidência) ou `WAITING_RETENTION` → `READY` → `EXECUTING` → `COMPLETED`.
- Erro recuperável: `RETRY_WAIT`; erro terminal/decisão insuficiente: `REQUIRES_REVIEW`. `CANCELLED` é permitido antes de qualquer descarte irreversível e exige auditoria. Não representar “pedido aceito” como “exclusão concluída”.
- Hold independente: `PENDING_VALIDATION` → `ACTIVE` → `EXPIRED` ou `REVOKED`; `CONTESTED` bloqueia execução destrutiva até adjudicação. A transição para `ACTIVE` requer autoridade/evidência validada conforme regra aprovada.
- Cada categoria do pedido tem resultado próprio e versão de política/decisão; isso permite tratamento parcial explícito sem dizer que o alvo inteiro foi apagado quando apenas parte é elegível.
- Tombstone externo: sequência monotônica com estado terminal de exclusão e escopo mínimo. Ausência, corrupção ou divergência do ledger mantém restore `SEALED`; nunca reconciliar usando somente conteúdo do backup.

### Primeiro incremento funcional após decisões

1. Para qualquer escrita, congelar matriz de política/base, âncora, papel de hold e escopo de pedido; revisar contrato/API e allowlists de auditoria. Essa decisão não bloqueia a avaliação read-only AGD-050-A.
2. Implementar avaliação read-only por tenant, como incremento separado, sem pedido persistido, idempotência, CAS ou efeito de domínio. Pedido durável e estados `BLOCKED`/`WAITING_RETENTION`/`READY` continuam proposta futura e dependem das decisões acima.
3. Habilitar uma única categoria e fluxo aprovado de ponta a ponta, com tombstone externo e worker idempotente, auditoria minimizada e fencing de cópias em storage. Não expandir allowlist até cada categoria e cópia serem inventariadas.
4. Exercitar em PostgreSQL real: mesma chave/body, chave com body divergente, isolamento entre tenants, marco temporal e fronteira, reserva futura, hold ativo/expirado/contestado, resultado externo desconhecido, retry/restart de worker, tentativa tardia de escrita após tombstone, minimização da auditoria e falha de ledger.
5. Integrar AGD-051: restaurar backup que antecede tombstone em ambiente isolado; reaplicar o ledger antes de qualquer leitura; comprovar que o conteúdo não reaparece; indisponibilidade/integridade duvidosa mantém restore selado. Registrar RPO/RTO observados sem declarar metas inexistentes.

## Coordenação com AGD-051

- Definir antes das migrations: identificador de tombstone estável e opaco, alvo/categorias, ordem global monotônica ou versão CAS, localização/autoridade fora do backup, retenção do próprio tombstone e verificação de integridade. O tombstone não deve carregar payload/PII desnecessário.
- O comando de exclusão grava intenção/tombstone de forma durável antes de iniciar o descarte das cópias. O worker de objeto externo respeita fence: escrita/restore antigo não pode recriar o conteúdo após a versão tombstone.
- Runbook AGD-051 restaura em isolamento, verifica backup e ledger, reaplica tombstones, valida integridade/contagens segundo escopo permitido e só libera leitura após prova positiva. Falha ou disputa mantém ambiente selado e sem processamento.
- O restore não desfaz hold expirado/revogado nem recria uma base de retenção; a política atual precisa ser reaplicada ao conjunto restaurado. O restore não pode introduzir conteúdo que uma exclusão válida removeu.
- Quorum, provas Merkle e cut proofs não são pré-requisito deste primeiro incremento; reconsiderar somente se operação/escala justificarem e houver decisão registrada.

## Divisão futura de trabalho e ownership

Contrato OpenAPI e matriz de categorias/decisões devem ser aprovados e congelados pelo coordenador **antes** de trabalho paralelo. Até isso acontecer, implementação paralela é bloqueada; compartilhar primeiro exemplos, enumerações de estado/erro, idempotência, autorização, ETag/CAS, e semântica de conclusão.

| Frente / responsável único | Arquivos sob responsabilidade futura | Limite |
|---|---|---|
| Backend — Terminal 1 (ou sucessor formal) | `backend/src/main/java/com/appagendamento/privacy/**`; migrations novas e imutáveis em `backend/src/main/resources/db/migration/V*.sql`; testes correspondentes em `backend/src/test/java/com/appagendamento/privacy/**`. | Persistência, transações/CAS, auditoria, idempotência, outbox, worker de storage e ledger. Coordenador revisa migrations/contrato; não tocar arquivos frontend. |
| Frontend — Terminal 2 (ou sucessor formal) | novo módulo em `frontend/src/features/privacy/**` e seus testes em `frontend/src/features/privacy/**/*.test.tsx`. | Consumir somente contrato congelado; mostrar escopo/estado/bloqueios sem conteúdo desnecessário; não calcular política ou autorização no browser. Não alterar `App.tsx` ou configuração comum sem atribuição explícita. |
| Contrato e decisão compartilhada — coordenador | `docs/openapi/admin-privacy.yaml` (novo) e decisão/matriz em documento compartilhado atribuído antes da execução; `docs/spec/**` somente após decisão explícita de produto. | Um dono para cada arquivo compartilhado; concluir OpenAPI e modelo de estados primeiro. Nenhum worker altera em paralelo OpenAPI/especificação/roteiro E2E. |
| Operação/AGD-051 — dono designado pelo coordenador | runbook de restore, armazenamento externo do ledger e teste isolado do pipeline de backup, em arquivos a atribuir. | Definir autoridade/custódia e critérios de selagem; não usar o próprio backup como fonte autoritativa de seus tombstones. |

O caminho de implementação não deve adicionar migration até requisitos/decisões mínimos estarem aprovados, matriz de dados reconciliada com produção e contrato revisto. Migrations V1–V43 neste snapshot são baseline imutável.

## AGD-050-A — avaliação administrativa somente leitura

- **API:** `GET /api/v1/admin/retention/assessment`; tenant vem de `AuthenticatedAdmin`, sem tenant em path/query/body. O caso de uso revalida associação ativa antes e depois das consultas. Responde `Cache-Control: no-store`; não possui command de exclusão/hold.
- **Fontes consultadas por projeções públicas dos módulos donos:** tenant lifecycle status; contagem de clients; total de appointments e appointments confirmadas futuras; total e estados pendentes/aceitos/desconhecidos de notification intents; total, pendentes e reconciliation/dead-letter de outbox; export jobs totais/ativos/artefatos com metadata disponível/limpeza pendente; subscription status e contagens de checkout/dunning pendentes/desconhecidos. Não há migrations.
- **Limite por métrica:** query observa até 10.001 linhas (`LIMIT 10.000 + 1`), retorna `value=10.000/status=AT_LEAST_LIMIT` quando excede o limite, e informa `countLimit=10.000`. Cada fonte tem timeout JDBC de 2 segundos; timeout/erro de uma fonte resulta em `SOURCE_UNAVAILABLE`. Não há paginação nem retorno de linhas/IDs.
- **Tempo/consistência:** `referenceAt` é capturado uma vez e usado para futuro/vencimento. Cada port executa uma consulta SQL independente em PostgreSQL com READ COMMITTED; `observedAt` é horário de conclusão observado pela aplicação. Resposta declara `NON_ATOMIC_READ_COMMITTED`, `startedAt` e `completedAt`; não é snapshot atômico.
- **Distinções:** ausência é `EXACT` com valor 0; falha da fonte é `SOURCE_UNAVAILABLE`, sem contagem; categoria fora do recorte é `NOT_EVALUATED/NOT_QUERIED`, sem contagem; falta de política/base aparece `NOT_CONFIGURED`; o marco da regra dos 12 meses segue `UNRESOLVED`; autoridade/inventário de hold são `UNDEFINED/NOT_EVALUATED`.
- **Categorias consultadas:** `TENANT_LIFECYCLE`, `CLIENT_RECORDS`, `APPOINTMENTS`, `NOTIFICATION_INTENTS`, `OUTBOX_EVENTS`, `EXPORT_JOBS`, `BILLING_OPERATIONS`.
- **Categorias ainda não consultadas:** `TENANT_AND_BUSINESS_CONFIGURATION`, `ADMIN_IDENTITIES_AND_MEMBERSHIPS`, `BUSINESS_SNAPSHOTS`, `CHALLENGES_AND_SESSIONS`, `IDEMPOTENCY_AND_RECEIPTS`, `NOTIFICATION_EVIDENCE_AND_INBOXES`, `PAYMENT_EVIDENCE`, `EXPORT_OBJECT_STORAGE`, `AUDIT_EVENTS`, `TRIAL_LEDGER`, `BACKUPS_AND_RESTORES`, `LEGAL_HOLDS`. Em particular, export job/metadata não é prova de estado físico do object storage.
- **Contrato para UI futura:** [docs/openapi/admin-retention.yaml](../openapi/admin-retention.yaml). Categorias expõem estado de avaliação/fonte, `observedAt`, contagens minimizadas/limitadas, fatos enumerados e motivos globais. Exemplo não contém IDs, nomes, telefones, conteúdo de mensagem, tokens, chaves ou detalhes financeiros.
- **Semântica informativa:** `informationalOnly=true`; blockers permanentes incluem `ASSESSMENT_ONLY_NO_DISCARD_AUTHORIZATION`, política/base ausentes, marco não resolvido e hold não avaliado. Não calcula `deleteAt`, não valida hold e não afirma conformidade.
- **Arquivos de código:** novos ports em `appointments.api`, `clients.api`, `tenant.api`, `notifications.api`, `outbox.api`, `exports.api`, `subscriptions.api`; adapters SELECT nos respectivos `*.infrastructure`; agregador/controller/erro em `retention.application` e `retention.api`; testes `RetentionAssessmentIT`, `GetRetentionAssessmentTest` e mock do novo caso de uso no `SecurityBoundaryTest`. Nenhum adapter sintético de produção ou migration.

### Requisitos definidos e propostas ainda não aprovadas nesta continuação

- **Definidos:** o pacote mantém retenção inicial de 12 meses pós-cancelamento/suspensão; AGD-050 exige política/base por categoria, hold válido com autoridade/validade e auditoria residual sem conteúdo excluído. AGD-050-A expõe apenas fatos observados e deixa o marco inicial, base por categoria, autoridade e inventário de hold sem resolução.
- **Pendentes, não aprovados:** prazo/base para demais categorias; evento/marco inicial e efeito de reativação; destino de pedidos com reservas futuras ou efeitos externos incertos; autoridade/evidência/validade do hold; campos de auditoria residual; fonte autoritativa/custódia do ledger AGD-051.
- **Proposta técnica:** manter o recorte atual limitado e expandi-lo via novos ports minimizados dos módulos donos. Nenhuma recomendação do plano ou status `NOT_CONFIGURED` representa aprovação jurídica/operacional.

## Estratégia de validação

| Critério de aceite | Procedimento futuro | Resultado esperado | Resultado observado nesta preparação |
|---|---|---|---|
| Avaliação AGD-050-A e isolamento | `RetentionAssessmentIT` em PostgreSQL 16.13/Testcontainers, tenant primário e segundo tenant | Contagens tenant-scoped, associação inválida negada, body sem conteúdo privado e leitura sem writes | Passou no `RetentionAssessmentIT` focal: 5 testes, zero falhas/erros/skips. |
| Zero, fonte indisponível, categoria fora do escopo e limite | `GetRetentionAssessmentTest` + fixtures PostgreSQL | Estados distintos; falha não vira zero; fonte posterior continua; limite não vira contagem exata | Passou no `verify` completo: 88 unitários/arquiteturais e 327 integrações em PostgreSQL, zero falhas/erros/skips. Os 2 unitários de retenção passaram. |
| Contagem exatamente no limite e acima dele | `RetentionAssessmentIT.reportsExactCountAtLimitAndLowerBoundAboveIt` com 10.000 e 10.001 clientes sintéticos | 10.000 retorna `EXACT`; acima retorna valor 10.000 e `AT_LEAST_LIMIT` | Passou com PostgreSQL 16.13/Testcontainers; fixture e banco são descartáveis e independentes do Compose compartilhado. |
| Timeout real de fonte e continuação da avaliação | `RetentionAssessmentIT.timesOutOneLockedSourceAndContinuesEvaluatingLaterSources`; lock PostgreSQL verificado em `pg_locks` antes do GET, timeout JDBC existente de 2s | `CLIENT_RECORDS` é `SOURCE_UNAVAILABLE`, sem `counts`; `APPOINTMENTS` posterior continua `ASSESSED` | Passou com PostgreSQL 16.13/Testcontainers; sem mocks nem sleeps. Lock liberado ao fechar/rollback da conexão no fim do teste. |
| Sintaxe YAML/OpenAPI, referências e exemplo | `ruamel.yaml==0.18.15`, `openapi-spec-validator==0.9.0` e `openapi-schema-validator==0.9.0`, instalados em venv temporário fora do repositório | YAML sem chaves duplicadas; OpenAPI 3.1 estruturalmente válido e referências locais resolvidas; exemplo validado contra o schema | Passou: `YAML syntax and duplicate keys: valid`; `docs/openapi/admin-retention.yaml: OK`; um exemplo do response 200 validado por `OAS31Validator`. Nenhuma incompatibilidade de contrato encontrada. |
| Política/base por categoria, exclusão e legal hold válidos | Deliberação operacional + testes futuros de domínio | Nenhuma exclusão sem regra/base/âncora/autoridade; hold somente conforme decisão aprovada | Não implementado. Requisitos legais/operacionais pendentes; API só informa. |
| Auditoria residual e AGD-051 restore | Teste futuro de exclusão + restore drill com backup anterior a tombstone e ledger externo | Auditoria minimizada; conteúdo não reaparece; ledger inválido mantém restore selado | Não executado; fora do AGD-050-A. |

## Progresso e encerramento/retomada

- [x] 2026-10-26 — inspeção do snapshot integrado e8dedd0, migrations V1–V43, especificação, OPEN-QUESTIONS e ADRs aplicáveis.
- [x] 2026-10-26 — plano original criado e preservado; continuação registrada em `agd-050a-retention-assessment`.
- [x] 2026-10-26 — API GET read-only, OpenAPI e consultas reais por ports dos módulos; nenhuma migration ou comando destrutivo.
- [x] 2026-10-26 — `spotless:apply spotless:check test`: 88 unitários/arquiteturais passaram; `RetentionAssessmentIT`: 3 integrações PostgreSQL 16.13 passaram; `GetRetentionAssessmentTest`: 2 unitários cobriram fonte indisponível e limite de contagem.
- [x] 2026-10-26 — OpenAPI 3.1 validado isoladamente: parser YAML com rejeição de duplicatas, validador de estrutura/referências e validação do exemplo do response contra schema; ferramenta e comandos reproduzíveis abaixo.
- [x] 2026-10-26 — lacunas de evidência PostgreSQL fechadas: fronteira exata/limitada e timeout por lock real com continuidade para fonte posterior; `RetentionAssessmentIT` passou com 5 testes e `./mvnw -B -ntp verify` completo passou com 88 unitários/arquiteturais e 327 integrações.
- [ ] Deliberar as decisões mínimas de prazo/base, âncora, reservas futuras, autoridade/validade de hold e auditoria residual antes de qualquer escrita destrutiva.
- [ ] Definir política/ledger externo e runbook AGD-051; avaliação atual não cobre restore nem é prova de conformidade.
- [ ] Expandir categorias não avaliadas somente com contrato e queries minimizadas dos módulos proprietários.

**Retomada exata:** branch `agd-050a-retention-assessment`, base `e8dedd0fa8c9d94e28bbe5bdd0b7c18d87d71cdf`, commit funcional `157807312dfff7e0fba140599e6664c3b51b2611`; commits posteriores registram o handoff e a validação OpenAPI. A API atual não altera conteúdo e as migrations V1–V43 permanecem intactas.

### Validação OpenAPI complementar

Ferramentas instaladas somente no ambiente temporário `/tmp/agd050a-openapi-validator`; nenhuma dependência, configuração compartilhada ou baseline do projeto foi alterada. `openapi-spec-validator 0.9.0` declara suporte a OpenAPI 3.1; `ruamel.yaml 0.18.15` rejeita chaves duplicadas por padrão. Instalação reproduzível:

```sh
python3 -m venv /tmp/agd050a-openapi-validator
/tmp/agd050a-openapi-validator/bin/python -m pip install openapi-spec-validator==0.9.0 openapi-schema-validator==0.9.0 ruamel.yaml==0.18.15
```

Validações executadas na raiz do repositório:

```sh
/tmp/agd050a-openapi-validator/bin/python -c 'from ruamel.yaml import YAML; YAML(typ="safe").load(open("docs/openapi/admin-retention.yaml")); print("YAML syntax and duplicate keys: valid")'
/tmp/agd050a-openapi-validator/bin/openapi-spec-validator docs/openapi/admin-retention.yaml
```

O primeiro comando passou sem sintaxe inválida ou chave duplicada; o segundo respondeu `docs/openapi/admin-retention.yaml: OK` para a versão declarada `3.1.0`, verificando a estrutura OpenAPI e referências. Um script pontual com `OAS31Validator` validou o único exemplo embutido (response 200) contra o schema resolvido; resultado `Example example: valid` e `1 validated`. A validação não executa a aplicação nem prova que respostas em runtime sempre respeitam o contrato.

Correspondência estática conferida com `AdminRetentionAssessmentController`, `RetentionAssessment`, `GetRetentionAssessment`, os adapters SQL e testes existentes: método/rota GET, cookie `AGDSESSION`, campos serialized, política de 12 meses com marco `UNRESOLVED`, limites `countLimit=10000` e timeout 2s, estados de categoria e respostas de autenticação/autorização. Categorias não avaliadas omitem contagens; fonte indisponível usa `SOURCE_UNAVAILABLE`/`UNAVAILABLE` e mapas vazios, nunca zero; contagem truncada usa valor 10.000 e `AT_LEAST_LIMIT`. Nesta continuação, apenas `RetentionAssessmentIT` e documentação de evidências foram alterados; OpenAPI e implementação da API não mudaram. O teste focal com PostgreSQL e a suíte `verify` completa foram executados após as alterações.
