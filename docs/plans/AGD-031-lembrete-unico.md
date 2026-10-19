# AGD-031 — Lembrete único

## Identificação e objetivo

- Implementar intenção persistida de um único lembrete por reserva/versão, integrada ao pipeline AGD-030.
- Referências: documento 01, seção “Mensagens do MVP”; documento 02, regras de reserva e eventos; documento 03, envelope de outbox e jobs; documento 06, AGD-031 e E2E-05.
- Base: PR #18 integrado em `main` pelo merge `ce417455c058a13a3dc3113d89729f3d9d0645af`; branch `agd-031-lembrete-unico`; follow-up corretivo do PR #19 parte do head `83298afc1e93199f928bf6424ac981ec009c8dd1`.

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
- V24 habilita `APPOINTMENT_REMINDER`, preserva `due_at` separado de `next_attempt_at` e permite que uma confirmação produza notificação imediata e lembrete da mesma origem. O follow-up P2 usa V25 para separar elegibilidade do resultado de transporte; V1–V24 permanecem imutáveis.
- O consumo precisa ser idempotente e recuperar eventos antigos por consumidor próprio; processar versão atual, nunca enviar conteúdo de evento histórico para uma reserva em versão diferente.
- Decisão de produto aprovada nesta instrução (19/10/2026): política ausente não cria lembrete nem inventa offset; “padrão ligado” exige política explicitamente configurada.
- Decisão temporal aprovada na mesma instrução: job atrasado é elegível somente com reserva `CONFIRMED`, versão corrente e início estritamente futuro; na igualdade ou depois do início torna-se obsoleto, sem tolerância. Reserva cujo evento é consumido após `due_at` pode ser enviada imediatamente se ainda futura e passar pelas demais validações.
- `due_at` é materializado com o offset vigente no consumo do evento. Mudança posterior de política não recalcula intent já criada. Evento não consumido e nova versão após remarcação usam a política vigente quando processados.
- Sem transport configurado, worker não reivindica nem consome tentativas; adapters controlados não provam entrega externa.
- Um transporte já em voo não pode ser desfeito por remarcação/cancelamento; a garantia cobre preflight/claim antes do início, não retractação externa.

## Estratégia e limites transacionais

1. Criar intenção de lembrete dentro da transação de `processed_event`, derivando tenant, cliente, estado, versão, início e política por portas server-side.
2. Em remarcação, invalidar versões anteriores; em transição terminal, invalidar todas. `PENDING`/`RETRY` tornam-se `OBSOLETE`; um `CLAIMED` recebe `reminder_eligible=false` preservando claim/lease/tentativa. A autorização final impede início posterior. Uma chamada já iniciada conserva seu resultado (aceite/referência ou `OUTCOME_UNKNOWN`) e não ganha retry automático.
3. Calcular `due_at = starts_at - reminderOffsetHours` como duração real de instantes, armazenar UTC e manter o timezone do agendamento apenas para exibição/contexto.
4. Revalidar `CONFIRMED`, `appointment_version`, `starts_at > Clock.instant()` e ownership/lease imediatamente antes do transporte; não retry automático em `OUTCOME_UNKNOWN`.
5. Atualizar métricas sem cardinalidade de tenant, reserva, telefone ou provedor.

## Validação

| Critério | Comando/procedimento | Resultado observado |
|---|---|---|
| Regras temporais/DST | `ReminderScheduleTest`, `Clock.fixed`, offsets válidos e zonas `America/New_York` nas transições de outono/primavera | 4 testes unitários passaram no gate Java 21 |
| Persistência e dedupe | `NotificationPipelineIT` com PostgreSQL 16.13/Testcontainers; confirmação origina intenção imediata + lembrete na mesma outbox, duplicatas e escopo lógico | 27/27 integrações passam; confirmação remarcada gera nova versão e obsoleta a anterior |
| Eventos terminais/ordem | Confirmação atrasada com reserva já v2, remarcação v2, cancelamento, conclusão e falta | Eventos terminais invalidam; evento histórico não agenda versão antiga |
| Worker e corridas | Latches e workers concorrentes; alterações antes/depois do transporte; reivindicação individual, fencing e `Clock` | Um worker envia uma vez; claim/estado são revalidados antes do transporte; mensagem em voo não é retraída |
| Sem fornecedor e métricas | Intent due vencida, backlog/overdue e worker sem transport | Permanência em `PENDING` sem tentativa; contadores/idade consultáveis sem PII |
| Migration | Banco limpo V1–V25, upgrade V23→V25 e V24→V25, `validate` e rerun; grants runtime | `TenantPersistenceIT` verifica V25, privilégios runtime e rerun em PostgreSQL; V1–V24 preservadas. V25 não altera migrations aplicadas |
| Gate integral local | commit `59966fa`; `cd backend && env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` | `BUILD SUCCESS`, 77 unitários/arquiteturais e 194 integrações PostgreSQL 16.13/Testcontainers; `NotificationPipelineIT` 25/25 |
| E2E existente | App CI no SHA final | E2E OIDC/Keycloak existente não representa entrega de lembrete; sem E2E de fornecedor ou UI nova |
| CI remoto | App CI e CodeQL push/PR no SHA final, jobs/check-runs concluídos | No head `2f4d73e85d636b1312a9b0b6eb13260c2690884b`, App CI [push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39321817347)/[PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39321824695) e CodeQL [push](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39321817300)/[PR](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39321824674) tiveram workflows e todos os jobs/check-runs concluídos com sucesso; E2E OIDC passou, sem provar envio de lembrete |

## Progresso

- [x] PR #18 integrado por merge commit; App CI e CodeQL pós-merge concluídos com sucesso.
- [x] Implementação funcional e migration V24; V1–V23 imutáveis.
- [x] Validação de backend/PostgreSQL no commit `d63b3eb`; revisão final sequencial. Revisores independentes indisponíveis por limite de threads.
- [x] Regressão de teste corrigida após App CI push no head `2017f3c` observar total global 2 em vez de 1; a causa era evento pendente alheio no batch global. A assertion agora verifica receipts do tenant sem relaxar asserções de intent/estado; suíte integral repetida com sucesso.
- [x] Branch publicada e PR #19 aberto. No head `542c8ef`, App CI/CodeQL push/PR terminaram com todos os jobs em sucesso; E2E OIDC existente passou, sem provar entrega de lembrete.
- [x] Head documental `09b74b5`: App CI/CodeQL push e PR, com todos os jobs concluídos com sucesso; E2E OIDC passou.
- [ ] Revisão humana permanece conforme o processo do PR; homologação/fornecedor real seguem pendentes.
- [ ] Homologação/fornecedor real permanecem fora do ambiente autorizado e pendentes.

## Encerramento ou retomada

- As regras de produto temporais/política foram aprovadas em 19/10/2026 e não permanecem pendentes. AGD-031 continua parcial por fornecedor/transport real, template aprovado, segredos gerenciados e homologação. A falha inicial de migração em schema isolado foi causada pela qualificação fixa `agendamento`; a V24 foi corrigida para usar o schema Flyway corrente e a suíte final passou.
- Não alegar entrega WhatsApp, exactly-once externo, cancelamento de mensagem em voo ou conclusão de AGD-032/033.

## Follow-up P2 — invalidação durante transporte em voo

- `PostgresNotificationRepository.obsoleteReminders` atualiza a elegibilidade sem apagar o claim/lease nem reescrever a tentativa `STARTED`. `claimDue` e `authorizeSend` filtram lembretes inelegíveis. Aceite/referência do envio já iniciado continua sob fencing original; a correlação de evidências usa o lock transacional compartilhado do ADR-0020. Resultado ambíguo continua `OUTCOME_UNKNOWN` e não é reprocessado automaticamente.
- Regressões PostgreSQL sincronizadas: (a) cancelar/processar evento durante `send` preso, depois aceitar com referência e admitir evidência autenticada; evidência correlaciona ao mesmo lembrete e deduplica; (b) remarcar/processar evento durante `send`, depois retornar AMBIGUOUS; tentativa e intent preservam `OUTCOME_UNKNOWN`, sem retry; (c) cancelar na janela de preflight antes da autorização final; zero chamadas externas.
- Nenhum lock/transação permanece aberto durante `send`. Não é possível retrair efeito externo após início da chamada; V25 preserva seu resultado em vez de mascará-lo como obsoleto. Não há alteração de endpoint/OpenAPI; o campo é estado interno persistido.
- Resultado local no commit de código `5da586ce4ca01f3834e2f897b59db1dae10386a0`: `NotificationPipelineIT` 26/26, `TenantPersistenceIT` 24/24 e `spotless:check verify` em JDK 21 com 77 unitários/arquiteturais + 196 integrações PostgreSQL 16.13/Testcontainers. Instalação limpa, upgrade V24→V25, validate/rerun e grants passaram. App CI/CodeQL push/PR do head documental final ainda aguardam publicação; AGD-031 permanece parcial.
- No head `2f4d73e85d636b1312a9b0b6eb13260c2690884b`, App CI/CodeQL push e PR concluíram todos os jobs com sucesso; App CI executou o E2E OIDC/Keycloak existente, que não comprova entrega por fornecedor. AGD-031 permanece parcial.

## Decisões temporais fechadas — validação corretiva do PR #19

- O head remoto `4e27ff213f1aa3a3a32478d46781a65cf3279728` foi confirmado antes da alteração. Foram atualizados os documentos canônicos, ADR-0021, OpenAPI e OPEN-QUESTIONS; as quatro regras aprovadas em 19/10/2026 não são atribuídas retroativamente à especificação anterior.
- Regressões em `NotificationPipelineIT`: evento confirmado consumido depois de `due_at` cria intent vencida que é aceita quando a reserva segue no futuro; alteração do offset de 6h para 12h preserva o `due_at` da intent antiga e usa 12h para a nova versão. O teste de borda no início continua rejeitando igualdade e depois.
- Validação local executada: `cd backend && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH && ./mvnw -B -ntp -Dtest=ReminderScheduleTest -Dit.test=NotificationPipelineIT verify` — `BUILD SUCCESS`, 4 unitários e 27 integrações com PostgreSQL 16.13/Testcontainers; V1–V25 aplicadas em banco real.
- A primeira execução no sandbox não acessou o socket Docker (`Operation not permitted`); a repetição autorizada fora do sandbox executou os mesmos testes e passou. Isso é limitação de permissão local, não falha do código.
- Revisão de código e requisitos foi sequencial. Revisores independentes foram tentados, mas o ambiente recusou novas threads; não alegar revisão independente ou aprovação humana.
- Antes desta atualização, o head `4e27ff2` tinha App CI/CodeQL push e PR com todos os jobs concluídos com sucesso (runs descritos no PR). Estes checks cobrem o head anterior, não o delta deste follow-up; novo SHA precisa de checks próprios antes do merge.
