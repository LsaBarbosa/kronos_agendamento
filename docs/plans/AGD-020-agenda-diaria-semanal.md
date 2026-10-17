# AGD-020 — Agenda diária/semanal

## Identificação e objetivo

- História P0 AGD-020, escopo somente consulta administrativa da agenda diária/semanal.
- Referências: documento 01, seção de administração da agenda; documento 02, §§4, 13 e snapshots/história; documento 03, seção “Agenda e reservas”; documento 06, critério AGD-020.
- Entregar filtros, listagem paginada, detalhe e história relevante; interface em timezone IANA do tenant, identificando atendimento, buffer e bloqueios.
- Exclui encaixe, criação/reschedule/cancelamento/conclusão/falta/correção de status, notificações, área de cliente, cache e nova infraestrutura.

## Estado e dependências

- AGD-015 fornece `appointment`, snapshots imutáveis e `appointment_history`; V13 contém índices por tenant/início e tenant/serviço/início, além de índice tenant/appointment/história.
- AGD-012 fornece consulta administrativa de bloqueios ativos e cancelados, com timezone confiável e limite atual de até 366 datas locais.
- O estado confirmado disponível hoje é `CONFIRMED`; não inventar outros estados. Serviço e catálogo podem mudar depois da reserva; a agenda usa o snapshot.
- Tenant, associação atual e papel vêm da sessão OIDC/autorização servidor-side. Capability AGD-016 não participa deste fluxo.
- A branch `agd-020-agenda-diaria-semanal` parte da main integrada no merge commit #11 `1ed82f6f844acf79fd6cb23f184fcbc8ed5f850e`; migrations V1–V14 permanecem imutáveis.

## Contrato e decisões técnicas

- `from` e `to` são datas locais inclusivas no timezone atual do tenant; `from <= to`; máximo de 366 datas locais. Não aplicar o horizonte de novas reservas ao histórico administrativo.
- `status` aceita somente os estados persistidos e documentados (inicialmente `CONFIRMED`); `serviceId` é filtro opcional, sem exigir que o serviço continue ativo.
- Limite padrão de página 50, máximo 100; cursor opaco/keyset baseado em `(starts_at, appointment_id)`, ordenação ascendente e desempate por UUID. Cursor não contém tenant nem concede autoridade; consultas continuam tenant-scoped.
- A listagem considera interseção da ocupação semiaberta com a janela `[início do from, início do dia após to)` no timezone IANA; SQL usa `starts_at < windowEnd AND occupied_until > windowStart`.
- Keyset evita deslocamento causado por inserções anteriores ao cursor. Cada página é uma leitura independente: alterações concorrentes podem surgir apenas após atualizar a consulta; não existe snapshot transacional entre requests. A UI sinaliza paginação parcial e oferece atualização explícita.
- DTOs têm allowlist. Lista/detalhe exibem nome do cliente necessário ao administrador, ID/estado/versão, snapshot do serviço/duração/buffer, início/fim do atendimento, fim da ocupação e timezone. Telefone, dados de autenticação, token, receipt, capability, digest e payloads internos não são retornados. História é derivada de `appointment_history`, nunca uma listagem da auditoria interna.
- Bloqueios são consultados pela API existente e permanecem visíveis mesmo sobrepostos a reservas; cancelados identificam histórico sem ocupar visualmente como ativos. Não inferir disponibilidade a partir da página carregada.
- Sem migration prevista: índices existentes cobrem tenant + início, tenant + serviço + início e história por appointment. Confirmar com plano/volume antes de acrescentar estrutura.
- Consultas não alteram estado e não têm CAS; critério “atualização concorrente não sobrescrita” aplica-se a comandos de mutação futuros, fora de AGD-020.

## Estratégia e evidências esperadas

1. Implementar consulta tenant-scoped no módulo `appointments`, contratos HTTP explícitos e erro não revelador para ID ausente/alheio; manter `anyRequest().denyAll()` e CSRF/security existentes.
2. Especificar OpenAPI de listagem, detalhe e história; usar DTOs próprios e validação de datas, cursor, filtros e limites.
3. Criar interface administrativa com visões diária/semanal, navegação, hoje, filtros, paginação, detalhe/história, estados de carregamento/vazio/erro/sessão/revogação e visualização distinta do buffer e bloqueios.
4. Testar com PostgreSQL real/Testcontainers: tenant isolation, filtros, status/serviço inativo, interseção de intervalos, limites, ordenação/keyset, histórico/snapshot, permissões e ausência de mutações.
5. Executar testes frontend e browser E2E real com Keycloak/PostgreSQL/Chrome, incluindo reserva persistida pela API pública, consulta em ambas as visões, histórico/snapshot/timezone/buffer e bloqueio pelo fluxo administrativo já existente.
6. Revisar diff, executar gates locais, publicar branch/PR para `main` e acompanhar App CI/CodeQL em push e PR. Não fazer merge/deploy.

## Progresso

- [x] Revisar e integrar PR #11 AGD-016: head `18904d1f35ff7737e941836e3d9250701c7fdab6`, merge `1ed82f6f844acf79fd6cb23f184fcbc8ed5f850e`; checks push/PR e pós-merge App CI/CodeQL passaram. Não foi inferida aprovação humana; status e regras consultados permitiram o merge autorizado.
- [x] Implementar APIs, contratos e interface AGD-020.
- [x] Executar browser E2E real no CI. Backend `spotless:check verify` passou: 69 unitários/arquiteturais + 99 integrações PostgreSQL 16.13/Testcontainers; frontend passou Prettier/Oxlint, 23 Vitest, TypeScript/Vite build. Chrome/Keycloak/PostgreSQL E2E passou no SHA `5087d2d510c3c566b694cb923f69e0dc18cca665` em App CI push/PR.
- [x] Publicar PR AGD-020 [#12](https://github.com/LsaBarbosa/kronos_agendamento/pull/12), branch `agd-020-agenda-diaria-semanal`, head validado `5087d2d510c3c566b694cb923f69e0dc18cca665`; App CI push/PR `39029288453`/`39029291785` e CodeQL push/PR `39029288460`/`39029291781` passaram. PR permanece aberto; não fazer merge/deploy nesta execução.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Consulta administrativa tenant-scoped | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` (`backend/`) | Suíte completa passou: 69 unitários/arquiteturais + 99 integrações em PostgreSQL 16.13/Testcontainers; HTTP próprio tenant, ID alheio/inexistente não revelador, detalhe/história e DTO allowlist. |
| Intervalo, filtros, paginação | Mesmo `verify` focal; métodos `administratorAgendaUsesStableKeysetPagesAndValidatesWindowLimits` e `administratorAgendaIncludesOccupancyThatStartsBeforeTheLocalDateWindow` | Passou em PostgreSQL real: limites/filtros/cursor keyset e inclusão de ocupação que começa antes da janela e nela intersecta. |
| Snapshot e história | Mesmo `verify` focal; consulta HTTP após confirmação e alteração do catálogo | Passou: snapshot histórico preservado e `appointment_history` inicial consultada sem telefone/capability. |
| Interface | `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH npm run format:check && npm run lint && npm test -- --run && npm run check` (`frontend/`) | Passou em 17/10/2026: Prettier, Oxlint, 23 Vitest, TypeScript e Vite build. |
| Browser E2E | `node --check scripts/dev/test-keycloak-browser.mjs`; job `oidc-browser-e2e` | Passou em Chrome/Keycloak/PostgreSQL no SHA `5087d2d510c3c566b694cb923f69e0dc18cca665`, push run `39029288453` e PR run `39029291785`: consulta real da reserva persistida, detalhe/história, snapshot/timezone/buffer, reload, bloqueio e estados de paginação/rede/401. |
| CI remoto | App CI e CodeQL em push/PR no SHA final funcional | Todos concluíram sucesso: App CI push/PR `39029288453`/`39029291785`; CodeQL push/PR `39029288460`/`39029291781`. |

## Descobertas e decisões

- `appointment_history` atualmente contém somente o evento inicial `APPOINTMENT_CREATED`; não fabricar transições futuras.
- V13 tem índices apropriados para consultas tenant-scoped de agenda/história; qualquer índice adicional requer evidência de plano de consulta/volume.
- Bloqueios existentes devem usar a API atual; não copiar motivos para logs nem ocultar overlap visual.

## Revisão e limitações

- Revisões independentes foram solicitadas, mas não puderam iniciar: limite de threads de subagentes atingido. O coordenador revisa sequencialmente; isso não equivale a parecer independente ou aprovação humana.
- Não existe migration AGD-020: V13 já possui índices por tenant/início, tenant/serviço/início e tenant/reserva/história. Bloqueios reutilizam o contrato AGD-012; reservas têm limite e paginação keyset.
- PR #11 foi integrado conforme autorizado; nenhuma migration publicada foi alterada. PR #12 contém AGD-020 e permanece aberto, sem merge/deploy. Checks do head funcional passaram; homologação/staging e pendências gerais de negócio/infra permanecem nos registros globais.
- Falhas intermediárias do browser E2E nos heads `cdad68e` e `888ae56` foram investigadas: primeiro o script consultava o controle antes de a agenda autenticada renderizar após reload; depois, o cenário de sessão 401 concorria com refresh automático. Esperas por estado carregado/refresh habilitado corrigiram o teste; o head `5087d2d` passou sem relaxar assertions.
