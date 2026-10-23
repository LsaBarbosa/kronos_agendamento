# AGD-043 — Reativação e renovação

## Identificação e objetivo

- Incremento dependente de AGD-042; branch `agd-043-reativacao-renovacao`, baseada no head `1d434a83ba63b4e247b0ca675c5db1a2b13c600e` do PR #24.
- Tornar a confirmação financeira de renovação parte do ciclo de pagamento já persistido, reativar sem duplicar efeitos e materializar intenção deduplicada para aviso anual sete dias antes.
- Referências: `docs/spec/app-agendamento-01-especificacao-mvp.md` §§12.1, 12.4–12.5; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` §4.7 e transições; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção Assinatura e AGD-041/042; documento 06 AGD-043; ADR-0026/0027.

## Estado e dependências

- AGD-041 já autentica eventos financeiros, registra inbox, correlaciona operações de checkout e atualiza estado/histórico/auditoria/outbox numa transação.
- AGD-042 já fornece ciclo de inadimplência, tentativas lógicas D0/D+3/D+6, identidade externa estável, claim/lease/fencing e autorização local revalidada sob lock. Uma tentativa só é iniciada com autorização e transporte presentes.
- A confirmação de webhook atualmente correlaciona apenas `billing_checkout_operation`; o resultado aceito de uma tentativa Dunning não tem caminho autoritativo de liquidação correlacionado.
- V1–V36 permanecem imutáveis. V37 é a migration funcional desta branch: vínculo entre attempt e inbox, snapshot contratual imutável por tentativa, intenção anual, preflight de legado e privilégios de correlação mínimos.
- Fornecedor, preço/moeda/oferta, autorização recorrente confiável, credenciais gerenciadas e homologação permanecem ausentes; produção continua fail-closed.

## Escopo

- Correlacionar evento autenticado a uma tentativa Dunning aceita pela referência/provedor/conta, validar snapshot financeiro do servidor, deduplicar/ordenar e aplicar pagamento sob o lock de tenant existente.
- Preservar retry técnico e identidade lógica do attempt; pagamento sela ciclo, cancela só o que ainda não começou e reativa imediatamente sem repetir versão/histórico/auditoria/outbox.
- Restringir renovação automática ao mandato mensal confiável; renovação anual usa porta distinta de autorização correspondente ao período e falha fechada sem essa evidência.
- Materializar intenção anual deduplicada no instante `paidThroughAt - 168h`, obsoletando-a quando a versão/período corrente mudar. Sem transporte de notificação configurado, não enviar nem registrar entrega.
- Exibir contrato, estado de autorização, intenção anual e tentativas existentes na área administrativa.

## Exclusões

- Nenhum fornecedor, sandbox, oferta, preço, moeda, termos, mandato ou autorização comercial é aprovado; sem cobrança ou notificação real.
- Não criar outra máquina de cobrança: Dunning de AGD-042 continua como autoridade de tentativas e tolerância.
- Sem mudança no TTL, trial, tolerância ou política de AGD-042; sem AGD-044, cobrança manual adicional ou integração WhatsApp.
- Não inferir datas de vigência. O `paidThroughAt` recebido em evento autenticado permanece dado autoritativo do contrato do provedor; a regra comercial de sua âncora/duração e do pagamento antecipado/atrasado continua pendente.

## Critérios verificáveis

| Critério | Evidência esperada |
|---|---|
| Pagamento de ciclo Dunning reativa e fecha retries uma vez | PostgreSQL/Testcontainers: inbox vinculada ao attempt; status/período/histórico/auditoria/outbox e receipt consistentes; reentrega não repete efeitos |
| Eventos antigos/duplicados não sobrescrevem período mais recente | eventos concorrentes/fora de ordem, comparação monotônica de `paidThroughAt` e sequência |
| Mensal só inicia chamada com mandato mensal atual | Clock + locks; autorização revogada/ausente nega antes do transporte |
| Anual não reutiliza mandato mensal | porta específica de autorização anual por período; ausência falha fechada |
| Aviso anual é persistido uma vez e obsoleto após alteração | Postgres, períodos/versões e concorrência de materialização |
| UI comunica estado sem afirmar pagamento/entrega | testes React e browser E2E sintético, reload e isolamento do contexto |
| Produção sem integrações continua sem efeitos externos | teste de ausência de transport/verifier e E2E isolado |
| V37 e privilégios são seguros | instalação limpa, V36→V37, Flyway validate/rerun, preflight/grants |

## Decisões técnicas e limites

- Um ciclo Dunning existente representa a cobrança lógica associada ao vencimento; tentativas D0/D+3/D+6 não criam uma segunda assinatura nem outro ledger.
- `billing_event_inbox` aceitará vínculo exclusivo com operação de checkout ou attempt Dunning; correspondência exige provider, account, referência, valor e moeda persistidos no servidor. Uma evidência sem vínculo permanece `UNLINKED`; não altera estado.
- O evento confirmado adquire `TenantAgendaLock` antes de ler a assinatura, attempt e ciclo. Usa `READ_COMMITTED`, locks de linha e sequência monotônica; uma referência de ciclo já concluída não pode regredir a assinatura.
- Pagamento confirmado poderá ser registrado mesmo quando o ciclo foi suspenso, preservando a regra AGD-042 de evidência de chamada já iniciada. Eventos de período não mais recente são registrados como `OLDER` e não alteram estado/período.
- O aviso anual usa uma intenção independente da tabela de notificações de reservas: essa tabela exige destinatário/contrato de appointment e não se aplica à comunicação comercial. Uma tabela mínima de intents não implica transporte nem entrega.
- `168h` é usado como instante técnico de agenda do aviso; se produto exigir sete dias de calendário local, isso precisa de decisão explícita. Não há template ou destinatário/fornecedor aprovado; estado observável permanece não entregue.
- Nenhuma transação de banco abrange uma chamada externa. Resultado ambíguo permanece no mesmo attempt e exige reconciliação suportada antes de nova cobrança.
- O snapshot em `billing_subscription_contract` representa os termos correntes para novos ciclos. Ao materializar ciclo, cada tentativa recebe cópia própria e imutável de valor, moeda, `termsVersion` e `offerVersion`; claim/autorização/webhook usam essa cópia mesmo que checkout posterior atualize o contrato corrente.
- A V37 preenche tentativas legadas somente quando há evidência de termos confirmados registrados até a criação do ciclo. Tentativa legada já iniciada sem esse vínculo faz a migration falhar em transação; não há preço inventado nem correção automática. Operação deve inspecionar/reconciliar antes do upgrade.
- O E2E browser injeta uma intenção anual sintética para provar projeção administrativa/reload. Materialização real da intenção é comprovada pelos testes PostgreSQL de `SubscriptionTrialIT`, não pelo fixture browser; nenhum fornecedor ou entrega é simulada como sucesso.

## Decisões de produto pendentes

- Âncora e duração comercial de período mensal/anual, incluindo pagamento antecipado/atrasado. Proposta a deliberar: aceitar somente o `paidThroughAt` autenticado do provedor e não calculá-lo localmente; exige contrato explícito do provedor e aprovação comercial.
- Semântica de mensal/anual quando o pagamento confirmado chega depois de período mais recente: este incremento protege monotonicidade e não substitui período por evidência mais antiga; regras de crédito/prorrogação permanecem pendentes.
- Se aviso de sete dias significa 168 horas exatas ou sete dias de calendário/zona, e canal/destinatário/retentativas quando não há fornecedor.
- Autorização anual por renovação e autoridade que pode concedê-la. A porta permanece indisponível; a UI não oferece consentimento simulado.

## Estratégia de execução e testes

1. Acrescentar porta de autorização anual, bloquear uso de mandato mensal para plano anual e adicionar correlação de attempt Dunning em webhook.
2. Criar V37 com preflight, constraints completas, FK tenant-scoped, unicidade, snapshots por attempt e grants mínimos (incluindo revogação de UPDATE amplo herdado na inbox).
3. Persistir/obsoletar intents anuais com Clock e apresentar na consulta administrativa; sem adaptador de entrega em produção.
4. Testar eventos, ordenação, reativação, autorizações, retries, aviso, tenant isolation, rollback e worker races com PostgreSQL real/conexões independentes.
5. Atualizar OpenAPI, especificação, ADR, STATUS, VALIDATION e TRACEABILITY; revisar achados de requisito/segurança independentemente quando houver revisores disponíveis.

## Progresso

- [x] Base remota confirmada: PR #24 draft aberto, head `1d434a83ba63b4e247b0ca675c5db1a2b13c600e`; branch de trabalho derivada desse head.
- [x] Mapeados GAPs: inbox não correlaciona attempts Dunning; a autorização genérica atual pode aceitar plano anual; intenção anual ainda não existe.
- [x] Implementação backend/migration e testes PostgreSQL direcionados, incluindo snapshot por tentativa e grants inbox restritos.
- [x] Interface/admin e roteiro E2E sintético (fixture prova exibição persistida, não materialização pelo worker).
- [ ] Gates completos do backend/frontend, revisão final e CI remoto no head de publicação.

## Achados da revisão independente e resolução

- **P1 — validação de pagamento com contrato mutável:** um checkout posterior podia substituir o valor/moeda corrente e fazer evidência autenticada de um attempt já iniciado ser classificada como `MISMATCHED`. Resolvido no mesmo incremento: snapshot financeiro persistido por attempt e usado por claim, autorização e reconciliação; teste PostgreSQL altera o contrato corrente após `ACCEPTED` e comprova evento original `APPLIED`, uma reativação e um único fato histórico.
- **P2 — privilégio de inbox:** grant de tabela `UPDATE` de V32 continuava efetivo apesar do grant parcial de V37. Resolvido em V37 por `REVOKE UPDATE` de tabela seguido de grant somente nas colunas de associação/correlação. `TenantPersistenceIT` comprova `has_table_privilege(..., 'UPDATE') = false`, colunas de correlação permitidas e payload financeiro sem UPDATE.
- **P2 — alcance do browser E2E:** o teste de UI semeia uma intenção anual persistida; não executa o worker para criá-la. A criação/deduplicação e obsolescência permanecem demonstradas em PostgreSQL real. O E2E não é apresentado como prova do worker nem de notificação real.
- Revisões feitas em leitura por dois revisores independentes (requisitos/estados e persistência/segurança). Não substituem aprovação humana no GitHub.

## Validação

| Critério | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Correlação de pagamento com renewal attempt | `./mvnw -B -ntp spotless:check '-Dit.test=SubscriptionTrialIT,BillingWebhookIT' verify` | Testcontainers: evento autenticado vincula, liquida e deduplica com termos imutáveis da tentativa | Classes direcionadas passaram: 80 unitários/arquiteturais + 37 integrações PostgreSQL; o caso de contrato atualizado prova um único período/histórico e `APPLIED` |
| Autorização mensal/anual | `SubscriptionTrialIT` com Clock | Mandato mensal distinto da autorização anual; default fail-closed | Cobertura direcionada passou dentro das 23 integrações da classe; porta anual indisponível sem fonte configurada |
| Aviso anual e obsolescência | PostgreSQL/Testcontainers e E2E | Uma intenção por período, sem alegação de envio | `SubscriptionTrialIT` comprovou materialização/deduplicação/obsolescência; browser usa fixture persistida e comprova apenas projeção/reload, não execução do worker |
| Schema V37 | `TenantPersistenceIT` | Fresh, V36→V37, validate/rerun/grants mínimos e legado sem snapshot falha sem reparo | 39 integrações PostgreSQL passaram, incluindo upgrade, validate/rerun, grant por coluna e preflight fail-closed |
| Corridas temporais de AGD-042 | `BillingWebhookIT` | Igualdade e espera do lock não iniciam envio; instante anterior permite uma chamada | 14 integrações passaram; fixtures incluem snapshot/conta/ref e isolam ciclo de outros tenants sem reduzir estado/transport assertions |
| Suíte completa backend | `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify`, JDK 21 | Todos os testes unitários e Testcontainers passam | Passou: 80 unitários/arquiteturais e 277 integrações PostgreSQL; 0 falhas, erros ou skips |
| CI sobre base PR #24 | App CI/CodeQL push e PR | Jobs concluídos; falha de segurança herdada do pin Keycloak explicitamente separada | No SHA `b293ec2`, CodeQL push/PR passou. App CI PR passou verify/frontend/E2E/secrets; push passou nesses jobs exceto E2E, que expirou em duas tentativas seletivas em pontos diferentes sem erro final explicativo. Security push/PR falhou no scan Keycloak herdado. Causa do E2E push permanece indeterminada. O fixture sem `provider_account_id` do SHA inicial foi corrigido. Links em VALIDATION; scan de imagem não aprovado |

## Retomada

Branch `agd-043-reativacao-renovacao` é dependente do PR #24. O PR deve ter base `agd-042-inadimplencia-retries-suspensao`, permanecer draft e conter somente commits AGD-043 acima do head base. Não fazer merge, deploy ou operação externa.
