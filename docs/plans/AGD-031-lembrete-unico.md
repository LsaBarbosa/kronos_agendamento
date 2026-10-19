# AGD-031 — Lembrete único

## Identificação e objetivo

- Implementar intenção persistida de um único lembrete por reserva/versão, integrada ao pipeline AGD-030.
- Referências: documento 01, seção “Mensagens do MVP”; documento 02, regras de reserva e eventos; documento 03, envelope de outbox e jobs; documento 06, AGD-031 e E2E-05.
- Base: PR #18 integrado em `main` pelo merge `ce417455c058a13a3dc3113d89729f3d9d0645af`; branch `agd-031-lembrete-unico`; implementação local no commit `59966fa`.

## Escopo e exclusões

- Incluir: cálculo de vencimento pelo offset configurado 2/6/12/24 horas; dedupe PostgreSQL; consumo recuperável de eventos de confirmação, remarcação e estados terminais; invalidação de versões obsoletas; preflight temporal; métricas sem PII.
- Excluir: fornecedor/WhatsApp real, templates, segredos de produção, envio de lembrete aprovado, AGD-032/033 e alterações de política de retenção.
- Ausência de `AvailabilityPolicy` configurada não equivale a default: não criar lembrete sem offset válido.

## Critérios verificáveis

| Critério AGD-031 | Prova prevista |
|---|---|
| Horário calculado a partir do início e offset | Testes de domínio com `Clock`, UTC e zonas IANA em transições DST |
| Job atrasado reavalia se ainda faz sentido | Integração PostgreSQL; lembrete só segue para transporte se versão/status atuais forem elegíveis e o início ainda não tiver ocorrido |
| Cancelada, encerrada ou remarcada não recebe lembrete obsoleto | Testes com `AppointmentRescheduled`, `AppointmentCancelled`, `AppointmentCompleted`, `ClientNoShowRecorded` e corrida com claim |
| Workers concorrentes não duplicam efeito lógico | Unicidade por tenant/reserva/versão/tipo/destinatário, claims SKIP LOCKED/fencing e testes com conexões independentes |
| Backlog/idade monitorados | Métricas agregadas por estado/tipo fixo, sem PII nem labels de alta cardinalidade |

## Dependências e decisões

- Reutilizar V22/V23, `processed_event`, `NotificationEventConsumer`, `NotificationWorker`, fencing, chave externa, outbox e lock de correlação do ADR-0020.
- V24 é prevista somente para habilitar `APPOINTMENT_REMINDER`, preservar `due_at` separado de `next_attempt_at` e permitir que uma confirmação produza a notificação imediata e o lembrete na mesma origem de evento; V1–V23 permanecem imutáveis.
- O consumo precisa ser idempotente e recuperar eventos antigos por consumidor próprio; processar versão atual, nunca enviar conteúdo de evento histórico para uma reserva em versão diferente.
- Proposta temporal registrada como pendente de produto: um lembrete atrasado só pode ser enviado antes do início efetivo da reserva; se a reserva já começou, fica obsoleto. Uma reserva criada depois de `due_at` fica imediatamente elegível se ainda não começou. Não se cria tolerância após o início.
- Proposta de política registrada como pendente: offset é materializado em `due_at` ao criar a intenção; mudança posterior não altera intenção persistida, e nova versão confirmada/remarcada usa o offset lido ao processar seu evento. A especificação não resolve a retroatividade nem o efeito de atraso do consumidor antes da criação do intent; ver `OPEN-QUESTIONS.md`.
- Sem transport configurado, worker não reivindica nem consome tentativas; adapters controlados não provam entrega externa.
- Um transporte já em voo não pode ser desfeito por remarcação/cancelamento; a garantia cobre preflight/claim antes do início, não retractação externa.

## Estratégia e limites transacionais

1. Criar intenção de lembrete dentro da transação de `processed_event`, derivando tenant, cliente, estado, versão, início e política por portas server-side.
2. Invalidar intenções `PENDING`/`RETRY`/`CLAIMED` de versões anteriores em remarcação e de todas as versões em transição terminal; invalidar claim impede início posterior, mas não cancela chamada já em voo.
3. Calcular `due_at = starts_at - reminderOffsetHours` como duração real de instantes, armazenar UTC e manter o timezone do agendamento apenas para exibição/contexto.
4. Revalidar `CONFIRMED`, `appointment_version`, `starts_at > Clock.instant()` e ownership/lease imediatamente antes do transporte; não retry automático em `OUTCOME_UNKNOWN`.
5. Atualizar métricas sem cardinalidade de tenant, reserva, telefone ou provedor.

## Validação

| Critério | Comando/procedimento | Resultado observado |
|---|---|---|
| Regras temporais/DST | `ReminderScheduleTest`, `Clock.fixed`, offsets válidos e zonas `America/New_York` nas transições de outono/primavera | 4 testes unitários passaram no gate Java 21 |
| Persistência e dedupe | `NotificationPipelineIT` com PostgreSQL 16.13/Testcontainers; confirmação origina intenção imediata + lembrete na mesma outbox, duplicatas e escopo lógico | 25/25 integrações passam; confirmação remarcada gera nova versão e obsoleta a anterior |
| Eventos terminais/ordem | Confirmação atrasada com reserva já v2, remarcação v2, cancelamento, conclusão e falta | Eventos terminais invalidam; evento histórico não agenda versão antiga |
| Worker e corridas | Latches e workers concorrentes; alterações antes/depois do transporte; reivindicação individual, fencing e `Clock` | Um worker envia uma vez; claim/estado são revalidados antes do transporte; mensagem em voo não é retraída |
| Sem fornecedor e métricas | Intent due vencida, backlog/overdue e worker sem transport | Permanência em `PENDING` sem tentativa; contadores/idade consultáveis sem PII |
| Migration | Banco limpo V1–V24, upgrade V23→V24, `validate` e rerun; grants runtime | `TenantPersistenceIT` 23/23 cobre V24 e privilégios; V1–V23 preservadas. Falha inicial por schema hardcoded foi corrigida antes da validação final |
| Gate integral local | commit `59966fa`; `cd backend && env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` | `BUILD SUCCESS`, 77 unitários/arquiteturais e 194 integrações PostgreSQL 16.13/Testcontainers; `NotificationPipelineIT` 25/25 |
| E2E existente | App CI no SHA final | E2E OIDC/Keycloak existente não representa entrega de lembrete; sem E2E de fornecedor ou UI nova |
| CI remoto | App CI e CodeQL push/PR no SHA final, jobs/check-runs concluídos | Pendente publicação |

## Progresso

- [x] PR #18 integrado por merge commit; App CI e CodeQL pós-merge concluídos com sucesso.
- [x] Implementação funcional e migration V24; V1–V23 imutáveis.
- [x] Validação de backend/PostgreSQL no commit `d63b3eb`; revisão final sequencial. Revisores independentes indisponíveis por limite de threads.
- [x] Regressão de teste corrigida após App CI push no head `2017f3c` observar total global 2 em vez de 1; a causa era evento pendente alheio no batch global. A assertion agora verifica receipts do tenant sem relaxar asserções de intent/estado; suíte integral repetida com sucesso.
- [x] Branch publicada e PR #19 aberto. No head `542c8ef`, App CI/CodeQL push/PR terminaram com todos os jobs em sucesso; E2E OIDC existente passou, sem provar entrega de lembrete.
- [ ] Aguardar checks do commit documental subsequente; revisão humana permanece conforme o processo do PR.
- [ ] Homologação/fornecedor real permanecem fora do ambiente autorizado e pendentes.

## Encerramento ou retomada

- AGD-031 permanece parcial sem decisão das regras temporais/política, fornecedor real, template, segredos gerenciados e homologação. A falha inicial de migração em schema isolado foi causada pela qualificação fixa `agendamento`; a V24 foi corrigida para usar o schema Flyway corrente e a suíte final passou.
- Não alegar entrega WhatsApp, exactly-once externo, cancelamento de mensagem em voo ou conclusão de AGD-032/033.
