# AGD-032 — Faltas em janela móvel

## Identificação e objetivo

- Implementar política tenant-scoped de faltas, leitura explicável e correção administrativa, sem concluir AGD-033.
- Referências: documento 01 §10; documento 02 §§4.6/6; documento 03 seção Clientes e restrições; documento 06 critérios AGD-032/E2E-06; ADR-0018 e ADR-0021.
- Base: main integrada em `de4be40aa99e1b9db64527f25cb02552f786dc46`; branch `agd-032-faltas-janela-movel`.

## Escopo e exclusões

- Inclui: política de limite 1–5 por tenant; leitura administrativa tenant-scoped de eventos `NO_SHOW`; contagem explicável na janela de 12 meses; correção explícita e auditada; controles de autorização/CAS/idempotência e efeitos transacionais necessários.
- Exclui: bloqueio público, restrição efetiva em novas reservas, desbloqueio e reativação concorrente de restrições (AGD-033); não altera `CONFIRMED` por edição genérica; não implementa faltas artificiais nem integra fornecedores.
- `NOT_TRACKED` permanece fronteira não implementada; não significa “sem restrição”.

## Estado e dependências

- `NO_SHOW` já é estado terminal persistido em `appointment`, com `appointment_history`, auditoria e `ClientNoShowRecorded` transacionais (AGD-023).
- `clients` ainda fornece `UntrackedClientBookingRestrictionQuery`; não há política persistida nem consulta de faltas.
- Última migration integrada: V25 (`V1–V25` preservadas); próxima migration funcional, se necessária: V26.
- Revisão independente não disponível: duas delegações foram recusadas pelo limite de threads; revisão será sequencial e não equivale a aprovação humana.

## Critérios verificáveis e decisões pendentes

| Critério | Evidência esperada | Estado |
|---|---|---|
| Política tenant-scoped configurável de 1–5 | API administrativa, CAS/idempotência/auditoria, PostgreSQL e UI | Implementação independente em andamento; ausência deve aparecer explicitamente como não configurada, sem valor default |
| Contagem de faltas nos 12 meses | Consulta com `Clock`, tenant/timezone e limites definidos, PostgreSQL | Bloqueada pela decisão temporal solicitada ao usuário |
| Eventos explicáveis na UI | Eventos/fatos e correções ordenados, sem dados excessivos | Aguardando decisão temporal e semântica de correção |
| Correção administrativa | CAS/idempotência, compensação e auditoria sem apagar história | Aguardando decisão sobre estado resultante/efeitos da correção |
| Restrição automática de autoagendamento | Não faz parte da prova deste incremento; manter `NOT_TRACKED` onde aplicável | AGD-033 |

Perguntas abertas registradas em `OPEN-QUESTIONS.md`:
1. âncora da janela, limites inclusivos e uso de calendário/timezone;
2. efeito da correção da falta sobre o estado atual da reserva;
3. consequência de uma política de limite ausente.

## Estratégia

1. Implementar somente a configuração de limite e a representação explícita de política ausente enquanto as perguntas não forem respondidas.
2. Após decisão, definir um instante de referência único via `Clock`, obter timezone da fonte confiável e consultar fatos de falta por tenant sem inferir restrição efetiva.
3. Implementar correção como comando específico, preserving estado/história original e registrando fato compensatório, autorização, versão e motivo conforme decisão aprovada.
4. Atualizar o caminho administrativo para mostrar política, contagem e fatos explicáveis; não exibir telefone, dados de terceiros ou `NOT_TRACKED` como resultado de contagem.
5. Usar nova migration somente para schema funcional real, com grants mínimos e rollback integral de auditoria/receipt.
6. Validar com JDK 21, PostgreSQL real/Testcontainers, conexões independentes para concorrência/CAS e browser Keycloak/PostgreSQL.

## Validação

### Follow-up P2 do PR #20

No head-base revisado `6b884853d910354e3f980361d1151b28394a5dae`, foram identificados três defeitos: CAS só criava a política, GETs antigos podiam sobrescrever estado após PUT e respostas gateway ambíguas liberavam a intenção. O ajuste mantém V1–V26; não há mudança de schema. O CAS passa a separar criação exclusiva `expectedVersion=0` da atualização tenant-scoped com versão esperada. A UI usa callback estável, geração/abort para leituras e invalida leituras na mutação; respostas 408/5xx e falhas de transporte preservam body/chave e bloqueiam edição até replay/reconciliação. Rejeições definitivas continuam liberando correção conforme contrato.

Regressões adicionadas: criação v0→v1, atualização v1→v2, replay, versão obsoleta/inicial incorreta, concorrência com um vencedor, rollback de política/auditoria/receipt; E2E com dois GETs ausentes liberados fora de ordem e HTTP 503 após commit seguido de retry com body/chave idênticos, receipt e efeitos únicos conferidos no DOM e PostgreSQL. Em 19/10/2026, JDK21 `./mvnw -B -ntp spotless:check verify` passou: 77 testes unitários/arquiteturais e 205 integrações em PostgreSQL 16.13; `ClientNoShowPolicyIT` passou 7/7. Frontend: format, lint, 23 Vitest, typecheck, build e sintaxe do roteiro passaram. O browser E2E passou no push [39336408761](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39336408761) e PR [39336412642](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39336412642); App CI e CodeQL em push/PR passaram no SHA funcional `0fa225ac92dbc46233993bc578787b230c1c0115`, todos os jobs concluídos. A tentativa inicial do Maven foi bloqueada pelo sandbox no socket Docker; a repetição autorizada passou. Decisões de janela, correção de `NO_SHOW` e política ausente permanecem pendentes em `OPEN-QUESTIONS.md`.

| Critério | Comando/procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Política 1–5, CAS e isolamento | `ClientNoShowPolicyIT` em PostgreSQL real | Apenas tenant autenticado, limites rejeitam inválidos, versão não se perde | Passou 5/5: ausência explícita, tenant isolation, associação revogada, replay único, entradas inválidas, CAS inicial concorrente |
| Janela móvel | Testes com `Clock` e fronteiras | Limites e calendário exatamente conforme decisão de produto | Pendente de decisão |
| Correção e histórico | PostgreSQL: correção, replay, concorrência e rollback | Sem apagar evento/história; efeitos únicos | Pendente de decisão |
| Browser administrativo | Chrome + Keycloak + PostgreSQL | Configura política, vê contagem/eventos e corrige conforme fluxo aprovado | No head de código `b2ddd9fd65b98d1e9ad3eecbd607e6c687c4228e`, App CI push [39332705108](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332705108) e PR [39332710807](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332710807) passaram `oidc-browser-e2e`, incluindo configuração e recarga do limite em banco limpo. No head anterior `5a2e307`, ambos E2Es falharam porque o helper procurava `<select>` dentro do label; corrigido para selecionar `no-show-threshold` pelo ID, sem reduzir assertions. E2E local não comprovado: o ambiente persistido tinha serviços e não corresponde à fixture limpa; nenhum dado foi apagado |
| Gate completo | `cd backend && export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH && ./mvnw -B -ntp spotless:check verify`; frontend gates | Gate aplicável verde | Backend passou: 77 testes unitários/arquiteturais + 203 integrações PostgreSQL; frontend 23 Vitest, format/lint/typecheck/build passou. No head de código `b2ddd9fd65b98d1e9ad3eecbd607e6c687c4228e`, App CI push/PR e CodeQL push/PR concluíram todos os jobs com sucesso: [39332705108](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332705108), [39332710807](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332710807), [39332705265](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332705265), [39332710873](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39332710873). Após a atualização dos registros no commit documental, os checks de App CI/CodeQL push/PR também concluíram com sucesso; ver evidências no `VALIDATION.md` |

## Limites e retomada

- Não declarar bloqueio ou desbloqueio público real até AGD-033.
- Manter pendentes homologação HTTPS/staging, preflight hospedado, segredo gerenciado/rotação, scan completo, retenção e integrações reais.
- Próximo passo: receber as três decisões registradas em `OPEN-QUESTIONS.md`, concluir contagem/correção, revisar, validar, publicar branch e PR; não fazer merge/deploy.
