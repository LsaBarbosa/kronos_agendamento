# AGD-032 — Faltas em janela móvel

## Identificação e objetivo

- Incremento do PR #20 na branch `agd-032-faltas-janela-movel`, continuação sobre a referência revisada `d7ce49cc5c536a20d00d62ac935033be77258caa`.
- Tornar a configuração opcional de limite funcional com consulta explicável e correção administrativa compensatória, sem implementar bloqueio/desbloqueio de AGD-033.
- Requisitos: documento 01 §6.4; documento 02 §§4.6/6; documento 03 contratos administrativos de clientes/faltas; documento 06 AGD-032 e E2E-06. Decisões desta instrução em ADR-0023.

## Estado e dependências

- V26 já persiste `client_no_show_policy` tenant-scoped, limite 1–5, CAS/idempotência e auditoria. V27 adiciona referência íntegra à correção compensatória e índice tenant/data para fatos.
- `appointment_history` persiste fatos originais, inclusive `NO_SHOW`, e a infraestrutura atual já fornece lock por tenant, CAS, idempotência, auditoria e outbox.
- AGD-033 não materializa restrição real; `NOT_TRACKED` permanece independente da política `NOT_CONFIGURED/CONFIGURED`.
- Não alterar migrations publicadas V1–V26. V27 é funcional e contempla referência única da história corrigida, FK composta, índice de consulta e allowlists de auditoria/outbox.

## Escopo

- Consultar contagem e fatos `NO_SHOW` por cliente e tenant, com política, janela e instante de avaliação.
- Corrigir somente `NO_SHOW → COMPLETED`, mediante motivo obrigatório, CAS e chave idempotente.
- Interface administrativa para localizar cliente, revisar fatos e corrigir com confirmação explícita.
- Excluir bloqueio/desbloqueio, decisões de cliente, defaults, efeitos públicos, fornecedor de notificação e edição genérica de estados terminais.

## Critérios verificáveis

1. Uma avaliação captura um único `asOf` via `Clock`; calcula 12 meses de calendário UTC e intervalo `[windowStart, asOf)`, ajustando dia inválido ao último dia do mês-alvo. O fato é o `occurred_at` original de `NO_SHOW`; timezone do tenant apenas apresenta.
2. Cada falta não corrigida aparece uma vez. Cancelamento/conclusão não contam. A correção referencia o fato original, preserva reservas/snapshots, incrementa versão exatamente uma vez e remove somente a contribuição corrigida.
3. Política ausente é `NOT_CONFIGURED`, sem limite padrão; fatos/contagem são consultáveis e não inferem `CLEAR` nem restrição. Com política configurada, exibir limite 1–5. Restrição real continua `NOT_TRACKED` até AGD-033.
4. Tenant/ator decorrem da sessão OIDC e associação atual; leitura/correção entre tenants ou após revogação são rejeitadas sem revelar dados.
5. Mutação, história compensatória, auditoria, receipt idempotente e `ClientNoShowCorrected` são atômicos; replay autorizado não duplica efeitos.
6. Consultas explicáveis retornam `asOf`, fronteiras, timezone, política, contagem e fatos minimizados em leitura coerente. Paginação preserva `asOf` e ordenação keyset, sem alegar snapshot físico entre requests.

## Decisões e contrato

- Produto aprovou em 19/10/2026 (ADR-0023): instante único; 12 meses de calendário UTC; `[start,asOf)`; correção administrativa apenas `NO_SHOW → COMPLETED`, motivo obrigatório e compensação referenciada; ausência de política `NOT_CONFIGURED` e distinta de `NOT_TRACKED`.
- `GET /api/v1/admin/clients/{id}/no-shows?cursor=&limit=` retorna fatos da janela e metadados; sem PII além do nome administrativo minimizado quando já autorizado.
- `POST /api/v1/admin/appointments/{id}/correct-no-show` recebe `expectedVersion` e `reason`, usa `Idempotency-Key`; autorização revalidada antes do replay.
- Correção toma lock do tenant e da reserva pelo protocolo existente. Consulta e count/facts usam snapshot transacional/repeatable-read. Cursor mantém `asOf` e posição; o escopo de tenant/cliente vem sempre do request autorizado, não do cursor.
- História compensatória registra `corrects_history_id` único; V27 reforça integridade da relação e allowlists de auditoria/outbox. Runtime recebe apenas grants necessários.

## Estratégia de teste

- PostgreSQL/Testcontainers: fronteiras UTC, subtração calendário/ano bissexto, contagem explicável, isolamento, revogação, correção/replay/digest/CAS, disputa concorrente, rollback e grants.
- Teste coerente de count/facts durante correção sob transação de leitura.
- Frontend: estados de sessão, carregamento, busca de cliente, fatos, motivo, conflito, resposta perdida/retry e descarte de resposta após troca/encerramento.
- Browser E2E com Keycloak/PostgreSQL: consulta, correção, contagem/história atualizadas, reload e recovery de resposta perdida com efeito único.
- Preservar testes da política de limite, GET atrasado e 503 já corrigidos.

## Progresso e validação

| Critério | Procedimento | Resultado observado |
|---|---|---|
| Decisões registradas | requisitos 01/02/03/06, ADR-0023, plano, contratos e OPEN-QUESTIONS | Aprovadas nesta instrução de 19/10/2026; removidas das questões de produto |
| Consulta explicável e calendário | `ClientNoShowFactsTest`, `PublicAppointmentIT`, PostgreSQL/Testcontainers | Maven passou: `PublicAppointmentIT` 55/55; ano bissexto, início inclusivo, `asOf` exclusivo, política ausente/configurada, fatos únicos e timezone de apresentação cobertos |
| Correção compensatória e atomicidade | `PublicAppointmentIT`, PostgreSQL real: CAS/replay/rollback/duas transações | Maven passou: replay/digest, CAS, disputa com um vencedor, rollback, associação revogada, história original + referência, auditoria/outbox/receipt únicos e snapshots preservados |
| Migration e grants | `TenantPersistenceIT`, instalação limpa e upgrade V26→V27 | V27 passou; `TenantPersistenceIT` 26/26, Flyway validate/rerun e runtime grants mínimos; Flyway validou 28 migrations descobertas, incluindo a repetível existente |
| UI e recuperação | Vitest, script `scripts/dev/test-keycloak-browser.mjs`, Chrome E2E autenticado | UI implementada; 23 Vitest, format/lint/typecheck/build e sintaxe E2E passaram. E2E local não executado para não reutilizar Compose/volumes e tenant local; job limpo no CI do PR é obrigatório antes de retirar draft |
| CI remoto | App CI/CodeQL push e PR no SHA final | Pendente publicação e conclusão de todos os jobs/check-runs |
| Homologação | ambiente HTTPS/staging | Pendente operacional; CI não equivale a homologação |

## Retomada/limites

- AGD-032 permanece parcial enquanto qualquer prova obrigatória ou CI final não passar.
- Gates locais em 19/10/2026 no código `97bd2628232be453c989fab78c2170a9d4f2df02`: JDK 21 `./mvnw -B -ntp spotless:check verify` — BUILD SUCCESS, 78 testes unitários/arquiteturais + 210 integrações PostgreSQL 16.13/Testcontainers; `ClientNoShowPolicyIT` 7/7, `PublicAppointmentIT` 55/55, `TenantPersistenceIT` 26/26. Frontend `npm run format:check && npm run lint && npm test -- --run && npm run check` — passou, 23 Vitest, TypeScript/build; `node --check` no roteiro E2E e parse YAML OpenAPI via Prettier passaram. A execução integral anterior falhou em uma fixture que usava horários fora do passo/ocupação e quatro expectativas de contagem de histórico; ambos corrigidos sem reduzir assertions, e a execução final integral passou.
- Revisão final sequencial; agentes independentes indisponíveis pelo limite de threads, sem alegação de revisão independente nem aprovação humana. Limites: fatos e contagem são tenant-scoped e coerentes por `REPEATABLE_READ`; páginas independentes não prometem snapshot imutável. `NOT_TRACKED` não materializa bloqueio/desbloqueio nem substitui AGD-033. Nenhuma retenção nova, dado em log ou PII adicional foi criada.
- Pendente antes de retirar draft: E2E browser limpo no CI, App CI/CodeQL push e PR em SHA final, conferência de todos os jobs/check-runs, publicação e revisão humana. Sem merge/deploy.
