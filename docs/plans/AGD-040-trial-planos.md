# AGD-040 — Trial e planos

## Identificação e objetivo

- História: AGD-040, trial e planos. Incremento iniciado em `agd-040-trial-planos`, sobre a main integrada em `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d`.
- Referências: documento 01 §12; documento 02 §7; documento 03 §2 e §12; documento 04 §§4 e 19; documento 06 AGD-040/041/042/043; ADR-0003 e ADR-0008.
- Objetivo observável: apresentar mensal/anual sem inventar preço/moeda, persistir/consultar trial e estado por tenant, e aplicar no servidor a elegibilidade comercial de novas reservas sem aceitar seleção de plano como pagamento.
- Exclusões: checkout, cobrança, webhooks financeiros, confirmação de pagamento, reativação por pagamento, tolerância de inadimplência e retenção; pertencem a AGD-041+ e decisões/gates próprios. Não habilitar publicação real ou envio financeiro.

## Estado e dependências

- A fundação `subscriptions` contém apenas `package-info`; não há entidade, migration ou gate comercial persistido. `CreateTenant` é provisionamento controlado, sem identidade comercial confiável; não existe fluxo de cadastro comercial em produção.
- O onboarding e a publicação continuam condicionados a termos/consentimentos e gates comerciais. Tenant/slug/administrador OIDC não serão usados como identidade comercial sem decisão explícita.
- Lacuna de produto registrada em `OPEN-QUESTIONS.md`: a fonte verificável da identidade de elegibilidade que impede novo trial quando um tenant é recriado não está definida. O critério “uma vez por tenant/identidade comercial” não autoriza deduzir CNPJ, e-mail, subject OIDC ou slug.
- AGD-041 é dependência para cobrar/confirmar plano; uma escolha de plano não será registrada como assinatura paga.

## Escopo e critérios verificáveis

- Trial `TRIALING` de 14 dias corridos, sem cartão, somente para elegibilidade confiável e única; início e fim visíveis ao administrador.
- Catálogo administrativo exibe planos mensal recorrente e anual com equivalência de dez mensalidades, sem preço/moeda fictícios.
- Estado temporal efetivo impede novas reservas públicas após `trial_end`; transição persistida a `SUSPENDED` segue o estado aprovado no documento 02. Reservas existentes, consultas/cancelamentos do cliente e operações administrativas permitidas permanecem intactas.
- Recriação de tenant não pode produzir novo trial para a mesma identidade de elegibilidade. Este critério fica pendente até a decisão da fonte de identidade.
- Seleção de plano não cria estado `ACTIVE` nem prova pagamento; nenhuma API de checkout/pagamento é criada.
- Identidade do tenant e autorização administrativa vêm da sessão/vínculo verificado no servidor. O gate de nova reserva é revalidado no servidor, inclusive no commit público; falha/ausência de registro comercial não abre acesso.

## Contratos e consistência

- `GET /api/v1/admin/subscription`: plano/estado/datas do tenant autenticado e catálogo sem valores monetários. Resposta `no-store`; sem `tenantId` de entrada.
- O plano escolhido e a proporção anual não alteram assinatura. Comando para criar trial só será integrado ao provisionamento quando a identidade comercial aprovada puder ser obtida de fonte confiável.
- Consulta pública pode tratar trial vencido como inelegível imediatamente usando `Clock`; qualquer materialização `TRIALING → SUSPENDED` usa CAS e grava histórico/auditoria/outbox previstos na mesma transação. Não aplicar grace period ao trial: documento 02 prevê `TRIALING → SUSPENDED` no fim; sete dias pertencem somente a `PAST_DUE` após vencimento de cobrança.
- A autorização de nova reserva pública deve tomar leitura coerente da assinatura sob a mesma proteção tenant-scoped do commit. Consulta de disponibilidade/publicação também não pode anunciar aceitação de novas reservas quando inelegível; sem alterar as regras de acesso a reservas já existentes.
- Sem alterar migrations publicadas V1–V29. Próxima migration V30 apenas se schema funcional for necessário, com grants mínimos e testes de instalação/upgrade/validate/rerun.

## Estratégia e riscos

1. Registrar contrato, questão de identidade e evidência de integração de AGD-033.
2. Implementar modelo de assinatura/trial, persistência e consulta administrativa; preservar política ausente como estado explícito, nunca como trial ativo.
3. Aplicar o gate comercial aos caminhos públicos de leitura e de commit de nova reserva, sem tocar cancelamento/consulta de reservas existentes.
4. Entregar UI de planos e dados de trial; estado sem elegibilidade deve explicar que a inicialização aguarda identidade confiável, sem oferecer ação que simule aprovação.
5. Testar limites com `Clock`, isolamento, concorrência, replay/CAS aplicáveis e preservação de reservas; E2E Keycloak/PostgreSQL/Chrome com fixtures só em perfil de teste.

Trade-off: fail-closed pode impedir novas reservas enquanto trial não estiver provisionado, mas evita dar acesso gratuito sem identidade comercial verificável. Este incremento não autoriza fornecedor ou ambiente de pagamentos.

## Progresso

- [x] 2026-10-21 — PR #21 AGD-033 integrado por merge commit `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d`; App CI e CodeQL pós-merge completos e verdes.
- [ ] Decidir identidade comercial verificável antes de ligar concessão automática de trial.
- [x] 2026-10-21 — catálogo de planos no domínio e `GET /api/v1/admin/subscription`, com `no-store`, sem preços/moeda e sem implicar pagamento; painel administrativo apresenta mensal/anual e deixa explícita a elegibilidade pendente.
- [x] 2026-10-21 — contrato OpenAPI e regressão de browser adicionados para autenticação, ausência de trial provisionado e anual = dez mensalidades.
- [ ] Persistir concessão/estado e conectar expiração `TRIALING → SUSPENDED` ao gate server-side de novas reservas; depende de identidade comercial verificável aprovada.
- [ ] Conectar criação real de tenant elegível à concessão única de trial. Não conceder por tenant UUID, slug, e-mail ou subject OIDC presumido.
- [x] 2026-10-21 — validação local da fatia independente: `cd backend && ./mvnw -B -ntp spotless:check test` (79 testes) e `./mvnw -B -ntp spotless:check -Dtest=SecurityBoundaryTest,PlanCatalogTest test` (8 testes) passaram com JDK 21; frontend format/lint, 23 Vitest, TypeScript/build e `node --check` passaram.
- [ ] E2E local real não executado: Compose compartilhado tem somente PostgreSQL/Keycloak, sem backend/frontend; não foi reiniciado. E2E de PR/CI ficará pendente até publicação.
- [ ] Revisão independente indisponível pelo limite de threads; revisão sequencial final ainda pendente.
- [x] 2026-10-21 — PR draft [#22](https://github.com/LsaBarbosa/kronos_agendamento/pull/22) aberto para `main`, commit funcional `2c972dd17b46466bc2d08b950ce35a0396cd527f`; sem merge/deploy.
- [x] 2026-10-21 — App CI/CodeQL push e PR concluídos em `33a4cb0c3788257a85ecdc3e54c6c81747b6813f`; todos os jobs/check-runs passaram, incluindo E2E browser. E2E local não executado.
- [ ] Homologação e gates comerciais/legais continuam fora da evidência local/CI.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Integração AGD-033 | App CI run `39586871605`; CodeQL run `39586871623` | todos jobs/check-runs success no merge SHA | App CI completo success; CodeQL ambos analisadores completos success; SHA `5df1035d08a2cf2f39705b9fbc4f7ed2412da90d` |
| Regressões existentes/backend | `cd backend && ./mvnw -B -ntp spotless:check verify` com JDK 21 | testes unitários e integração PostgreSQL passam | BUILD SUCCESS; 79 unitários/arquiteturais + 227 integrações PostgreSQL 16.13/Testcontainers |
| Catálogo de planos/API segura | teste `PlanCatalogTest`, `SecurityBoundaryTest`; browser E2E no CI | dois planos, anual = 10, sem preço, sessão obrigatória e no-store | teste backend dirigido passou (8 testes); browser E2E local não executado, aguardando CI |
| Trial elegível/único | PostgreSQL com identidade de elegibilidade aprovada | 14 dias, apenas uma concessão | Pendente da decisão sobre fonte/identificador |
| Gate de novas reservas | Testcontainers + commit público, teste concorrente | elegibilidade atual revalidada, sem afetar reservas existentes | Pendente |
| Interface/admin | Keycloak e browser E2E | plano e datas/estado corretos após reload | Pendente |
