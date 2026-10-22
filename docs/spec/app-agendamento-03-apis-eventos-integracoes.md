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

Resposta pública só é permitida para slug explicitamente publicado e ainda elegível no gate de prontidão. A resolução de tenant é exclusivamente server-side; não publicado, suspenso, fora da prontidão e recurso alheio/inexistente seguem resposta não reveladora. Resposta não inclui configuração interna, contagem de faltas, assinatura, IDs de provedor ou dados de outros clientes.

### Disponibilidade

`GET /api/v1/public/{slug}/availability?serviceId={id}&from={date}&to={date}`

Regras:

- A operação só é liberada para slug explicitamente publicado após revalidar o gate de prontidão em cada leitura; tenant ativo, serviço ativo e agenda configurada não bastam. Falha da fonte obrigatória de ocupações não equivale a agenda vazia e deve ser respondida como indisponibilidade temporária.
- `from` e `to` são datas locais inclusivas no timezone IANA confiável do tenant; `from <= to` e `DAYS.between(from,to) + 1 <= 31`. Janela inválida/excessiva retorna `400 INVALID_AVAILABILITY_WINDOW`.
- A resposta contém `serviceId`, datas solicitadas ordenadas, `timeZone`, `generatedAt`, `configured` e slots ordenados por dia, com timestamps ISO 8601 contendo offset. Cada dia informa `explicitlyClosed`; datas fora do horizonte permanecem com lista de slots vazia.
- Um `Clock` é capturado uma vez por consulta. Antecedência usa instante decorrido e permite início exatamente no limite. Horizonte `N` conta `N` datas locais incluindo hoje: última data = hoje local + `N - 1`, inclusiva; candidatos desde o início da data seguinte são excluídos. Não converter para `N * 24` horas.
- Inícios são ancorados ao começo de cada período aplicável e avançam por `slotStepMinutes`. Filtros não alteram a âncora; períodos de `SPECIAL` têm âncoras próprias. Duração mais buffer deve caber em um só período.
- Ausência de configuração não aplica defaults e é distinguida de fechamento explícito.
- Cache pode ser usado com TTL curto e invalidação por mudança relevante.
- Resultado é indicativo até o commit; não cria hold nem garante confirmação. Ocupações reais e proteção contra corrida pertencem a AGD-015.

Exemplo de fronteira: hoje local 16/10 e horizonte 7 permitem datas 16/10 a 22/10; antecedência continua filtrando candidatos nesses dias. Uma consulta que peça até 25/10 retorna 25/10, mas sem slots após o horizonte.

### Criação pública de reserva — AGD-015

`POST /api/v1/public/{slug}/appointments`

- Exige `Idempotency-Key` e CSRF. O corpo é `{serviceId, startsAt, client:{name,phone}}`; `startsAt` é ISO 8601 com offset. Campos derivados (tenant, timezone, duração, fim, buffer, preço e disponibilidade) não são aceitos do cliente.
- O servidor resolve slug/publicação e readiness atuais, serviço ativo do tenant, telefone E.164 e um candidato de AGD-013 para a data local e offset informados. Revalida dentro da transação de confirmação; disponibilidade previamente exibida não é hold.
- Sucesso `201` retorna `{appointmentId,operationId,status,serviceName,startsAt,serviceEndsAt,timeZone}`. Não devolve nome/telefone, nem oferece consulta futura por esse telefone. `SLOT_NO_LONGER_AVAILABLE` retorna `409` com orientação para atualizar os horários.
- Mesma chave/conteúdo devolve as mesmas referências sem criar cliente, reserva, história, auditoria ou evento novamente. Digest divergente retorna `409 IDEMPOTENCY_KEY_REUSED`. Replay revalida publicação/readiness/serviço e não compara a reserva original contra a ocupação que ela própria criou.
- `AppointmentConfirmed` é gravado no outbox no commit com payload mínimo `{appointmentId,version}`; não contém PII nem afirma que WhatsApp foi enviado.
- Antes da chamada confirmadora, o frontend chama `POST /api/v1/public/{slug}/booking-intents/current` com CSRF, `Idempotency-Key`, `X-Booking-Recovery` e o mesmo corpo lógico de criação. O endpoint valida/canonicaliza e persiste apenas tenant, digest HMAC, digest da chave e da capability, referências mínimas do serviço/horário e timestamps; não persiste nome, telefone, cookies, headers ou request bruto. Sem preparo concluído, a UI não chama `/appointments`.
- O `X-Booking-Recovery` é uma capability aleatória de alta entropia gerada e persistida no browser antes do envio. No servidor somente seu SHA-256 é armazenado. Ela não aparece na URL nem logs e autoriza somente a consulta de estado/resumo mínimo da intenção para o tenant resolvido pelo slug. A capability não prova identidade do cliente nem autoriza consulta por telefone, PII, alteração ou cancelamento.
- A capability expira tecnicamente 7 dias após o prebind, prazo para retornar e reconciliar a mesma intenção após falha/reload; depois disso, a consulta é não reveladora e não autoriza criar outra intenção automaticamente. Expiração não apaga vínculo, receipt, reserva, história, auditoria ou idempotência; expurgo físico segue a política geral de retenção ainda pendente.
- `GET /api/v1/public/{slug}/booking-intents/current` recebe a capability em header e é read-only. Responde `PREPARED_UNKNOWN` sem receipt observável (não significa que uma requisição concorrente/prestes a confirmar não exista), ou `COMPLETED` com o receipt mínimo `{appointmentId,operationId,status,serviceName,startsAt,serviceEndsAt,timeZone}`. Vínculo ausente/inválido/alheio é não revelador. A resposta é `Cache-Control: no-store` e não contém nome/telefone.
- O preparo é idempotente: mesmo tenant, chave, capability e comando equivalente retorna o vínculo existente; conteúdo diferente retorna `409 IDEMPOTENCY_KEY_REUSED`. Uma capability não pode ser vinculada a outro tenant/intenção. Após reload, a UI reconsulta antes de oferecer retry; no estado incerto exige que nome/telefone sejam reintroduzidos e envia o mesmo comando/chave. Não receipt, erro de rede, `OPERATION_IN_PROGRESS` ou indisponibilidade nunca libera chave nova automaticamente. Um conflito incompatível é mostrado explicitamente.
- Digest público v2 persiste como `hmac-sha256:v2:<keyId>:<64 hex>`, usando canonicalização UTF-8 length-prefixed `appointments.public.create.canonical.v2`. A chave ativa e keyring são obrigatórios e externos à aplicação; key ids históricos precisam permanecer disponíveis para receipts replayáveis. `PUBLIC_BOOKING_DIGEST_KEY_UNAVAILABLE` é indisponibilidade segura, não `IDEMPOTENCY_KEY_REUSED`. Registros SHA-256 hex legados continuam comparáveis pela canonicalização v1 original e permanecem inalterados.
- Gate de publicação e elegibilidade são revalidados no preparo e antes de qualquer replay confirmador. Consulta de receipt previamente confirmado é somente leitura por capability/tenant e não abre a publicação. Falha ou expiração da capability não apaga reserva nem receipt idempotente.

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

`phone` aceita no máximo 32 caracteres. Campo ausente/em branco, entrada acima do limite ou número internacional inválido retorna HTTP 400 com `error.code=INVALID_PHONE`; o servidor nunca trunca o valor. Essa rejeição é definitiva e permite corrigir o campo e iniciar nova intenção. Falha de transporte ou resposta ambígua não equivale a esse erro e conserva a identidade lógica da solicitação. Violações de outros parâmetros de transporte/contrato retornam `INVALID_REQUEST`, não devem ser interpretadas pela UI como rejeição de telefone.

### Validar código

`POST /api/v1/public/{slug}/access-challenges/{challengeId}/verify`

```json
{
  "code": "123456"
}
```

Resposta cria sessão limitada via cookie `HttpOnly`, `Secure`, `SameSite` adequado ou token de curta duração protegido contra exfiltração.

O challengeId, quando retornado, é opaco e sempre consultado junto do tenant resolvido pelo slug; ele não é uma credencial de sessão. A verificação aceita somente o código no body e emite cookie de cliente separado da sessão OIDC administrativa, restrito a `/api/v1/client`. A sessão emitida persiste o vínculo ao challenge consumido. `GET /api/v1/client/session` retorna apenas estado booleano; para reconciliar uma verificação de resultado incerto, aceita `slug` e `challengeId` juntos e retorna verdadeiro somente se o cookie ativo foi emitido pelo challenge `VERIFIED` desse tenant. Nenhum telefone, código ou token é exposto; sessão antiga de outro telefone/tenant não confirma a tentativa. Se a resposta da verificação se perder, essa recuperação depende de o cookie resultante ter chegado ao navegador; a consulta não recria nem reemite um cookie perdido. Falha de transporte, HTTP 503 ou resposta inválida da consulta mantém o resultado incerto e permite repetir a recuperação com o mesmo challenge, sem aceitar `authenticated:false` como sucesso. `POST /api/v1/client/session/logout` revoga a sessão. CSRF continua ativo para mutações autenticadas por cookie. O contrato OpenAPI versionado está em `docs/openapi/public-client-access.yaml`. TTL/renovação da sessão e comprimento do OTP não estão definidos pela especificação e permanecem decisões de produto pendentes; não inferir valores das sessões administrativa ou da capability de AGD-016.

### Reservas do cliente

| Método | Endpoint | Descrição |
|---|---|---|
| GET | `/api/v1/client/appointments?status=future&cursor=...` | lista reservas próprias em páginas por cursor |
| GET | `/api/v1/client/appointments/{appointmentId}` | detalhes mínimos da reserva própria |
| POST | `/api/v1/client/appointments/{appointmentId}/cancel` | cancela reserva própria dentro da política |
| POST | `/api/v1/client/session/logout` | encerra sessão |

As rotas de reserva derivam `tenant_id` e telefone exclusivamente da sessão `AGDCLIENT` persistida, válida e não revogada. O booleano de `GET /api/v1/client/session` serve apenas para restaurar a interface; cada endpoint valida a sessão e consulta a titularidade no servidor. A titularidade é resolvida por tenant + telefone E.164 verificado; `tenantId`, telefone e `clientId` do navegador não são aceitos como autoridade. Sessão administrativa OIDC e capability AGD-016 não autorizam essas rotas.

A listagem ordena por `starts_at ASC, appointment_id ASC`, com páginas fixas de 20 itens e cursor keyset. Sem filtro, lista todas as reservas próprias. O único filtro temporal vigente é `status=future`, que inclui reservas com `starts_at` igual ou posterior ao instante capturado para a consulta; outros valores são `400 VALIDATION_ERROR`. `cursor` é o `appointmentId` marcador da página anterior; só é aceito se a reserva pertencer ao cliente/tenant autenticados, caso contrário retorna erro genérico de validação. A próxima página é indicada por `nextCursor` e `hasMore`. Todos os resultados incluem apenas serviço do snapshot, início/fim de atendimento, timezone, estado e versão; não incluem telefone/nome do cliente, buffer, auditoria ou dados administrativos.

Detalhe de ID alheio e inexistente retorna o mesmo `404 RESOURCE_NOT_FOUND`. Todas as respostas, inclusive erros de recursos privados, usam `Cache-Control: no-store`.

Cancelamento exige `Idempotency-Key` e `expectedVersion`. Só `CONFIRMED` própria pode ser cancelada; a operação usa lock tenant-first e CAS, grava estado, versão, revisão da agenda, história, auditoria (`CLIENT`/`VERIFIED_CLIENT`), receipt e `AppointmentCancelled` na mesma transação. Serviço inativo e restrição de autoagendamento não impedem acesso/cancelamento da reserva existente. O cutoff é lido da política atual configurada e permite `now <= startsAt - cancellationCutoffHours`, incluindo igualdade. Sem política configurada, a ação falha fechada; não há valor default. Depois do prazo retorna `409 CANCELLATION_WINDOW_CLOSED`, orientando contato com o estabelecimento e sem efeitos. A ocupação só deixa de bloquear após commit.

O replay exige sessão atual e vínculo de titularidade atuais antes de devolver o receipt, mas não reaplica o cutoff nem compara a versão consumida pela própria operação. Mesma chave e comando retorna o resultado original; payload divergente retorna `IDEMPOTENCY_KEY_REUSED`; operação concorrente pode retornar `OPERATION_IN_PROGRESS`. CSRF é obrigatório nas mutações por cookie. Logout revoga a sessão server-side e expira o cookie; a interface apaga os dados privados ao sair ou receber sessão encerrada. O acesso por telefone verificado não constitui identidade civil, não libera nova reserva por si só e não permite consultar reservas de terceiros.

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
| POST | `/api/v1/admin/appointments/{id}/correct-no-show` | correção específica `NO_SHOW → COMPLETED` (AGD-032) |
| GET | `/api/v1/admin/appointments/{id}/history` | linha do tempo |

#### Encerramento administrativo (AGD-023)

Os comandos são separados: `POST /api/v1/admin/appointments/{id}/cancel`, `/complete` e `/mark-no-show`. Cada um recebe `expectedVersion`, `reason` opcional (até 500 caracteres) e `Idempotency-Key`; usa escopo idempotente próprio e digest canônico versionado com tenant, ação, alvo, versão e motivo normalizado. O tenant e o administrador são derivados da associação OIDC atual. Cancelamento pelo administrador transita para `CANCELLED_BY_PROVIDER` e é registrado como ator `ADMIN/TENANT_ADMIN`, nunca como sessão verificada do cliente. Serviços inativos não impedem administrar a reserva.

Somente `CONFIRMED` pode transitar. Replays revalidam autorização atual antes de devolver o receipt original, inclusive depois da transição terminal; uma nova intenção para estado terminal recebe `409 INVALID_STATE_TRANSITION`. Estado, versão, revisão monotônica da agenda, história, auditoria allowlisted, receipt e evento previsto compartilham a transação. A ocupação de reserva cancelada deixa de contar somente após commit. Motivo livre é preservado na história autorizada, mas não é copiado para auditoria/outbox. Eventos são `AppointmentCancelled`, `AppointmentCompleted` e `ClientNoShowRecorded`, com payload limitado a `{appointmentId,version}`; falta registrada não comprova bloqueio automático, envio de notificação nem cancelamento de lembrete.

**Decisão de produto aprovada pelo usuário nesta instrução em 17/10/2026, ADR-0018:** `COMPLETED` e `NO_SHOW` somente quando `Clock.instant() >= occupiedUntil`, incluindo buffer. Exemplo: atendimento 09:00–09:30 com buffer até 09:45 só pode ser concluído/marcado como falta a partir de 09:45. O instante imediatamente anterior é rejeitado; igualdade e instantes posteriores são permitidos. Sem tolerância adicional de dez minutos. Cancelamento administrativo permanece condicionado a `CONFIRMED`, sem cutoff de cliente.

Cancelamento pelo cliente permanece sujeito ao `cancellationCutoffHours`: decisão aprovada pelo usuário nesta instrução permite `now <= startsAt - cancellationCutoffHours`, inclusive a igualdade. Não há rota mutável nesta história: identidade verificada depende de AGD-024/025. Não interpretar telefone/capability como autenticação. Lembretes/notificações efetivos dependem de AGD-030/031; contagem/restrição automática por falta depende de AGD-032/033.

#### Consulta administrativa da agenda (AGD-020)

- `from` e `to` são datas locais inclusivas no timezone IANA atual do tenant; `from` não pode ser posterior a `to`. A consulta aceita no máximo 366 datas locais inclusivas, igual ao limite das consultas administrativas de bloqueios. Esta janela não é limitada pelo horizonte de novas reservas e permite consultar histórico existente.
- `status` é opcional e aceita somente estados realmente persistidos/documentados; no estado atual, `CONFIRMED`. `serviceId` é opcional e pode localizar reservas cujo serviço foi inativado. Um filtro inválido retorna `400 VALIDATION_ERROR`; datas invertidas ou janela maior que 366 também retornam `400 VALIDATION_ERROR`.
- A listagem é ordenada por `starts_at ASC, appointment_id ASC`. `limit` default 50, intervalo 1–100; `cursor` opaco é keyset dessa ordenação e a resposta informa `nextCursor`, `hasMore` e `timeZone`. Cursor não seleciona tenant e não é credencial. Cada página é uma leitura independente; novas reservas podem aparecer somente após atualizar, sem estabilidade de snapshot entre requisições.
- A janela temporal é `[from.atStartOfDay(zone), (to + 1 dia).atStartOfDay(zone))`, convertida para instantes com regras IANA (inclusive DST). Entram reservas cuja ocupação [startsAt, occupiedUntil) intersecta a janela: `startsAt < windowEnd AND occupiedUntil > windowStart`; uma reserva iniciada antes de `from` permanece visível se seu buffer alcançar a janela.
- Lista e detalhe usam snapshot imutável da reserva: nome do serviço, duração, buffer, início/fim do atendimento, fim da ocupação e timezone. Nome do cliente é exposto apenas no contexto administrativo autorizado; telefone é omitido. Histórico vem de `appointment_history` e não expõe tabela/detalhes internos de auditoria, credenciais ou contexto OIDC. ID alheio e inexistente são indistinguíveis (`404 RESOURCE_NOT_FOUND`).
- Bloqueios são consultados pela rota administrativa existente, tenant-scoped e com a mesma janela local. Bloqueio ativo e cancelado continuam consultáveis; UI distingue estado e não esconde interseções. A ausência em uma página não prova disponibilidade.
- As rotas de agenda são somente leitura e não têm CAS. A proteção contra sobrescrita concorrente é aplicável quando histórias de mutação administrativa forem implementadas; esta história não cria comandos nem altera versões.

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

#### Reserva administrativa (AGD-021)

`POST /admin/appointments` exige sessão OIDC atual, associação ativa, CSRF e `Idempotency-Key`. O servidor deriva ator e tenant; `clientId` deve pertencer ao tenant. Alternativamente, `client: {name, phone}` cria/reutiliza cliente por telefone E.164 tenant-scoped. As duas formas são mutuamente exclusivas. A resposta contém apenas referências, snapshot do serviço e horários; não devolve telefone.

O comando usa `expectedScheduleVersion` como revisão monotônica global `schedule_revision`, retornada por `GET /admin/schedule/weekly-periods` em `scheduleRevision`. Ela não é soma nem substitui as versões independentes de grade, política, exceções e bloqueios. Durante a criação, o servidor trava a revisão em compartilhamento e lê novamente os dados; alteração concorrente causa `409 VERSION_CONFLICT`. O lock da agenda do tenant precede a revisão e a leitura/trava do serviço, com ordem compatível com reserva pública e criação de bloqueio.

A allowlist de `overridePolicies` é exatamente `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE`. Cada política só pode ser selecionada quando a avaliação atual do servidor detectar a violação; o motivo normalizado (1–500 caracteres) é obrigatório se qualquer override for usado e proibido sem override. `OUTSIDE_WEEKLY_SCHEDULE` ignora somente a grade semanal e só se aplica quando não existe exceção para a data. `CLOSED` continua fechado. `SPECIAL` substitui a grade semanal e é a configuração final: a ocupação, incluindo buffer, precisa caber integralmente em um único período especial; o override não pode contornar seus limites. `MIN_NOTICE` é independente e pode ser ignorada dentro de um período `SPECIAL`, com motivo. Horizonte, falta de configuração, serviço inativo, bloqueios, ocupações e `OVERLAP` não são ignoráveis. Políticas desconhecidas ou incompatíveis são rejeitadas. Serviço e buffer vêm do catálogo no servidor, e a ocupação continua `[startsAt, occupiedUntil)`.

Uma exceção `CLOSED` significa data sem atendimento; uma `SPECIAL` substitui a grade semanal por seus períodos. Portanto, encaixe fora da grade só é permitido sem exceção por data; `SPECIAL` aceita início compatível com sua âncora e passo, desde que serviço e buffer caibam em um período. O comando não cria, remove nem amplia exceções/grade. A decisão de produto está registrada na ADR-0016.

Reserva, história, auditoria, resultado idempotente e `AppointmentConfirmed` compartilham a transação PostgreSQL. Motivo é preservado na história administrativa; detalhes de auditoria contêm apenas políticas permitidas, indicação booleana de motivo e estado da fronteira de restrições de cliente. Outbox não recebe motivo, telefone, nome ou payload de request. `AppointmentConfirmed` solicita processamento, não comprova envio de WhatsApp. Busca mínima de clientes exige query com 2–80 caracteres, retorna até 20 IDs/nomes ativos do tenant e nunca retorna telefone.

Encaixe não ignora bloqueios nem conflito com reservas. AGD-033 fornece consulta persistida tenant-scoped de restrições: a reserva administrativa continua autorizada para cliente restrito, mas a decisão é registrada de forma minimizada. A criação pública consulta a restrição dentro do lock compartilhado por tenant antes de persistir cliente/reserva. O status legado `NOT_TRACKED` não é emitido; permanece aceito apenas nos formatos históricos de auditoria.

#### Remarcação administrativa (AGD-022)

`POST /api/v1/admin/appointments/{id}/reschedule` exige sessão OIDC/associação ativa, CSRF, `Idempotency-Key`, `expectedVersion`, `expectedScheduleRevision` e `startsAt` com offset. Somente `CONFIRMED` pode ser remarcada. Tenant e ator são derivados no servidor; ID alheio/inexistente mantém resposta não reveladora. A capability de recuperação pública não autoriza esta operação.

`expectedVersion` é CAS da reserva; `expectedScheduleRevision` é a revisão monotônica da agenda consultada em `GET /api/v1/admin/schedule/weekly-periods`. Não são intercambiáveis nem agregadas. Replay revalida a autorização atual antes de devolver `appointmentId`, `operationId`, versão e instantes do resultado original; não revalida a versão consumida pelo próprio commit nem repete efeitos.

A reserva conserva appointmentId, cliente, serviço e snapshot original; nova ocupação é calculada com duração/buffer do snapshot. **Decisão de produto aprovada em 17/10/2026 (ADR-0017):** para uma nova mutação, o servidor exige que o serviço relacionado esteja atualmente `ACTIVE` no tenant, consultando essa elegibilidade separadamente do snapshot e sincronizando-a com alterações concorrentes do catálogo. Inativar o serviço preserva reserva e ocupação; nenhum override contorna inatividade. Replay de comando concluído revalida autorização e devolve o receipt original sem repetir a avaliação de elegibilidade consumida pelo próprio comando, inclusive se o serviço foi inativado depois. Horário local e offset precisam ser válidos na zona IANA usada pela configuração. Regras de grade, exceção, antecedência, horizonte, bloqueios e ocupações são revalidadas após o lock compartilhado do tenant. O intervalo novo pode sobrepor o antigo da própria reserva, mas não qualquer outra ocupação. A troca usa update em uma transação, CAS e exclusion constraint; conflito preserva integralmente o horário antigo.

Overrides não são herdados. Se houver encaixe explícito, somente `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE` são aceitos, com motivo novo. A política aprovada na ADR-0016 continua válida: o primeiro override só ignora grade semanal sem exceção na data; `CLOSED` permanece fechado e `SPECIAL` limita a ocupação completa a um período especial. Nenhum outro limite pode ser ignorado ou alterado como efeito colateral.

Update, revisão da agenda, histórico before/after, auditoria allowlisted, receipt idempotente e `AppointmentRescheduled` compartilham o commit. O evento contém apenas identidade da reserva e versão. Atualmente não há armazenamento/pipeline de lembretes; o evento não prova cancelamento ou envio. Invalidação da intenção anterior e solicitação efetiva de lembrete novo dependem de AGD-030/031 e permanecem pendentes.

O detalhe técnico do protocolo e trade-offs está na ADR-0017.

### Clientes e restrições

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/clients?query=&cursor=` | busca limitada |
| GET | `/api/v1/admin/clients/{id}` | perfil local e faltas |
| GET | `/api/v1/admin/clients/{id}/no-shows` | eventos na janela e história |
| POST | `/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift` | desbloqueio com motivo |

#### Faltas explicáveis e política (AGD-032)

| Método | Endpoint | Uso |
|---|---|---|
| GET | `/api/v1/admin/no-show-policy` | consultar se o limite tenant-scoped está configurado |
| PUT | `/api/v1/admin/no-show-policy` | configurar/alterar limite 1–5 com `expectedVersion` e `Idempotency-Key` |
| GET | `/api/v1/admin/clients/{id}/no-shows` | contagem explicável do cliente autenticado, janela, política e fatos |
| POST | `/api/v1/admin/appointments/{id}/correct-no-show` | correção específica `NO_SHOW → COMPLETED`, motivo obrigatório, CAS e `Idempotency-Key` |
| POST | `/api/v1/admin/clients/{id}/restrictions/{restrictionId}/lift` | desbloqueio explícito com `expectedVersion`, motivo e `Idempotency-Key` |
| POST | `/api/v1/admin/clients/{id}/restrictions/reconcile` | reconciliação explícita por cliente com `expectedPolicyVersion` e `Idempotency-Key` |

O tenant e o administrador vêm da associação autenticada no servidor. A consulta captura um único `asOf` com `Clock`, calcula em UTC `windowStart = asOf - 12 meses de calendário` (com ajuste de dia inválido) e usa `[windowStart, asOf)`. O instante original da história `NO_SHOW` ancora a contagem; timezone IANA do tenant serve somente à apresentação. A consulta retorna `windowStart`, `asOf`, `timeZone`, contagem, política `{status: CONFIGURED|NOT_CONFIGURED, threshold?, version}`, estado da restrição (`NOT_CONFIGURED|CLEAR|RESTRICTED`), restrição ativa e história mínima da decisão, além dos fatos de contribuição. Consulta, contagem e fatos são lidos sob um snapshot PostgreSQL coerente; paginação usa `asOf` fixo e ordenação por instante/ID, mas páginas são requests independentes e não prometem snapshot físico imutável.

Política ausente é `NOT_CONFIGURED`, `version=0`, sem threshold. Contagem e fatos permanecem disponíveis; não inferir `CLEAR` nem criar restrição. Restrição ativa é consultada e bloqueia novo autoagendamento mesmo com política ausente. Sem política e sem restrição ativa, criação pública continua permitida com status `NOT_CONFIGURED`, sob todos os demais gates. Correção e envelhecimento não removem restrições. Desbloqueio é explícito, exige motivo e preserva os fatos.

Reduzir o limite não executa varredura durante o `PUT`. O comando administrativo de reconciliação por cliente revalida a associação atual, usa lock tenant-first comum e compara `expectedPolicyVersion` depois do lock. Política ausente produz `409 POLICY_NOT_CONFIGURED`. Captura `asOf` após o lock, calcula janela/fatos conforme ADR-0023 e retorna decisão (`BELOW_THRESHOLD`, `ALREADY_RESTRICTED`, `NO_UNUSED_CAUSAL_FACT` ou `ACTIVATED`), `asOf`, `windowStart`, contagem, threshold/versão e restrição resultante. O request inclui somente `expectedPolicyVersion`; cliente e tenant vêm da rota autenticada. Escopo idempotente é tenant/cliente/reconciliação versão 1 e digest canônico inclui `expectedPolicyVersion`. Replay revalida autorização e retorna o receipt original sem repetir efeitos, inclusive após desbloqueio posterior.

Ativação por reconciliação seleciona uma falta contribuinte na janela ainda não referenciada como causa de nenhuma restrição (ativa ou já desbloqueada). Se não existir causa inédita, a decisão não ativa nem reusa causa histórica. Uma nova falta pode ativar novamente sob a política vigente. Quando `ACTIVATED`, restrição, histórico administrativo `RECONCILED`, auditoria, receipt e `ClientSelfBookingBlocked` são gravados na mesma transação; decisões sem ativação também persistem receipt/auditoria, sem outbox de ativação. Concorrência com alteração da política serializa pelo lock; versão obsoleta recebe `409 VERSION_CONFLICT`. A política é independente da restrição e não se remove ou libera uma restrição ativa por ausência de configuração. Reserva administrativa e consulta/cancelamento próprio permanecem permitidos.

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

AGD-040 expõe em `GET /api/v1/admin/subscription` o trial/assinatura do tenant da associação autenticada e as ofertas `MONTHLY`/`ANNUAL`, com `Cache-Control: no-store`. A oferta anual informa equivalência a dez mensalidades, sem introduzir preço ou moeda. A concessão é iniciada somente por provisionamento server-side que receba identidade opaca de fonte confiável. Sem fonte configurada ou com resultado inconclusivo, `subscription` é `null`, a elegibilidade é `VERIFICATION_UNAVAILABLE` e não se concede trial. O trial válido dura 14 dias; considera-se expirado em `now >= trialEndsAt`, quando passa a `SUSPENDED`, sem tolerância de `PAST_DUE`. Não há comando de escolha/checkout nesta história. Selecionar uma oferta não altera o estado nem comprova pagamento. O endpoint não recebe tenantId nem identidade comercial.

#### AGD-041 — checkout hospedado e evidência de pagamento

`POST /api/v1/admin/subscription/checkout` recebe somente `planCode` (`MONTHLY`/`ANNUAL`) e `expectedVersion`, além de `Idempotency-Key` e `X-Expected-Tenant-Context`. O contexto do cabeçalho é uma precondição contra troca de sessão/contexto, comparada com a associação autenticada no servidor; não seleciona nem concede tenant. Tenant/ator vêm exclusivamente da sessão e associação atual. A intenção no navegador é vinculada ao UUID de contexto retornado pela consulta administrativa, com corpo e chave imutáveis durante envio/resultado incerto. O servidor resolve preço, moeda, versão da oferta e termos; sem oferta completa e provider configurado retorna indisponibilidade sem chamar serviço externo nem criar operação. O checkout deve ser hospedado/tokenizado pelo provider; nenhum dado completo de cartão é aceito pela API. A operação persistida mantém snapshot mínimo dos termos e identidade estável; resposta perdida/retry recupera a mesma operação. HTTP 408, falha de transporte, 5xx ou resposta inválida após envio mantêm resultado incerto. Rejeições são definitivas somente por resposta estruturada/código reconhecido; os conflitos conhecidos `VERSION_CONFLICT`, `SUBSCRIPTION_ALREADY_ACTIVE` e `IDEMPOTENCY_KEY_REUSED` não são confundidos com ambiguidade financeira. Uma chamada externa incerta não autoriza novo checkout lógico ou reenvio cego.

`GET /api/v1/admin/subscription/payment-attempts` e `GET /api/v1/admin/subscription/payment-attempts/{operationId}` são tenant-scoped, minimizados e `no-store`; consulta/reconciliação usa a identidade original e não cria outra cobrança. `CHECKOUT_READY` e redirect são apenas sessão hospedada criada, não pagamento confirmado. Somente evidência autoritativa autenticada e correlacionada pode promover a assinatura para `ACTIVE`; preço/moeda da evidência devem coincidir com o snapshot do servidor. O frontend não envia estado de pagamento.

`POST /api/v1/webhooks/payments/{provider}` é estruturalmente reservado a adapters específicos. Body bruto, cabeçalhos, timestamp, autenticidade e proteção de replay seguem o contrato oficial do provider aprovado. Não existe protocolo genérico aceito em produção: sem adapter autenticador configurado, a rota falha fechada e não persiste eventos. Corpo admitido é limitado a 65536 bytes; inválido não altera assinatura. Eventos autenticados são deduplicados por provider/conta/evento, correlacionados exclusivamente a operação/referência persistida e processados sob ordenação autoritativa. Evento duplicado não repete efeito; evento antigo não reverte fato mais novo. Adapters sintéticos e seus headers são exclusivos de E2E.

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

### Pipeline AGD-030 — estado incremental

O consumidor local de `AppointmentConfirmed`, `AppointmentRescheduled` e `AppointmentCancelled` cria intenção tenant-scoped, deduplicada por tenant, reserva, versão, tipo e destinatário. Receipt e intenção são confirmados na mesma transação. O worker valida novamente estado/versão, usa lease/fencing, chama o adapter fora da transação e separa retry comprovadamente sem efeito, falha final e resultado desconhecido. Resultado de notificação não altera a reserva.

As consultas administrativas minimizadas estão descritas em `docs/openapi/admin-notifications.yaml`. Sem fornecedor, template e protocolo aprovados, não há transport de produção nem endpoint de webhook ativo; a porta de verificação/admissão é exercitada apenas com adapter de teste. O adapter não comprova autenticidade nem entrega real. Falta de configuração de envio mantém o worker sem claim/tentativas. Evidência autenticada ainda sem referência correlacionada é armazenada em inbox minimizada. A admissão e a persistência do aceite serializam por advisory lock transacional comum de `(provider_code, provider_message_reference)`, de modo que qualquer uma das ordens de commit reconcilia a evidência sem depender de reentrega; duplicatas também tentam reconciliar pendências antes de retornar. Dedupe e sequência permanecem duráveis/monotônicas. Isso não cobre `OUTCOME_UNKNOWN` sem referência e sem consulta/idempotência suportada pelo fornecedor: essa reconciliação permanece pendente e não há reenvio automático. Retenção continua sem política aprovada. AGD-031 é separada: este pipeline não cria, invalida nem confirma envio de lembretes.

### Lembrete único — AGD-031

O consumidor independente `notifications.appointment-reminders.v1` processa `AppointmentConfirmed`, `AppointmentRescheduled`, `AppointmentCancelled`, `AppointmentCompleted` e `ClientNoShowRecorded`. Receipt e intenção são persistidos atomicamente; chave lógica inclui tenant, reserva, versão, tipo e destinatário. Só a versão e o estado atuais podem produzir intenção. Política ausente não produz lembrete. V24 mantém `due_at` separado de `next_attempt_at` e permite lembrete originado do mesmo evento que a notificação imediata. Remarcação invalida lembretes de versões anteriores; eventos terminais invalidam pendentes. O worker reivindica uma intenção por vez e, imediatamente antes do transporte, revalida fencing/lease, estado, versão e se a reserva ainda não começou. Essa checagem não pode cancelar mensagem já iniciada no transporte. Métricas agregadas não usam tenant, telefone ou reserva como label.

Decisão de produto aprovada nesta instrução para AGD-031: política ausente não cria intenção nem inventa offset; “padrão ligado” só vale com política explicitamente configurada. Job atrasado pode ser enviado enquanto estado e versão permanecem atuais e `starts_at > Clock.instant()`; igualdade com o início ou instante posterior torna-o obsoleto, sem tolerância. Reserva cujo evento é consumido depois de `due_at` fica imediatamente elegível se ainda não começou e as demais validações passarem. `due_at` materializa o offset vigente no consumo do evento; política alterada depois não recalcula intenção já criada, enquanto evento ainda não consumido e nova versão após remarcação usam o offset vigente quando processados. Nenhuma chamada externa ocorre na transação. Sem fornecedor real, transporte de produção permanece desabilitado; adapters de teste provam somente o núcleo persistente, não entrega.

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

Para `AppointmentConfirmed` v1, o payload publicado limita-se a `appointmentId` e `version`; tenant, aggregate, operação e correlação ficam no envelope persistido. Consumidores não inferem envio de mensagem a partir do commit.

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

AGD-042 materializa `ACTIVE → PAST_DUE` quando o instante de vencimento (`paid_through_at`) é alcançado, conserva o instante e persiste `grace_ends_at = paid_through_at + 168 horas`. As tentativas lógicas são únicas por ciclo/sequence em D0/D+3/D+6 nos offsets UTC de 0/72/144 horas; reexecução técnica conserva identidade e chave externa. `now >= grace_ends_at` encerra tolerância. Após downtime, durante a tolerância, somente a tentativa vencida mais recente nunca iniciada permanece elegível; anteriores são `SKIPPED_LATE_RECOVERY` com motivo, sem mover futuras. Tentativa iniciada/em voo/aceita/ambígua nunca é pulada e exige reconciliação antes de autorizar outra cobrança lógica. Após o fim da tolerância não se inicia nova chamada, mas resultado de chamada iniciada continua persistível/reconciliável mesmo após suspensão. Agendamento, claim ou aceite de transporte não comprovam pagamento.

`GET /api/v1/admin/subscription` também devolve estado, `paidThroughAt`, `graceEndsAt` e até 30 agendamentos lógicos do tenant autenticado, sem PII. A resposta é `no-store`. `PAST_DUE` mantém o uso permitido durante a tolerância; após a fronteira temporal aprovada, novas reservas públicas são negadas mesmo antes da materialização `SUSPENDED`. Nenhuma dessas regras amplia o prazo de trial de AGD-040. Sem provider e autorização recorrente aprovados/configurados, o transporte financeiro continua desabilitado.

O worker expõe métricas agregadas de backlog, vencimento/idade, tentativas técnicas, `OUTCOME_UNKNOWN`, aceite de transporte, recusas, falhas finais e pulos por recuperação tardia. Métricas não usam tenant, referência de operação, provedor ou PII como label. Aceite de transporte não é liquidação nem pagamento.

## 12. Jobs agendados

| Job | Frequência/acionamento | Proteção |
|---|---|---|
| lembretes | consulta contínua por `due_at` | claim com lease, dedupe e verificação da versão da reserva |
| cobrança D0/D+3/D+6 | scheduler por vencimento/`next_attempt_at` | claim individual, lease/fencing, autorização sob lock tenant-scoped e chave estável por tentativa |
| reconciliação financeira | consulta pelo identificador persistido | somente protocolo/idempotência suportados; sem suporte, estado ambíguo permanece bloqueado |
| suspensão | quando `now >= grace_end` | CAS da assinatura; impede novos inícios e preserva resultados de chamadas já iniciadas |
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
