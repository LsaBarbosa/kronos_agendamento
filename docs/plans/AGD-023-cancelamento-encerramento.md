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
- Não há decisão de produto sobre o primeiro instante permitido para `COMPLETED` ou `NO_SHOW`, inclusive igualdade. OPEN-QUESTIONS registra pergunta específica e proposta conservadora: conclusão não antes de `service_ends_at`; falta não antes de `occupied_until`; igualdade permitida; sem tolerância de dez minutos. O comportamento temporal ficará explicitamente parcial até aprovação.
- Cliente verificado depende de AGD-024/025; política persistida de faltas depende de AGD-032/033; lembretes/notificações efetivos dependem de AGD-030/031.

## Contrato e estratégia

- Endpoints `POST /api/v1/admin/appointments/{id}/cancel`, `/complete` e `/mark-no-show`; cada um tem escopo de idempotência distinto e versionado. Body `{expectedVersion, reason?}`; motivo é opcional conforme a especificação geral de motivo opcional, normalizado e limitado a 500 caracteres.
- Cancelamento administrativo produz `CANCELLED_BY_PROVIDER` com ator `ADMIN/TENANT_ADMIN`. Não representa uma sessão de cliente nem `CANCELLED_BY_CLIENT`.
- Só `CONFIRMED` transiciona. Replays autenticados retornam o receipt original sem revalidar a versão consumida; nova intenção em estado terminal recebe `INVALID_STATE_TRANSITION`.
- Depois de autorização/acesso tenant-scoped e reserva existente, o coordenador verifica/reproduz a chave. Nova execução adquire lock de agenda do tenant e depois lock da reserva, revalida estado/CAS e política temporal, atualiza status/versão e grava história, auditoria, outbox e receipt dentro da transação READ_COMMITTED.
- Todas as transições avançam a revisão da agenda; isso simplifica invalidação de decisões administrativas concorrentes e não altera a configuração. Não há chamadas externas na transação.
- Eventos permitidos: `AppointmentCancelled`, `AppointmentCompleted`, `ClientNoShowRecorded`, payload mínimo `{appointmentId,version}`. Motivo livre só aparece na história; auditoria/outbox guardam metadados allowlisted, sem PII ou texto livre.
- A política pura do domínio para cancelamento pelo cliente recebe o cutoff atual validado, `startsAt` e instante confiável; não existe rota mutável nem identidade de cliente nesta história. A proposta de negar igualdade ao cutoff é conservadora, não aprovada e está em OPEN-QUESTIONS; AGD-024/025 devem integrar autenticação e obter o cutoff da configuração persistida.
- Migration V18 amplia estados, history checks, allowlists de auditoria/outbox e concessões runtime sem alterar V1–V17.
- UI apresenta ações distintas somente em reserva CONFIRMED, bloqueia durante envio/incerteza, mantém chave/corpo/alvo em retry e recarrega lista/detalhe/história após receipt confirmado.

## Progresso e validação

| Critério | Procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Comandos, CAS, replay e efeitos | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=PublicAppointmentIT test` em `backend/` | uma transição terminal e um conjunto de efeitos | Passou: 48 integrações PostgreSQL 16.13/Testcontainers, incluindo os três comandos, replay autorizado, rejeição de estado terminal, CSRF, isolamento e preservação de snapshots |
| Política de cancelamento de cliente | `ClientAppointmentCancellationPolicyTest` em `backend/` | usar o cutoff persistido e instante confiável; sem expor rota até identidade verificada | Implementação pura e 2 testes passaram para antes/no/depois do limite e valores configuráveis válidos. A igualdade bloqueada é proposta não aprovada e consta em OPEN-QUESTIONS; AGD-024/025 integram identidade/rota. |
| Concorrência e rollback | `PublicAppointmentIT` em transações PostgreSQL independentes | vencedor único; rollback preserva estado e ocupação | Passou: cancelamento versus conclusão; cancelamento versus reserva e bloqueio; falha de auditoria reverte estado, versão, história, auditoria, receipt, outbox e liberação da ocupação |
| Contrato, autorização e estados da UI | frontend gates e browser E2E Keycloak/PostgreSQL/Chrome | ações/estado persistido corretos, retry não duplica | Vitest/format/lint/TypeScript/build passaram (23 testes). E2E `d12df7c` falhou por esperar alerta após botão disabled. E2E `1150735` mostrou B fora da data consultada por ter sido movida a `now - 2 days` antes do teste de navegação; fixture agora mantém B visível até após o cancelamento. CI do head seguinte pendente. Compose local de desenvolvimento não foi reiniciado |
| V18 | `TenantPersistenceIT` e `spotless:check verify` | V1–V17 imutáveis; migration e permissões válidas | Passou: V18 em banco limpo e upgrade V17→V18; Flyway validate/rerun e grants mínimos; 20 testes de persistência diretos. Gate completo: 72 testes unitários/arquiteturais + 139 integrações PostgreSQL, todos verdes |
| Regras temporais de conclusão/falta | decisão de produto necessária | limites observáveis aprovados, sem liberar ocupação futura | Pendente; proposta conservadora documentada em OPEN-QUESTIONS |
| Revisão independente | revisores de requisito e transação | achados independentes consolidados | Pendente; threads de agentes indisponíveis nesta execução, revisão sequencial será declarada |
| CI remoto | App CI/CodeQL push e PR no head final | workflows concluídos com sucesso | Pendente; branch/PR ainda não publicados |

## Descobertas e limites

- Estados terminais deixam de satisfazer a constraint de ocupação ativa somente no commit; rollback deve restaurar a linha como `CONFIRMED` e manter a ocupação.
- O evento de falta é o fato integrado desta história, não implementação de contagem ou restrição automática.
- Outbox indica intenção lógica; não comprova invalidação de lembrete ou comunicação entregue.
- Mudanças de status não alteram snapshots ou timestamps históricos de serviço/ocupação.
- Uma falha inicial no `spotless:check verify` revelou mock ausente de `TransitionAdministrativeAppointment` no teste de fronteira de segurança; mock adicionado e a repetição completa passou. A suíte V18 também corrigiu expectativas históricas de contagem e limite de migration, sem modificar migrations anteriores.
- A UI mantém `appointmentId`, body e chave durante resposta incerta. A resposta de transporte libera somente o guard síncrono do POST para permitir retry; `outcomeUncertain` continua bloqueando navegação e alteração incompatível. E2E remoto precisa comprovar o estado renderizado.

## Encerramento ou retomada

- Atualizar este plano, STATUS, VALIDATION e TRACEABILITY somente com comandos/resultados observados.
- Manter AGD-023 parcial se a política temporal aguardar decisão ou integrações futuras exigidas pelo aceite.
- Próximo trabalho não iniciado nesta execução.
