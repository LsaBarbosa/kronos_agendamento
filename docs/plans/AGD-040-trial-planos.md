# AGD-040 — Trial e planos

## Identificação e objetivo

- História: AGD-040, trial e planos. Incremento iniciado em `agd-040-trial-planos`, sobre a main integrada em `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d`.
- Referências: documento 01 §12; documento 02 §7; documento 03 §2 e §12; documento 04 §§4 e 19; documento 06 AGD-040/041/042/043; ADR-0003 e ADR-0008.
- Objetivo observável: apresentar mensal/anual sem inventar preço/moeda; implementar concessão persistida de trial quando uma porta exclusivamente server-side retornar identidade comercial estável verificada; aplicar no servidor o gate temporal de novas reservas sem aceitar seleção de plano como pagamento.
- Exclusões: checkout, cobrança, webhooks financeiros, confirmação de pagamento, reativação por pagamento, tolerância de inadimplência e retenção; pertencem a AGD-041+ e decisões/gates próprios. Não habilitar publicação real ou envio financeiro.

## Estado e dependências

- A fundação agora persiste assinatura/trial e aplica o gate de novas reservas (V30). `CreateTenant` continua sendo provisionamento controlado; não é fonte de identidade comercial confiável.
- O onboarding e a publicação continuam condicionados a termos/consentimentos e gates comerciais. Tenant/slug/administrador OIDC não serão usados como identidade comercial sem decisão explícita.
- A fonte/fornecedor que verificará a identidade comercial continua pendente em `OPEN-QUESTIONS.md`. Nesta entrega, a porta só aceita identidade opaca vinda de fonte confiável server-side; a implementação de produção permanece inconclusiva/desabilitada. Não deduzir elegibilidade de tenantId, slug, e-mail, subject OIDC ou dado autodeclarado.
- AGD-041 é dependência para cobrar/confirmar plano; uma escolha de plano não será registrada como assinatura paga.

## Escopo e critérios verificáveis

- Trial `TRIALING` de 14 dias corridos, sem cartão, somente para elegibilidade confiável e única; início e fim visíveis ao administrador.
- Catálogo administrativo exibe planos mensal recorrente e anual com equivalência de dez mensalidades, sem preço/moeda fictícios.
- Estado temporal efetivo impede novas reservas públicas após `trial_end`; transição persistida a `SUSPENDED` segue o estado aprovado no documento 02. Reservas existentes, consultas/cancelamentos do cliente e operações administrativas permitidas permanecem intactas.
- Recriação de tenant não produz novo trial para a mesma identidade opaca verificada; a garantia do mecanismo será testada com adapter sintético restrito aos testes. A fonte comercial real e, portanto, concessões reais permanecem pendentes.
- Seleção de plano não cria estado `ACTIVE` nem prova pagamento; nenhuma API de checkout/pagamento é criada.
- Identidade do tenant e autorização administrativa vêm da sessão/vínculo verificado no servidor. O gate de nova reserva é revalidado no servidor, inclusive no commit público; falha/ausência de registro comercial não abre acesso.

## Contratos e consistência

- `GET /api/v1/admin/subscription`: estado/datas do tenant autenticado, elegibilidade reportada sem PII e catálogo sem valores monetários. Resposta `no-store`; sem `tenantId` de entrada.
- A concessão é acionada exclusivamente no provisionamento server-side consultando `CommercialEligibilityIdentitySource`; não existe endpoint para declarar identidade verificada. Sem fonte configurada ou com resultado inconclusivo, não há concessão. Adapters com identidade sintética são exclusivos de testes/E2E e não podem ser ativados no perfil normal.
- A porta retorna token opaco estável da identidade verificada, nunca o identificador bruto. Ledger separado e unicidade no banco impedem reutilizar benefício entre tenants recriados; assinatura corrente é única por tenant. Tentativa repetida no mesmo tenant é idempotente; identidade previamente consumida não recebe outro trial.
- Persistência/ledger usam V30, FK sem cascade e grants mínimos. A concessão grava histórico, auditoria e receipt idempotente transacionalmente. A expiração usa CAS/histórico/auditoria no mesmo commit e não cria receipt/outbox sem contrato para operação de worker. `TRIALING → SUSPENDED` em `now >= trialEndsAt`; o gate recusa novas reservas desde a igualdade, mesmo antes da materialização. O worker é desabilitado nos testes que substituem `Clock`/repositório, mantendo o relógio determinístico.
- Gate de disponibilidade e nova reserva consulta a assinatura sob a proteção tenant-scoped existente; o commit revalida depois do lock. `TRIALING` só permite novas reservas em `now < trialEndsAt`; ausência, `SUSPENDED` e fonte inconclusiva fecham novas reservas sem afetar consulta/cancelamento de reservas existentes. Gates legais/publicação permanecem independentes.
- Preservar V1–V29; próxima migration V30, com grants mínimos, installation/upgrade/Flyway validate/rerun.

## Estratégia e riscos

1. Preservar a pendência de fonte comercial real e manter a produção fail-closed.
2. Implementar porta opaca server-side, concessão de 14 dias, ledger separado e assinatura única por tenant; integrar no provisionamento controlado sem bloquear criação de tenant quando a fonte estiver ausente.
3. Materializar vencimento por CAS e aplicar gate comercial à disponibilidade e ao commit público; bloquear apenas novas reservas, preservando consulta/cancelamento existente.
4. Exibir estado/datas e elegibilidade no painel sem expor identidade nem oferecer simulação de verificação/pagamento; proteger leituras antigas após logout/troca de contexto.
5. Testar com Clock e PostgreSQL concorrência, unicidade, replay, rollback, isolamento, expiração e preservação de reservas; E2E controlado usa identidade sintética apenas em configuração de teste.

Retenção/expurgo do ledger de concessões não está aprovado. O desenho não apaga nem expira elegibilidade neste incremento; a política de retenção deverá ser decidida antes de qualquer rotina de expurgo.

Trade-off: fail-closed pode impedir novas reservas enquanto trial não estiver provisionado, mas evita dar acesso gratuito sem identidade comercial verificável. Este incremento não autoriza fornecedor ou ambiente de pagamentos.

## Progresso

- [x] 2026-10-21 — PR #21 AGD-033 integrado por merge commit `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d`; App CI e CodeQL pós-merge completos e verdes.
- [ ] A identidade comercial verificável permanece pendente; sem fonte real e segredo/configuração aprovados, concessões de produção ficam desabilitadas.
- [x] 2026-10-21 — catálogo de planos no domínio e `GET /api/v1/admin/subscription`, com `no-store`, sem preços/moeda e sem implicar pagamento; painel administrativo apresenta mensal/anual e deixa explícita a elegibilidade pendente.
- [x] 2026-10-21 — contrato OpenAPI e regressão de browser adicionados para autenticação, ausência de trial provisionado e anual = dez mensalidades.
- [x] 2026-10-21 — porta server-side de identidade comercial; adaptador de produção retorna inconclusivo e fixture sintética existe somente no perfil E2E. Sem fonte aprovada, nenhuma concessão real é possível.
- [x] 2026-10-21 — concessão idempotente transacional integrada ao provisionamento de tenant; ledger global impede reutilização entre tenants, com disputa concorrente coberta em PostgreSQL.
- [x] 2026-10-21 — V30 persiste assinatura, ledger e histórico; CAS materializa expiração e gates de disponibilidade/commit bloqueiam nova reserva no vencimento, preservando reservas existentes.
- [x] 2026-10-21 — painel e OpenAPI mostram estado/datas e elegibilidade sem expor identidade; E2E de browser verifica reload e bloqueio pós-vencimento usando somente fixture restrita.
- [x] 2026-10-21 — validação local completa: backend `spotless:check verify` com JDK 21 passou (79 unitários/arquiteturais + 235 integrações PostgreSQL 16.13/Testcontainers), incluindo `TenantPersistenceIT` (28) e `SubscriptionTrialIT` (8); frontend format/lint, 23 Vitest, TypeScript/build, sintaxe Node e `bash -n` passaram.
- [ ] E2E local real não executado: Compose compartilhado tem somente PostgreSQL/Keycloak, sem backend/frontend; não foi reiniciado. O E2E real passou no App CI push/PR do head final indicado na validação abaixo.
- [ ] Revisão independente indisponível pelo limite de threads; revisão sequencial do coordenador concluída, sem equivalência a aprovação humana.
- [x] 2026-10-21 — PR draft [#22](https://github.com/LsaBarbosa/kronos_agendamento/pull/22) aberto para `main`, commit funcional `2c972dd17b46466bc2d08b950ce35a0396cd527f`; sem merge/deploy.
- [x] 2026-10-21 — App CI/CodeQL push e PR concluídos em `33a4cb0c3788257a85ecdc3e54c6c81747b6813f`; todos os jobs/check-runs passaram, incluindo E2E browser. E2E local não executado.
- [x] 2026-10-21 — SHA funcional publicado `74d3536e881a70fbde2fa448e1ba50aa858ac75d`; App CI PR [39598299143](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39598299143) e CodeQL push/PR [39598293006](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39598293006)/[39598299258](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39598299258) concluíram success, incluindo E2E browser no PR e todos os analisadores. App CI push [39598292893](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39598292893) falhou somente no `oidc-browser-e2e` legado de remarcação; o mesmo job passou no evento PR. A divergência permanece sem causa conclusiva e está preservada como tal em VALIDATION.
- [x] 2026-10-21 — Na revalidação do head documental `ae45542a26ad46ebe9ff11eb231c00c64f1eb828`, push App CI passou incluindo E2E; PR App CI falhou no E2E ao consultar o campo “Nome” antes do rerender após alternar para novo cliente. O roteiro foi sincronizado por condição DOM (sem atraso arbitrário); os novos checks aguardam execução.
- [x] 2026-10-21 — Correção de sincronização do roteiro no SHA `646dfa72941ab561ab872787d9646dcc22f29464`; App CI push [39600563357](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39600563357) e PR [39600570844](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39600570844), CodeQL push [39600563399](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39600563399) e PR [39600570946](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39600570946) concluíram todos os jobs/analisadores/check-runs com sucesso; ambos os E2Es browser passaram.
- [x] 2026-10-21 — Head documental `453b44cf1b8df0aaf950913fda146632955c5b7e` passou App CI push/PR [39601329376](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39601329376)/[39601339058](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39601339058) e CodeQL push/PR [39601329300](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39601329300)/[39601339092](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39601339092), todos os workflows/jobs/check-runs verdes, inclusive os dois E2Es reais. O código não mudou após `646dfa7`; esta atualização documental exige verificação própria dos checks.
- [x] 2026-10-21 — O head documental `2e6c11ef3d3f93633829018e0af6974a76dbd7bc` também passou App CI push/PR [39602114684](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39602114684)/[39602125061](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39602125061) e CodeQL push/PR [39602114672](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39602114672)/[39602125067](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39602125067), todos os workflows/jobs/check-runs success, incluindo E2E nos dois eventos.
- [ ] E2E local Chrome/Keycloak/PostgreSQL não executado porque a composição ativa não contém backend/frontend e não foi reiniciada. Homologação e gates comerciais/legais continuam fora da evidência local/CI.
- [ ] Fonte/identificador comercial aprovado e configuração gerenciada seguem pendentes; nenhuma concessão real é habilitada pela implementação atual. Retenção/expurgo do ledger também requer decisão explícita.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Integração AGD-033 | App CI run `39586871605`; CodeQL run `39586871623` | todos jobs/check-runs success no merge SHA | App CI completo success; CodeQL ambos analisadores completos success; SHA `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d` |
| Regressões existentes/backend | `cd backend && JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` | testes unitários e integração PostgreSQL passam | `BUILD SUCCESS`: 79 unitários/arquiteturais e 235 integrações PostgreSQL 16.13/Testcontainers, sem falhas |
| Catálogo de planos/API segura | teste `PlanCatalogTest`, `SecurityBoundaryTest`; browser E2E no CI | dois planos, anual = 10, sem preço, sessão obrigatória e no-store | teste backend dirigido passou (8 testes); browser E2E local não executado; CI Chrome/Keycloak/PostgreSQL passou no head final |
| Trial elegível/único | `SubscriptionTrialIT`, identidade sintética explícita | 14 dias, uma concessão mesmo com tenant recriado/concorrência | Testado em PostgreSQL com adapter controlado; fonte aprovada real continua pendente |
| Gate de novas reservas | `SubscriptionTrialIT`, `PublicAppointmentIT`, `PublicPageIT` | igualdade no fim fecha; gate no commit e disponibilidade; reservas existentes preservadas | Integrações PostgreSQL e E2E browser Chrome/Keycloak/PostgreSQL passaram no CI do head final |
| Interface/admin | `AdminSubscriptionPanel`, roteiro Keycloak | estado/datas após reload, logout não restaura resposta privada, vencimento bloqueia nova reserva | Format/lint/23 Vitest/TypeScript/build locais passaram; CI browser Chrome/Keycloak/PostgreSQL passou no head final |

### Complemento desta continuação

- Núcleo funcional concluído; `AGD-040` permanece parcial até existir fonte comercial aprovada e configuração gerenciada. Não habilitar concessões reais por fallback.
- `V30__persist_trial_grants_and_subscription_lifecycle.sql` preserva V1–V29 e cobre assinatura única por tenant, concessão única por identidade, histórico, checks, índice de expiração e permissões runtime mínimas.
- Testes desta rodada: `SubscriptionTrialIT` cobre concessão ausente/inconclusiva; concessão válida e repetida; identidade repetida em tenant recriado; corrida entre tenants; rollback de concessão/auditoria/receipt; expiração antes/na igualdade/depois; gate com worker atrasado; concorrência de materialização; consulta autenticada e tenant-scoped. A suíte completa executou 79 unitários/arquiteturais + 235 integrações PostgreSQL, zero falhas.
- No head documental `170def4c2274c45c840694b9d401c84b07f52f6f`, App CI push/PR [39616189058](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39616189058)/[39616195080](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39616195080) e CodeQL push/PR [39616189049](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39616189049)/[39616195037](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39616195037) concluíram `completed/success`, com todos jobs/check-runs e E2E nos dois eventos. Isso resolve a inconsistência no envelope do run push do head anterior. E2E local não executado porque a composição compartilhada não contém backend/frontend; ver `docs/development/VALIDATION.md`.
- Decisões externas pendentes: fonte/identificador comercial verificável e provisionamento de segredo/configuração; política de retenção do ledger; textos/versões legais e demais gates de publicação; preço/moeda e cobrança em AGD-041+. Nenhuma contratação, checkout, pagamento ou envio real é autorizado.
