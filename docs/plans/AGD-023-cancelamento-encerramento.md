# AGD-023 — Cancelamento e encerramento

## Identificação e objetivo

- Implementar comandos administrativos separados para cancelar pelo prestador, concluir e registrar falta, mantendo a identidade e snapshots da reserva.
- Referências: documento 01 §9, documento 02 §§4.5, 6, 9 e 10, documento 03 §5 e eventos, documento 06 AGD-023/E2E-05/E2E-06.
- Branch `agd-023-cancelamento-encerramento`, baseada na main integrada em `1fb90e97912fc014a93e1eaafc276ee510eaa0ef` (confirmada com `git fetch origin main`).
- Fora do escopo: cancelamento autenticado do cliente, correção/reabertura de estado terminal, contagem móvel/restrições, jobs de lembrete e envio por fornecedor.

## Estado e dependências

- Existem criação e remarcação administrativa com autorização OIDC, CSRF, lock tenant-first, CAS, coordenador de idempotência, auditoria, história e outbox transacionais.
- `appointment` só admite `CONFIRMED`; a história inicial e remarcação têm checks estritos V13/V16/V17. A próxima migration necessária é V18.
- A ocupação ativa é definida por `status='CONFIRMED'`; atualizar estado sob a mesma transação remove a ocupação apenas no commit. Leituras de agenda aceitam estados documentados pelo enum.
- **Decisão de produto aprovada pelo usuário em 17/10/2026 nesta instrução (ADR-0018):** `COMPLETED` e `NO_SHOW` exigem `Clock.instant() >= occupiedUntil`, incluindo buffer; igualdade é permitida. Ex.: serviço 09:00–09:30 ocupado até 09:45 só admite encerramento a partir de 09:45. Cancelamento do cliente admite `now <= startsAt - cancellationCutoffHours`, inclusive; cancelamento administrativo continua restrito a `CONFIRMED` sem herdar o cutoff do cliente. Não há tolerância adicional de dez minutos. A rota do cliente continua fechada até AGD-024/025.
- Cliente verificado depende de AGD-024/025; política persistida de faltas depende de AGD-032/033; lembretes/notificações efetivos dependem de AGD-030/031.

## Contrato e estratégia

- Endpoints `POST /api/v1/admin/appointments/{id}/cancel`, `/complete` e `/mark-no-show`; cada um tem escopo de idempotência distinto e versionado. Body `{expectedVersion, reason?}`; motivo é opcional conforme a especificação geral de motivo opcional, normalizado e limitado a 500 caracteres.
- Cancelamento administrativo produz `CANCELLED_BY_PROVIDER` com ator `ADMIN/TENANT_ADMIN`. Não representa uma sessão de cliente nem `CANCELLED_BY_CLIENT`.
- Só `CONFIRMED` transiciona. Replays autenticados retornam o receipt original sem revalidar a versão consumida; nova intenção em estado terminal recebe `INVALID_STATE_TRANSITION`.
- Depois de autorização/acesso tenant-scoped e reserva existente, o coordenador verifica/reproduz a chave. Nova execução adquire lock de agenda do tenant e depois lock da reserva, revalida estado/CAS e política temporal, atualiza status/versão e grava história, auditoria, outbox e receipt dentro da transação READ_COMMITTED.
- Todas as transições avançam a revisão da agenda; isso simplifica invalidação de decisões administrativas concorrentes e não altera a configuração. Não há chamadas externas na transação.
- Eventos permitidos: `AppointmentCancelled`, `AppointmentCompleted`, `ClientNoShowRecorded`, payload mínimo `{appointmentId,version}`. Motivo livre só aparece na história; auditoria/outbox guardam metadados allowlisted, sem PII ou texto livre.
- A política pura do domínio para cancelamento pelo cliente recebe o cutoff atual validado, `startsAt` e instante confiável; não existe rota mutável nem identidade de cliente nesta história. A igualdade permitida no cutoff é a decisão aprovada acima; AGD-024/025 devem integrar autenticação e obter o cutoff da configuração persistida.
- Migration V18 amplia estados, history checks, allowlists de auditoria/outbox e concessões runtime sem alterar V1–V17.
- UI apresenta ações distintas somente em reserva CONFIRMED, bloqueia durante envio/incerteza, mantém chave/corpo/alvo em retry e recarrega lista/detalhe/história após receipt confirmado.

## Progresso e validação

| Critério | Procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Comandos, CAS, replay e efeitos | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=PublicAppointmentIT test` em `backend/` | uma transição terminal e um conjunto de efeitos | Passou: 49 integrações PostgreSQL 16.13/Testcontainers, incluindo os três comandos, replay autorizado, rejeição de estado terminal, CSRF, isolamento, snapshots e limites temporais controlados por `Clock`. |
| Política temporal e cutoff de cliente | `PublicAppointmentIT`, `ClientAppointmentCancellationPolicyTest` | limite de ocupação inclusivo para conclusão/falta; cutoff inclusivo de cliente | Decisão aprovada em 17/10/2026 e registrada na ADR-0018. Teste PostgreSQL controlado por `Clock` passou para buffer positivo e zero, um nanossegundo antes, igualdade, depois e replay; política de cliente aceita antes/igual e rejeita depois. Nenhuma rota mutável de cliente foi criada; identidade depende de AGD-024/025. |
| Concorrência e rollback | `PublicAppointmentIT` em transações PostgreSQL independentes | vencedor único; rollback preserva estado e ocupação | Passou: cancelamento versus conclusão; cancelamento versus reserva e bloqueio; falha de auditoria reverte estado, versão, história, auditoria, receipt, outbox e liberação da ocupação |
| Contrato, autorização e estados da UI | frontend gates e browser E2E Keycloak/PostgreSQL/Chrome | ações/estado persistido corretos, retry não duplica | Vitest/format/lint/TypeScript/build passaram (23 testes). E2E `d12df7c` falhou por esperar alerta após botão disabled; `1150735` mostrou B fora da data visual, corrigido mantendo-a visível durante cancelamento; `51dc612` apontou notice oculto após COMPLETED; `bd89044` avançou e mostrou que a história não renderizava versão para evento sem mudança de horário. Notice e versão do evento terminal corrigidos no `df06522b3dc8e6c75ab3848ee1d556d982ee8c83`; App CI E2E push [39061791902](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39061791902) e PR [39061794563](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39061794563) passaram. A regressão exige estado COMPLETED, versão 3, evento e faixa 2→3; Compose local não foi reiniciado |
| V18 | `TenantPersistenceIT` e `spotless:check verify` | V1–V17 imutáveis; migration e permissões válidas | Passou: V18 em banco limpo e upgrade V17→V18; Flyway validate/rerun e grants mínimos; 20 testes de persistência diretos. Gate completo no delta: 72 testes unitários/arquiteturais + 140 integrações PostgreSQL, todos verdes. Nenhuma migration nova foi necessária. |
| Guarda temporal em API e ocupação | `TransitionAdministrativeAppointment`; `PublicAppointmentIT` com PostgreSQL real | rejeitar antes de `occupiedUntil`, liberar na igualdade e preservar a ocupação até então | Aprovado e comprovado: abaixo do limite retorna `INVALID_STATE_TRANSITION`, sem status/version/history/audit/receipt/outbox de sucesso; a linha `CONFIRMED` segue ocupando `[starts_at, occupied_until)`. Na igualdade confirma a transição; replay posterior retorna o receipt sem novos efeitos. |
| Recuperação de contexto na UI | `AdminAgendaPanel.tsx`; browser E2E | conflito permanece associado ao alvo através de remarcação incerta e cancelamento concorrente; retry reconcilia e libera navegação | E2E atualizado para duas reservas, POST pendente, conflito persistido, atualização manual bloqueada, resposta perdida após commit, cancelamento em outra aba, retry com alvo/body/chave iguais e assertivas DOM do estado final. App CI no SHA `48eee8f716dcca14e9b05fb0f498773e1a783727` falhou porque o roteiro tentou preencher data antes de abrir o formulário; corrigido em `73e8a5c98677e994d22433f9acd83d38183c64df`. No head `73e8a5c`, o painel ainda estava com revisão da agenda obsoleta após mutação concorrente e o E2E esperava sucesso; o roteiro agora espera o conflito CAS, atualiza as versões explicitamente e só então testa perda/retry. `node --check` e `git diff --check` passaram localmente; a execução do E2E desse delta está pendente no CI. |
| Revisão independente | revisores de requisito e transação | achados independentes consolidados | Pendente; threads de agentes indisponíveis nesta execução, revisão sequencial será declarada |
| CI remoto | App CI/CodeQL push e PR no head final | workflows concluídos com sucesso | CodeQL push/PR passou nos heads `48eee8f` ([39065313674](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39065313674), [39065315855](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39065315855)) e `73e8a5c` ([39065658602](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39065658602), [39065660509](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39065660509)). App CI push/PR falhou no E2E em ambos: primeiro por não abrir o formulário, depois por não resolver o conflito CAS de revisão obsoleta antes da remarcação. O roteiro foi corrigido para atualizar explicitamente as versões; novos checks no head corrigido ainda serão acompanhados. PR #15 permanece aberto, sem merge/deploy. |

## Descobertas e limites

- Estados terminais deixam de satisfazer a constraint de ocupação ativa somente no commit; rollback deve restaurar a linha como `CONFIRMED` e manter a ocupação.
- O evento de falta é o fato integrado desta história, não implementação de contagem ou restrição automática.
- Outbox indica intenção lógica; não comprova invalidação de lembrete ou comunicação entregue.
- Mudanças de status não alteram snapshots ou timestamps históricos de serviço/ocupação.
- Uma falha inicial no `spotless:check verify` revelou mock ausente de `TransitionAdministrativeAppointment` no teste de fronteira de segurança; mock adicionado e a repetição completa passou. A suíte V18 também corrigiu expectativas históricas de contagem e limite de migration, sem modificar migrations anteriores.
- A UI mantém `appointmentId`, body e chave durante resposta incerta. A resposta de transporte libera somente o guard síncrono do POST para permitir retry; `outcomeUncertain` continua bloqueando navegação e alteração incompatível. E2E remoto precisa comprovar o estado renderizado.

## Encerramento ou retomada

- Atualizar este plano, STATUS, VALIDATION e TRACEABILITY somente com comandos/resultados observados.
- Manter AGD-023 parcial pelas integrações futuras aplicáveis: identidade/rota de cancelamento do cliente (AGD-024/025), jobs/lembretes e fornecedores reais (AGD-030/031), contagem/restrição automática (AGD-032/033), além das pendências operacionais globais. As regras temporais foram decididas nesta instrução e não são mais uma pendência de produto.
- Próximo trabalho não iniciado nesta execução.
