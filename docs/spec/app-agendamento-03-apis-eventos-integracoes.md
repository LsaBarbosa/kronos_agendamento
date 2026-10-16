# 03 — APIs, eventos e integrações

## 1. Convenções da API

### Base

- REST sobre HTTPS.
- JSON UTF-8.
- Prefixo versionado: `/api/v1`.
- Timestamps em ISO 8601 com offset; instantes canônicos persistidos em UTC.
- Identificadores opacos, preferencialmente UUIDv7/ULID ou UUID aleatório sem informação de negócio.
- Paginação por cursor em coleções potencialmente grandes.
- `traceId` em toda resposta de erro e cabeçalho de correlação.

### Autorização

| Superfície | Autoridade |
|---|---|
| `/public/{slug}/...` | slug resolve tenant; somente dados publicáveis |
| `/client/...` | sessão curta emitida após código WhatsApp e vinculada a tenant + telefone |
| `/admin/...` | sessão autenticada do administrador, tenant derivado do principal |
| `/webhooks/...` | assinatura do provedor, allowlist/replay protection quando disponível |
| `/internal/...` | identidade de workload; nunca exposta à internet pública |

O servidor ignora ou rejeita `tenantId` enviado como tentativa de escolher outro tenant em rotas autenticadas.

### Idempotência

Exigir `Idempotency-Key` em comandos que criam ou podem repetir efeito:

- criar ou remarcar reserva;
- criar bloqueio/exceção;
- solicitar exportação;
- reativar/cancelar assinatura;
- comandos administrativos corretivos;
- chamadas do adapter a provedores quando eles suportarem chave equivalente.

A chave é vinculada ao tenant, escopo do comando e digest canônico. Reuso com conteúdo diferente retorna conflito.

### Concorrência otimista

Em recursos mutáveis, devolver `ETag` ou `version`. Atualizações enviam `If-Match` ou `expectedVersion`. Divergência retorna `409 VERSION_CONFLICT` sem aplicar mutação parcial.

## 2. Envelope de erro

```json
{
  "error": {
    "code": "SLOT_NO_LONGER_AVAILABLE",
    "message": "Este horário acabou de ser reservado.",
    "traceId": "01J...",
    "retryable": true,
    "details": {
      "refreshAvailability": true
    }
  }
}
```

Regras:

- `message` é segura para o usuário; detalhes internos ficam nos logs.
- `code` é estável dentro da versão da API.
- `retryable=true` não autoriza repetir sem a mesma chave de idempotência.
- Validação usa lista de campos sem ecoar segredos.

### Códigos principais

| HTTP | Código | Significado |
|---|---|---|
| 400 | `VALIDATION_ERROR` | entrada inválida |
| 400 | `INVALID_LOCAL_TIME` | horário local inexistente/ambíguo ou offset inválido na zona IANA do tenant |
| 401 | `AUTHENTICATION_REQUIRED` | credencial ausente/inválida |
| 403 | `ACTION_NOT_ALLOWED` | ator autenticado sem permissão |
| 404 | `RESOURCE_NOT_FOUND` | recurso inexistente ou não revelável |
| 409 | `SLOT_NO_LONGER_AVAILABLE` | intervalo ocupado no commit |
| 409 | `VERSION_CONFLICT` | recurso mudou desde a leitura |
| 409 | `IDEMPOTENCY_KEY_REUSED` | mesma chave com digest diferente |
| 409 | `OPERATION_IN_PROGRESS` | comando original ainda em andamento |
| 409 | `INVALID_STATE_TRANSITION` | estado atual não aceita o comando |
| 422 | `BOOKING_POLICY_VIOLATION` | antecedência, horizonte ou grade inválida |
| 422 | `CLIENT_SELF_BOOKING_BLOCKED` | cliente não pode se autoagendar |
| 423 | `TENANT_SUSPENDED` | novas reservas fechadas por assinatura |
| 429 | `RATE_LIMITED` | limite de uso/abuso excedido |
| 503 | `DEPENDENCY_UNAVAILABLE` | dependência necessária indisponível |
| 202 | `OPERATION_PENDING` | aceito para conclusão assíncrona |

Quando o resultado de efeito externo for genuinamente ambíguo, a API não deve devolver falha definitiva que incentive repetição insegura. Retorne a operação consultável e estado `OUTCOME_UNKNOWN`/`RECONCILING` conforme o contrato administrativo.

## 3. API pública

### Negócio e catálogo

| Método | Endpoint | Descrição |
|---|---|---|
| GET | `/api/v1/public/{slug}` | dados publicáveis do negócio e políticas de exibição |
| GET | `/api/v1/public/{slug}/services` | serviços ativos |
| GET | `/api/v1/public/{slug}/services/{serviceId}` | detalhes de um serviço ativo |

Resposta pública não inclui configuração interna, contagem de faltas, assinatura, IDs de provedor ou dados de outros clientes.

### Disponibilidade

`GET /api/v1/public/{slug}/availability?serviceId={id}&from={date}&to={date}`

Regras:

- Datas são locais ao tenant; resposta inclui `timeZone`.
- Intervalo solicitado é limitado ao horizonte e a um tamanho máximo, por exemplo 31 dias.
- Cache pode ser usado com TTL curto e invalidação por mudança relevante.
- Resultado é indicativo até o commit.

Exemplo:

```json
{
  "serviceId": "svc_01J...",
  "timeZone": "America/Sao_Paulo",
  "generatedAt": "2026-08-17T15:00:00Z",
  "days": [
    {
      "date": "2026-08-20",
      "slots": [
        {
          "startsAt": "2026-08-20T09:00:00-03:00",
          "serviceEndsAt": "2026-08-20T10:00:00-03:00"
        }
      ]
    }
  ]
}
```

### Criar reserva

`POST /api/v1/public/{slug}/appointments`

Cabeçalhos:

- `Idempotency-Key`: obrigatório.
- `X-Correlation-Id`: opcional; validado e limitado.

Pedido:

```json
{
  "serviceId": "svc_01J...",
  "startsAt": "2026-08-20T09:00:00-03:00",
  "client": {
    "name": "Ana Silva",
    "phone": "+5511999999999"
  },
  "privacyNoticeVersion": "2026-08-01"
}
```

Resposta `201`:

```json
{
  "appointmentId": "apt_01J...",
  "operationId": "op_01J...",
  "status": "CONFIRMED",
  "startsAt": "2026-08-20T09:00:00-03:00",
  "serviceEndsAt": "2026-08-20T10:00:00-03:00",
  "timeZone": "America/Sao_Paulo",
  "access": {
    "verificationRequired": true,
    "channel": "WHATSAPP"
  }
}
```

Nunca devolver se o telefone já existe, está bloqueado ou possui outras reservas além da mensagem estritamente necessária ao fluxo atual.

## 4. API de acesso do cliente

### Solicitar código

`POST /api/v1/public/{slug}/access-challenges`

```json
{
  "phone": "+5511999999999"
}
```

Resposta neutra `202`, exista ou não vínculo elegível:

```json
{
  "challengeAccepted": true,
  "expiresInSeconds": 300
}
```

O `challengeId` pode ser devolvido quando não facilitar enumeração; alternativamente, usar cookie seguro/identificador opaco.

### Validar código

`POST /api/v1/public/{slug}/access-challenges/{challengeId}/verify`

```json
{
  "code": "123456"
}
```

Resposta cria sessão limitada via cookie `HttpOnly`, `Secure`, `SameSite` adequado ou token de curta duração protegido contra exfiltração.

### Reservas do cliente

| Método | Endpoint | Descrição |
|---|---|---|
| GET | `/api/v1/client/appointments?status=future&cursor=...` | lista reservas daquele tenant/telefone |
| GET | `/api/v1/client/appointments/{appointmentId}` | detalhes próprios |
| POST | `/api/v1/client/appointments/{appointmentId}/cancel` | cancela dentro da política |
| POST | `/api/v1/client/session/logout` | encerra sessão |

Cancelamento exige `Idempotency-Key` e versão esperada. Resposta repetida do mesmo comando é estável.

## 5. API administrativa

### Tenant e configuração

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/tenant` | configuração e onboarding |
| PATCH | `/api/v1/admin/tenant` | nome, slug elegível, timezone e contato |
| GET | `/api/v1/admin/availability-policy` | política atual |
| PUT | `/api/v1/admin/availability-policy` | atualiza política por CAS |

Mudança de timezone exige aviso de alto impacto e política explícita para reservas futuras; recomendação: bloquear alteração enquanto existirem reservas futuras, salvo migração guiada.

### Serviços

| Método | Endpoint |
|---|---|
| GET | `/api/v1/admin/services` |
| POST | `/api/v1/admin/services` |
| GET | `/api/v1/admin/services/{serviceId}` |
| PATCH | `/api/v1/admin/services/{serviceId}` |
| POST | `/api/v1/admin/services/{serviceId}/activate` |
| POST | `/api/v1/admin/services/{serviceId}/deactivate` |

### Grade, exceções e bloqueios

| Método | Endpoint | Observação |
|---|---|---|
| GET | `/api/v1/admin/schedule/weekly-periods` | grade corrente |
| PUT | `/api/v1/admin/schedule/weekly-periods` | substituição atômica por versão |
| GET | `/api/v1/admin/schedule/exceptions?from=&to=` | exceções no período |
| POST | `/api/v1/admin/schedule/exceptions` | cria exceção |
| PATCH | `/api/v1/admin/schedule/exceptions/{id}` | altera por CAS |
| DELETE | `/api/v1/admin/schedule/exceptions/{id}` | remove por CAS |
| GET | `/api/v1/admin/blocked-periods?from=&to=` | bloqueios |
| POST | `/api/v1/admin/blocked-periods` | cria bloqueio |
| DELETE | `/api/v1/admin/blocked-periods/{id}` | cancela/remove conforme história |

Preferir cancelamento lógico para objetos que já produziram efeitos auditáveis.

#### Contrato administrativo de exceções e bloqueios — AGD-012

- Todas as rotas derivam tenant, ator e timezone da associação administrativa ativa; payload não aceita `tenantId` nem `timeZone` como autoridade. Leituras e replay revalidam a associação atual. CSRF é obrigatório em comandos mutáveis; objetos de outro tenant e inexistentes têm resposta não reveladora.
- `GET /admin/schedule/exceptions?from=YYYY-MM-DD&to=YYYY-MM-DD` usa datas locais inclusivas, exige `from <= to` e limita a janela a 366 datas. Retorna `{from,to,timeZone,exceptions}`; uma data sem exceção não aparece na lista. Cada item contém `exceptionId`, `localDate`, `kind` (`CLOSED` ou `SPECIAL`), `periods`, `reason` opcional e `version`.
- `POST /admin/schedule/exceptions` cria versão 1 e exige `Idempotency-Key`; recebe `{localDate,kind,periods,reason?}`. `CLOSED` exige `periods=[]`; `SPECIAL` exige períodos não vazios. `PATCH /admin/schedule/exceptions/{exceptionId}` substitui atomicamente a configuração e exige `expectedVersion` e chave idempotente; pode alterar a data se não conflitar com outra exceção do tenant. `DELETE` exige body `{expectedVersion}` e chave idempotente; a remoção física do registro faz a grade semanal voltar a valer, preservando auditoria e evento.
- Os limites de períodos especiais são `HH:mm` no dia local, múltiplos de 15, `[start,end)`, start<end, adjacência permitida, overlap rejeitado, sem meia-noite. `localStartOffset` e `localEndOffset` são opcionais e obrigatórios somente para limites locais ambíguos; cada valor precisa ser válido na zona IANA do tenant para a data/hora. Os offsets escolhidos são persistidos. O digest canônico inclui os offsets, alvo, versão, data, tipo e conteúdo; comandos sem offsets preservam a forma canônica v1.
- `GET /admin/blocked-periods?from=YYYY-MM-DD&to=YYYY-MM-DD` usa janela local inclusiva de no máximo 366 datas e lista todos os bloqueios que intersectem o intervalo, inclusive cancelados, para preservar histórico. Cada item inclui `blockId`, instantes UTC `startsAt`/`endsAt`, `timeZone`, representação local, motivo de criação, status, versão e, se cancelado, instante/motivo de cancelamento.
- `POST /admin/blocked-periods` exige `Idempotency-Key` e recebe `{startsAt,endsAt,reason}`. Os limites aceitam data/hora local ISO sem offset, resolvida somente quando há exatamente um offset válido, ou ISO 8601 com offset explícito que precisa ser válido na zona IANA do tenant. Ambos devem ter minuto múltiplo de 15, sem segundos/fração; os instantes resolvidos precisam satisfazer `start < end`. Bloqueios podem atravessar data e mudança de offset.
- `DELETE /admin/blocked-periods/{blockId}` é cancelamento lógico e exige body `{expectedVersion,reason}` e `Idempotency-Key`. Motivo não vazio é obrigatório tanto na criação quanto no cancelamento. Versão incrementa uma vez; motivo de criação e cancelamento permanecem separados e não são incluídos em auditoria/outbox.
- Atualizações usam CAS e comandos persistem mutação, resposta idempotente, auditoria de sucesso e `ScheduleChanged` na mesma transação. Reuso da chave com digest divergente retorna `409 IDEMPOTENCY_KEY_REUSED`; versão divergente retorna `409 VERSION_CONFLICT`; inexistente/alheio retorna o erro não revelador previsto pelo contrato.
- Consulta de bloqueio usa interseção semiaberta com a janela em instantes derivada das datas na zona do tenant; duração real decorre dos instantes UTC. Limites locais inexistentes são rejeitados. Sem offset, limites ambíguos são rejeitados; offset explícito desambigua somente quando válido na zona.
- O evento `ScheduleChanged` continua com payload allowlisted `{tenantId,version}`; não inclui motivo, horário ou request. Agendamentos e cache real ainda não existem; integração concorrente com reservas depende de AGD-015 e invalidação de cache de AGD-013. O evento transacional não é prova de cache invalidado.

#### Contrato administrativo da grade e das políticas — AGD-011

- `GET` da grade devolve `{configured, timeZone, version, periods}`. `configured=false` e `version=0` representam ausência; `configured=true` com `periods=[]` significa semana explicitamente fechada.
- `PUT` da grade substitui todos os períodos e recebe `{expectedVersion, periods}` mais `Idempotency-Key`. `weekday` usa ISO em maiúsculas (`MONDAY`–`SUNDAY`); limites locais usam `HH:mm`; `timeZone` é sempre obtido do tenant autenticado. O array ordenado por dia e hora é a forma canônica do digest, de modo que permutações equivalentes não alteram a identidade lógica.
- `GET` da política devolve `{configured, version, slotStepMinutes, minimumNoticeHours, bookingHorizonDays, cancellationCutoffHours, reminderOffsetHours}`. Quando `configured=false`, valores são `null`, não defaults. `PUT` substitui os cinco valores em conjunto, exige `expectedVersion` e `Idempotency-Key`.
- `version` é independente para grade e política. Versão inicial ausente é `0`; cada alteração efetiva confirmada incrementa uma vez. Replay exige autenticação/vínculo atual e retorna o resultado original sem incrementar versão nem repetir auditoria/outbox.
- Períodos da mesma semana não podem se sobrepor; limites adjacentes seguem intervalos semiabertos `[início,fim)`. `localStart` e `localEnd` usam `HH:mm`, com minutos em `00`, `15`, `30` ou `45`; essa regra independe de `slotStepMinutes` (inclusive 30 ou 60). O início deve ser estritamente anterior ao fim; cruzar meia-noite é rejeitado neste MVP.
- A recorrência permanece em horário local do tenant e timezone IANA; não converter a grade em timestamps nem offsets UTC fixos. A configuração ausente continua diferente de uma semana configurada vazia.
- Rotas e schemas OpenAPI versionados estão em `docs/openapi/admin-availability.yaml`. CSRF usa o header publicado por `/api/v1/csrf`; a configuração atual usa `X-CSRF-TOKEN`.

### Agenda e reservas

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/appointments?from=&to=&status=&serviceId=` | agenda filtrada |
| GET | `/api/v1/admin/appointments/{id}` | detalhes e história |
| POST | `/api/v1/admin/appointments` | reserva administrativa/encaixe |
| POST | `/api/v1/admin/appointments/{id}/reschedule` | remarca atomicamente |
| POST | `/api/v1/admin/appointments/{id}/cancel` | cancelamento administrativo |
| POST | `/api/v1/admin/appointments/{id}/complete` | conclui atendimento |
| POST | `/api/v1/admin/appointments/{id}/mark-no-show` | registra falta |
| POST | `/api/v1/admin/appointments/{id}/correct-outcome` | correção excepcional |
| GET | `/api/v1/admin/appointments/{id}/history` | linha do tempo |

Comandos de encaixe incluem:

```json
{
  "serviceId": "svc_01J...",
  "startsAt": "2026-08-20T08:00:00-03:00",
  "client": { "name": "Ana Silva", "phone": "+5511999999999" },
  "overridePolicies": ["OUTSIDE_WEEKLY_SCHEDULE", "MIN_NOTICE"],
  "reason": "Solicitação atendida por telefone",
  "expectedScheduleVersion": 42
}
```

`OVERLAP` não é uma política ignorável no MVP.

### Clientes e restrições

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/clients?query=&cursor=` | busca limitada |
| GET | `/api/v1/admin/clients/{id}` | perfil local e faltas |
| GET | `/api/v1/admin/clients/{id}/no-shows` | eventos na janela e história |
| POST | `/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift` | desbloqueio com motivo |

### Assinatura

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/subscription` | plano, status e datas |
| POST | `/api/v1/admin/subscription/checkout` | inicia autorização/cobrança |
| POST | `/api/v1/admin/subscription/reactivate` | inicia reativação idempotente |
| POST | `/api/v1/admin/subscription/cancel` | solicita cancelamento |
| POST | `/api/v1/admin/subscription/annual-renewal-consent` | registra autorização válida |
| GET | `/api/v1/admin/subscription/payment-attempts` | visão sanitizada |

Não aceitar `paymentConfirmed=true` do frontend. Confirmação vem do webhook autenticado ou consulta direta ao provedor.

### Exportações

| Método | Endpoint | Uso |
|---|---|---|
| POST | `/api/v1/admin/exports` | solicita snapshot |
| GET | `/api/v1/admin/exports/{id}` | acompanha estado |
| POST | `/api/v1/admin/exports/{id}/download-token` | autoriza download curto |
| DELETE | `/api/v1/admin/exports/{id}` | expira antecipadamente quando permitido |

Exemplo de solicitação:

```json
{
  "format": "CSV",
  "category": "APPOINTMENTS",
  "filters": {
    "from": "2026-01-01",
    "to": "2026-06-30",
    "statuses": ["COMPLETED", "NO_SHOW"]
  }
}
```

## 6. API de consulta de operações

Para comandos que podem ficar em andamento:

`GET /api/v1/admin/operations/{operationId}`

```json
{
  "operationId": "op_01J...",
  "kind": "SUBSCRIPTION_REACTIVATION",
  "state": "RECONCILING",
  "result": null,
  "createdAt": "2026-08-17T15:00:00Z",
  "updatedAt": "2026-08-17T15:00:30Z"
}
```

Estados externos propostos: `IN_PROGRESS`, `SUCCEEDED`, `FAILED`, `RECONCILING`, `OUTCOME_UNKNOWN`. Não expor detalhes de risco que habilitem fraude.

## 7. Eventos de domínio

Eventos são fatos no passado, imutáveis e versionados.

| Evento | Produzido quando | Consumidores principais |
|---|---|---|
| `TenantActivated` | onboarding mínimo concluído | auditoria, analytics |
| `ServiceCreated` | serviço criado | cache/disponibilidade |
| `AvailabilityPolicyChanged` | política confirmada | cache, auditoria |
| `ScheduleChanged` | grade/exceção/bloqueio mudou | cache, impacto de agenda |
| `AppointmentConfirmed` | commit da reserva | notificação, analytics |
| `AppointmentRescheduled` | troca atômica de intervalo | lembretes, notificação, analytics |
| `AppointmentCancelled` | cancelamento confirmado | lembretes, notificação, analytics |
| `AppointmentCompleted` | serviço concluído | relatórios |
| `ClientNoShowRecorded` | falta confirmada | política de restrição, analytics |
| `ClientSelfBookingBlocked` | restrição ativada | notificação administrativa, auditoria |
| `ClientSelfBookingUnblocked` | restrição levantada | auditoria |
| `NotificationRequested` | intenção criada | worker WhatsApp |
| `SubscriptionPastDue` | cobrança venceu | aviso, retries |
| `SubscriptionSuspended` | tolerância acabou | gate público, aviso |
| `SubscriptionReactivated` | pagamento confirmado | gate público, aviso |
| `ExportRequested` | snapshot aceito | worker de exportação |
| `ExportAvailable` | objeto confirmado | UI/aviso |
| `ExportExpired` | janela encerrou | limpeza |

### Envelope

```json
{
  "eventId": "evt_01J...",
  "eventType": "AppointmentConfirmed",
  "schemaVersion": 1,
  "tenantId": "ten_01J...",
  "aggregateId": "apt_01J...",
  "aggregateVersion": 1,
  "occurredAt": "2026-08-17T15:00:00Z",
  "correlationId": "cor_01J...",
  "causationId": "cmd_01J...",
  "payload": {}
}
```

Não incluir PII desnecessária. Consumidores que precisam de dados atuais consultam fonte autorizada ou recebem referência protegida.

## 8. Consumo idempotente de eventos

Cada consumidor mantém `processed_event(consumer_name, event_id)` ou uma chave de efeito mais específica na mesma transação de sua mutação. Fluxo:

1. iniciar transação;
2. tentar registrar `event_id` para o consumidor;
3. se já existir, confirmar sem novo efeito;
4. aplicar mudança local;
5. commit;
6. confirmar mensagem no broker.

Para efeito externo, usar chave idempotente do provedor e uma operação consultável. Confirmação do broker antes do efeito pode perder trabalho; confirmação depois do efeito pode repeti-lo. A idempotência resolve a repetição lógica.

## 9. Webhooks

### Endpoints

- `POST /api/v1/webhooks/payments/{provider}`
- `POST /api/v1/webhooks/messaging/{provider}`

### Pipeline de admissão

1. limitar tamanho e tempo de leitura;
2. capturar bytes necessários à verificação;
3. validar assinatura, timestamp e identificador de evento;
4. rejeitar replay fora da janela quando a semântica permitir;
5. persistir evento admitido e digest;
6. responder rapidamente;
7. processar de forma assíncrona e idempotente;
8. reconciliar por API do provedor se o conteúdo for ambíguo.

Eventos inválidos não alteram estado de negócio. Eventos duplicados retornam sucesso compatível ao provedor, mas não repetem efeito.

### Ordenação

Não assumir que webhooks chegam em ordem. Aplique fatos com:

- versão/sequência do provedor, quando autoritativa;
- consulta do estado atual no provedor para eventos incompatíveis;
- regras monotônicas e histórico;
- CAS da versão local.

Um evento antigo não deve rebaixar assinatura já reativada por fato posterior.

## 10. Integração WhatsApp

Definir uma porta interna, independente de fornecedor:

```text
sendAccessCode(command) -> providerMessageRef
sendTemplate(command)   -> providerMessageRef
queryMessage(ref)       -> deliveryEvidence
```

Requisitos do adapter:

- chave idempotente ou dedupe local por intenção;
- templates versionados;
- normalização de erros em recuperável, definitivo ou ambíguo;
- limite de throughput e backoff respeitando o provedor;
- assinatura de webhook;
- segregação de credenciais por ambiente;
- métricas de aceite, entrega e falha.

Se houver timeout após envio e o provedor não oferecer consulta/idempotência, marcar resultado ambíguo; não disparar mensagem ilimitadamente.

## 11. Integração de cobrança

Porta interna proposta:

```text
createCheckout(command)
chargeRenewal(command)
queryPayment(providerRef)
cancelSubscription(command)
```

Requisitos:

- nunca armazenar dados completos de cartão;
- preferir checkout/tokenização hospedada pelo provedor;
- usar chave idempotente permanente por cobrança lógica;
- mapear `provider_event_id` com unicidade;
- confirmar pagamento por webhook autenticado ou leitura direta autoritativa;
- tratar chargeback/estorno como fatos separados;
- agendar D0/D+3/D+6 com uma tentativa lógica por sequência;
- interromper retries assim que pagamento for confirmado.

Uma tentativa com efeito incerto não autoriza criar nova cobrança com outra identidade. Primeiro reconciliar a mesma cobrança lógica.

## 12. Jobs agendados

| Job | Frequência/acionamento | Proteção |
|---|---|---|
| lembretes | consulta contínua por `due_at` | claim com lease, dedupe e verificação da versão da reserva |
| pagamento D0/D+3/D+6 | scheduler por `next_attempt_at` | chave por assinatura + vencimento + sequência |
| suspensão | após `grace_end` | CAS da assinatura e confirmação de ausência de pagamento |
| expiração de código | lazy + limpeza periódica | estado terminal idempotente |
| exportação | fila | um ativo por tenant, lease e retries |
| expiração de exportação | por `expires_at` | fechamento lógico antes de apagar objeto |
| publicação outbox | contínua | `SKIP LOCKED`, retry e consumidores idempotentes |
| reconciliação externa | periódico e sob exceção | limites, cursor e operation ID |
| retenção/exclusão | política agendada | legal hold e ledger/tombstone |

Nenhum job depende de executar exatamente no segundo previsto. Cada execução reavalia pré-condições e é segura quando repetida.

## 13. Contratos frontend–backend

- Frontend nunca calcula disponibilidade autoritativa.
- Datas locais exibidas sempre usam o timezone devolvido pela API.
- Botão de confirmação fica protegido contra múltiplos cliques, mas a segurança real é a idempotência do servidor.
- Em timeout de criação, frontend consulta/reenvia com a mesma chave; não gera nova chave automaticamente.
- Em `SLOT_NO_LONGER_AVAILABLE`, atualiza a lista e preserva dados do cliente.
- Em `VERSION_CONFLICT`, recarrega o recurso e pede nova decisão quando a mudança for material.
- Estados assíncronos usam polling com backoff ou SSE pós-MVP; não presumir sucesso pelo `202`.

## 14. Versionamento e compatibilidade

- Mudança compatível adiciona campo opcional; clientes ignoram campos desconhecidos.
- Remoção/renomeação exige nova versão ou período de depreciação.
- Enums desconhecidos devem gerar fallback seguro na UI, não crash nem ação automática.
- Eventos têm versionamento independente da API.
- Adapters normalizam versões de provedores e preservam o payload digest para auditoria.

## 15. Testes de contrato obrigatórios

- OpenAPI validada no CI.
- Consumer-driven contract para frontend e workers críticos.
- Fixtures de webhook autêntico, duplicado, inválido, atrasado e fora de ordem.
- Repetição da mesma chave e reuso com payload divergente.
- Teste concorrente de dois commits no mesmo slot.
- Teste de timezone/horário de verão.
- Teste de expiração e revogação de link de exportação.
- Teste de isolamento por tenant em todos os filtros e IDs.
