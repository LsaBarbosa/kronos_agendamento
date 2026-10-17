# AGD-015 — Commit de reserva sem sobreposição

## Identificação e objetivo

- Incremento P0 sobre `main` integrada em `e5052261ca54ca4186a12a2c8341471a352aaba6`, branch `agd-015-reserva-sem-sobreposicao`.
- Referências: documento 01 §§4, 5–6; documento 02 §§4.2, 4.5–4.6, 11 e 13; documento 03 §§1–3, 6–8 e eventos; documento 04 §§6, 7 e 16; documento 06 AGD-015, AGD-016, AGD-020–025 e AGD-030.
- Resultado observável: visitante autorizado pela publicação confirma uma reserva persistida sem conflito, com cliente tenant-scoped, snapshot, histórico, auditoria, idempotência e `AppointmentConfirmed` confirmados atomicamente.

## Escopo e exclusões

- Inclui cliente público com telefone E.164, criação de `CONFIRMED`, snapshot do serviço, história inicial, ocupação PostgreSQL, API pública, ocupações reais em disponibilidade/bloqueios e confirmação na interface.
- Inclui proteção compartilhada da disputa entre reserva e bloqueio e retry da intenção após resposta perdida.
- Não inclui remarcação/cancelamento/faltas, encaixe administrativo, acesso verificado do cliente, WhatsApp, cobrança, notificações executadas, área do cliente, hold, cache ou broker.
- O gate legal/comercial segue bloqueando publicação real; fixture E2E não constitui consentimento, termo aceito ou integração comercial real.

## Estado e decisões técnicas

- AGD-014 foi integrada por PR #9: head aprovado `64e418a03f190dc30f5cb3963d7495b113e18f26`, merge commit `e5052261ca54ca4186a12a2c8341471a352aaba6`. Sem review humana registrada; revisão independente por agentes não equivale a aprovação humana. Checks do head passaram; pós-merge em execução.
- Última migration publicada observada: V12; nova migration funcional será V13. V1–V12 permanecem imutáveis.
- Idempotência: tenant + `appointments.public.create.v1` + digest versionado de slug, serviço, instante/offset, nome normalizado e telefone E.164. Replay revalida publicação/readiness/serviço antes de retornar apenas IDs e resumo sem PII; não testa disponibilidade contra a própria ocupação.
- Telefone: aceitar representação internacional explicitamente com `+` e código de país; remover apenas separadores de apresentação permitidos e validar E.164. Não inferir país a partir do navegador, slug ou timezone.
- Snapshot inclui nome/duração/buffer do serviço no commit, timezone e nome do cliente conforme documento 02; preço/moeda permanecem ausentes/nulos, sem valor padrão. O telefone fica no cadastro tenant-scoped do cliente e não é duplicado como snapshot por não constar entre os campos documentados; telefone e nome não entram em auditoria/outbox nem na resposta idempotente.
- Concorrência: um lock transacional compartilhado por tenant, adquirido por `SELECT ... FOR UPDATE` no registro tenant antes das leituras de conflito em ambos os comandos de criação. Transações operam em `READ COMMITTED`; consultas de bloqueios/ocupações executadas após a espera observam commits anteriores ao lock. Ordem de lock: tenant → leitura de agenda/serviço/cliente → gravações locais. Sem chamadas externas na transação.
- Exclusion constraint PostgreSQL sobre tenant e `tstzrange(starts_at, occupied_until, '[)')`, parcial a `CONFIRMED`, garante também reserva-reserva. Conflito conhecido desta constraint é mapeado especificamente para `SLOT_NO_LONGER_AVAILABLE`; outros erros de integridade não são mascarados.
- API pública aceita somente `serviceId`, `startsAt` ISO 8601 com offset e `client{name,phone}`; ignora dados derivados do cliente. O servidor resolve slug, timezone, tenant, elegibilidade e slot. Mutação pública exige CSRF e chave de idempotência; publicação e políticas são verificadas novamente no comando.
- Corridas com grade/catálogo são revalidadas na operação. Lock comum a reserva/bloqueio é a garantia necessária contra conflito entre tabelas; alterações de catálogo não removem snapshots já persistidos.

## Critérios e evidências

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Nome, E.164 e slot validados | domínio, `PublicAppointmentIT` e browser E2E | normalização, equivalência de telefone, offset/zona e serviço elegível | Backend local passou no SHA `166ec0a7616405c820d79e5dd08a08340bc5ac94`; browser Keycloak/PostgreSQL/Chrome passou no head `35c6ff8eb507e3fc2e214893f0058b7f19820552` |
| Cliente, reserva, história, audit, idempotência e outbox atômicos | `./mvnw spotless:check verify` | falhas de audit/outbox revertem o conjunto; retry recupera receipt sem PII | 63 testes unitários/arquiteturais + 92 integrações PostgreSQL 16.13/Testcontainers passam localmente no SHA `166ec0a7616405c820d79e5dd08a08340bc5ac94`; App CI verify passou no head `35c6ff8` em push/PR |
| Snapshot permanece inalterado após catálogo mudar | `PublicAppointmentIT` | nome/duração/buffer do serviço confirmados preservados; preço/moeda ausentes | Teste PostgreSQL passa; telefone permanece no cadastro Client, pois não é campo de snapshot documentado |
| Restrição PostgreSQL e adjacência | V13 e `PublicAppointmentIT` | GiST rejeita overlap, permite adjacência; somente violação nomeada vira conflito de slot | Teste PostgreSQL passa |
| Duas reservas no mesmo slot | `PublicAppointmentIT` com transações independentes | exatamente uma confirma; outra recebe `SLOT_NO_LONGER_AVAILABLE` | Teste PostgreSQL passa |
| Reserva versus bloqueio, nos dois sentidos | `PublicAppointmentIT` com trigger/barreira PostgreSQL | reserva primeiro recusa bloqueio; bloqueio primeiro recusa reserva | Testes PostgreSQL passam sob lock comum e `READ COMMITTED` explícito |
| Replay, digest conflitante, revogação e resposta perdida | `PublicAppointmentIT` e browser E2E | revalidação precede replay; mesmos IDs sem repetir efeitos; corpo/chave preservados em timeout | Replay, conflito e despublicação antes do replay passam localmente; browser simulou resposta perdida após commit, repetiu mesmo corpo/chave, recebeu confirmação e verificou uma única reserva persistida |
| Disponibilidade usa ocupações reais | `AvailabilityScheduleIT` e `PublicAppointmentIT` | ocupação confirmada desaparece; fonte indisponível retorna erro temporário | Integrações PostgreSQL passam; sem reserva/ocupação artificial de produção |
| Browser Keycloak + PostgreSQL + Chrome | job `oidc-browser-e2e` em App CI | admin usa fixture E2E restrita; visitante confirma reserva real; retry cria uma reserva; slot deixa oferta | Passou em push/PR no head `35c6ff8`; não executado localmente para preservar Compose/volumes compartilhados. Fixtures legais/comerciais do overlay E2E não comprovam prontidão real |
| Migrations/grants | `TenantPersistenceIT` e Flyway | instalação limpa, upgrades V1/V2/V5/V6/V7/V8/V9/V10/V11/V12, validate/rerun, FKs, GiST e grants runtime mínimos | Passa com PostgreSQL 16.13/Testcontainers: fresh V1–V13, upgrades e rerun; V1–V12 imutáveis |

## Estratégia e progresso

1. [x] Inspecionar AGD-014, critérios, migrations e adapters existentes; identificar lacuna real de appointments. (16/10/2026)
2. [x] Registrar contrato e ADR de lock compartilhado, `READ COMMITTED` explícito, actor e plano.
3. [x] Criar V13, domínio e adapters de cliente/reserva/história; atualizar allowlists de audit/outbox.
4. [x] Integrar leitura tenant-scoped ao motor e à criação de bloqueios.
5. [x] Implementar caso de uso/API com chave idempotente e revalidação antes do replay e sob lock.
6. [x] Conectar formulário/retry com CSRF e estado de resultado desconhecido.
7. [x] Adicionar testes PostgreSQL de constraints, rollback, replay e corridas; browser E2E criado para App CI.
8. [x] Revisões independentes somente leitura concluídas; incompatibilidade do `@MockitoBean` multimódulo corrigida por bean `@Primary` isolado. Após primeiro CI, corrigido import faltante na fixture E2E e, depois, serialização HTTP que removia o offset tenant; teste PostgreSQL passou com `-03:00`. Revisão final encontrou que um retry incerto recebendo 404 após despublicação liberaria a intenção. A UI agora mantém estado incerto e bloqueia nova intenção, sem alegar confirmação nem permitir troca do comando; o caso novo passou em Vitest local. App CI/CodeQL do head anterior passaram, mas não validam esta correção ainda. A falha anterior de E2E por texto desatualizado foi corrigida sem reduzir assertions.

## Riscos e limites

- A disponibilidade pública deixa de falhar como indisponível somente quando a fonte PostgreSQL de appointments for ligada; fallback de erro não pode retornar lista vazia.
- Confirmação e bloqueio precisam chamar o mesmo componente/SQL de lock e manter a ordem de aquisição. A exclusion constraint sozinha não cobre a tabela `blocked_period`.
- `AppointmentConfirmed` registra referência e versão permitidas, sem PII; WhatsApp continua posterior e não participa do commit.
- Fixtures de publicação no overlay E2E são somente evidência da aplicação e nunca alteram o gate padrão de produção.
- O `request_digest` é um SHA-256 determinístico do comando canônico (inclui nome/telefone normalizados), não guarda os valores em claro, mas admite tentativa offline de candidatos se houver cópia do banco. HMAC exigiria segredo estável e operação de rotação; registrar essa mitigação como risco residual e não confundir o digest com armazenamento seguro de PII.
- Sem homologação HTTPS/staging, preflight hospedado, scan completo de dependências, política de retenção aprovada ou fornecedor real.

## Encerramento ou retomada

- AGD-015 permanece parcial: fluxo funcional, concorrência, atomicidade, PostgreSQL e CI têm evidência neste incremento; readiness legal/comercial real, homologação HTTPS/staging, preflight hospedado, scan completo, retenção e WhatsApp real continuam pendentes. A intenção incerta fica em memória enquanto a página está montada; reload/fechamento perde o estado local, sem persistir PII no browser. Recuperação após reload permanece evolução de AGD-016.
- A nova correção para 404 após resultado incerto está localmente validada, aguardando commit/publicação e App CI/CodeQL do head final antes de qualquer integração.
- O próximo incremento recomendado é AGD-016 para endurecer retry consultável/recuperável; futuras histórias continuam responsáveis por acesso de cliente, notificações e comandos de agenda.
