# AGD-030 — Pipeline de notificações

## Identificação e objetivo

- História: AGD-030, conforme `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`.
- Base: merge de AGD-025 na `main`, `cb2fdbee705dbd8277bc4d943e3672b7f92ff74c`.
- Branch: `agd-030-pipeline-notificacoes`.
- Objetivo observável: consumir eventos de confirmação, remarcação e cancelamento, criar uma intenção deduplicada persistente e executar o ciclo de tentativa/resultado com segurança, sem exigir um fornecedor.
- Referências: documentos 01 §§7/13/17, 02 §8, 03 §§7–10, 04 §§9/18, 05 §§5/8/22 e documento 06 AGD-030/031.

## Escopo e exclusões

- Inclui confirmação, remarcação e cancelamento por cliente ou prestador, com estados e tentativas persistidos, consulta administrativa minimizada, processamento de eventos idempotente e suporte interno a evidências de entrega autenticadas por adapter.
- Não inclui AGD-031 (lembrete), templates aprovados, escolha/contratação de WhatsApp, envio real, credenciais gerenciadas, homologação, console de reenvio, cobrança ou mudança do estado da reserva por notificação.
- Sem fornecedor/protocolo aprovado, não existe endpoint de webhook ativo nem adapter de envio de produção. O adapter controlado fica restrito a testes.

## Estado e dependências

- A V6 já fornece `outbox_event`, `processed_event`, `FOR UPDATE SKIP LOCKED`, leases, fencing e classificação de resultado ambíguo para publicação genérica. O pacote `notifications` ainda contém somente `package-info`.
- Eventos de reserva existentes carregam apenas `{appointmentId,version}`. O destinatário e dados atuais são resolvidos por portas tenant-scoped de `appointments` e `clients`; nenhum PII é copiado para outbox ou intenção.
- Fornecedor, templates, segredos, assinatura/replay de webhook e hospedagem são pendências de produto/operação em `OPEN-QUESTIONS.md` e ADR-0003.

## Contratos e invariantes

- Tipos de intenção deste incremento: `APPOINTMENT_CONFIRMED`, `APPOINTMENT_RESCHEDULED`, `APPOINTMENT_CANCELLED`.
- Deduplicação: unicidade PostgreSQL por `(tenant_id, appointment_id, appointment_version, message_type, recipient_client_id)` e referência única ao evento de origem. `processed_event(consumer_name,event_id)` e criação da intenção compartilham transação.
- Evento histórico só cria intenção se versão e estado atuais ainda corresponderem ao evento. Antes do envio, a mesma condição é revalidada; evento antigo vira `OBSOLETE`, nunca envia dados atuais sob versão histórica.
- Tentativas são gravadas antes da chamada externa. `RETRYABLE_NO_EFFECT` só permite retry com backoff; falha definitiva encerra; timeout/crash após início da chamada vira `OUTCOME_UNKNOWN`, não é reivindicado novamente. A chave externa permanece estável por intenção.
- Chamadas externas ocorrem fora de transações. Claim usa lock curto, lease e `claim_token`; resultado exige ownership/token correspondente. Expiração de lease após tentativa iniciada é reconciliação, não permissão para reenviar.
- Webhook requer verificador de fornecedor; evidência normalizada é deduplicada e só promove estado conforme sequência autoritativa. Sem adapter aprovado, admissão de produção permanece ausente/fechada.
- Consulta admin usa tenant/ator da sessão OIDC e associação atual, retorna somente tipo, reserva, versão, estado, contadores, instantes e códigos allowlisted; sem telefone, payload, conteúdo, segredo ou identificador externo do provedor.

## Estratégia e testes

1. V22 criou intenções/tentativas/evidências ligadas; V23 acrescenta inbox durável para evidências autenticadas ainda sem correlação, preservando V1–V22 e grants runtime mínimos.
2. Definir portas públicas dos módulos `appointments` e `clients` para snapshots/notificação e contato, sem consultas cross-module arbitrárias.
3. Implementar consumidor local com receipt e intenção atômicos, worker/transport desacoplados, estados de falha e consulta administrativa.
4. Manter controller/webhook não registrado sem verifier; testes usam adapter sintético isolado.
5. Validar com PostgreSQL/Testcontainers instalação limpa/upgrade, duplicação/concor­rência, rollback, claims/fencing, backoff, ambiguidade, privacidade, isolamento e webhook sintético. Executar frontend somente se afetado; sem UI prevista neste incremento.
6. Executar `./mvnw -B -ntp spotless:check verify`, revisão sequencial (threads de subagentes indisponíveis no início) e gates remotos App CI/CodeQL após publicar.

## Progresso e validação

| Critério | Procedimento | Esperado | Observado |
|---|---|---|---|
| Integração AGD-025 | GitHub PR #17, checks no head e pós-merge | merge commit em main e App CI/CodeQL pós-merge | Passou; merge `cb2fdbee705dbd8277bc4d943e3672b7f92ff74c`; App CI [39289175761](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39289175761), CodeQL [39289175672](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39289175672), todos os jobs concluídos com sucesso |
| Consumo idempotente e intenção | `NotificationPipelineIT` com PostgreSQL 16/Testcontainers | dedupe, concorrência, rollback e eventos antigos sem dados incompatíveis | Passou no gate completo; redelivery/concor­rência, rollback receipt+intent e versão/estado obsoleto cobertos |
| Worker seguro | `NotificationPipelineIT` + transport controlado | fencing, backoff, falha final/ambígua sem retry cego | Passou; providerless não reclama nem consome tentativas; backoff/limite, fencing, erro terminal e resultado ambíguo cobertos |
| Admissão de evidência | `NotificationPipelineIT` com verifier sintético | inválido/duplicado/fora de ordem sem regressão | Passou; evidência inválida/duplicada rejeitada e evidência atrasada persiste sem regredir estado |
| Migração/permissões | `TenantPersistenceIT` e boot PostgreSQL real | instalação/upgrade, validate/rerun e grants mínimos | Passou no gate completo; V22 funcional, instalação limpa, upgrades Flyway, validate/rerun e grants runtime; V1–V21 preservadas |
| Validação local | `./mvnw -B -ntp spotless:check verify` em `backend/` | formatação e suíte completa | `BUILD SUCCESS` em 19/10/2026: 73 testes unitários/arquiteturais e 178 integrações PostgreSQL 16.13/Testcontainers; `NotificationPipelineIT` 11/11 e `TenantPersistenceIT` 21/21 |
| Revisão | leitura sequencial de código, contrato e diff | bloqueadores corrigidos e limites registrados | Revisão sequencial pelo coordenador. Subagentes indisponíveis por limite de threads; não equivale a revisão independente nem aprovação humana |
| CI remoto | App CI e CodeQL push/PR no SHA `07e657a666a6acf92b6d931ad967e108108e7c6f` | todos os jobs concluídos com sucesso | Passou: App CI push [39294080889](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39294080889) e PR [39294115689](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39294115689); CodeQL push [39294081135](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39294081135) e PR [39294115634](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39294115634), todos os jobs concluídos com sucesso. |
| Homologação WhatsApp | fornecedor, templates e ambiente aprovados | envio/consulta/webhook reais | Pendente; nenhum envio real ou template aprovado declarado |

## Descobertas e trade-offs

- A outbox atual já tem worker genérico, mas nenhum provider; sua lease recuperável após crash foi desenhada para publicação idempotente e não deve ser reutilizada para reenviar cegamente mensagens de resultado ambíguo.
- O adapter controlado prova composição e transações, não entrega, template ou autenticidade de fornecedor real.
- Não há mecanismo genérico suficiente para declarar ordenação de webhook sem protocolo selecionado; a camada normalizada exige sequência monotônica do adapter e permanece fechada em produção.
- Claims são adquiridos um a um imediatamente antes do processamento externo; o limite de lote não reserva leases antecipadamente.
- Inbox persiste somente campos normalizados mínimos. Evidência recebida antes de `markAccepted` fica pendente e é reconciliada atomicamente após a referência persistir. Duplicatas mantêm identidade única antes da correlação; sequência antiga não regride estado.
- Para `OUTCOME_UNKNOWN` sem referência e sem consulta/idempotência suportada pelo fornecedor, a evidência não pode ser associada com segurança. Mantém-se o estado desconhecido para reconciliação operacional; não há retry cego.

## Retomada

- PR [#18](https://github.com/LsaBarbosa/kronos_agendamento/pull/18) aberto, sem merge/deploy; código e CI push/PR validados em `07e657a666a6acf92b6d931ad967e108108e7c6f`.
- AGD-030 permanecerá parcial até fornecedor, templates aprovados, segredos, política de reconciliação suportada e homologação real.
- AGD-031 continua separada; não declarar lembretes invalidados/enviados.
- Revisão por subagentes não disponível por limite de threads nesta sessão; revisão sequencial será registrada sem alegação de independência ou aprovação humana.

## Follow-up P2 — claims e evidência antecipada

- Corrigir reivindicação em lote para uma intenção por vez, preservando lease/fencing e tratamento conservador da chamada em curso cujo lease expire.
- V23 cria `notification_evidence_inbox`, deduplicação por provedor/evento e vínculo posterior à intenção; migra evidências já persistidas de V22 sem payload bruto.
- Evidência local executada no código `23e502b`: `spotless:check verify` em Java 21 — 73 unitários/arquiteturais e 182 integrações PostgreSQL 16.13/Testcontainers; `NotificationPipelineIT` 14/14 e `TenantPersistenceIT` 22/22. Inclui corrida com latch/lease vencida, fake send admitindo webhook antes do aceite, dedupe/ordem/rollback, instalação limpa, upgrade V22→V23, validate/rerun e privilégios runtime. CI remoto do head documental será registrado após conclusão.
