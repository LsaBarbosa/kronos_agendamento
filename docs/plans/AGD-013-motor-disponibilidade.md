# AGD-013 — Motor de disponibilidade

## Identificação e objetivo

- História: AGD-013 (P0). Branch `agd-013-motor-disponibilidade`, baseada na `main` após merge do PR #7, merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`.
- Objetivo: calcular disponibilidade indicativa por tenant, serviço e período sob demanda; não reservar, bloquear nem garantir confirmação.
- Referências: `docs/spec/app-agendamento-01-especificacao-mvp.md` §5; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` §§2, 12 e 13; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção “Disponibilidade”; `docs/spec/app-agendamento-04-arquitetura-implementacao.md` seção do módulo `availability`; `docs/spec/app-agendamento-06-backlog-criterios-aceite.md` AGD-013; ADR-0010 e ADR-0011.
- Inclui cálculo de slots, leitura tenant-scoped da configuração, serviços, bloqueios e porta mínima para ocupações; testes temporais e de propriedade; contrato interno e documentação.
- Exclui confirmação/hold de reserva, cobrança, notificação, página pública, publicação pública, cache, broker e persistência artificial de reservas.

## Estado e dependências

- PR #7 / AGD-012 integrado em merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`; App CI push `38910031408` e PR `38910037394` passaram, incluindo browser E2E. CodeQL push `38910031418` e PR `38910037301` passaram.
- `availability` já persiste grade semanal, política, exceções e bloqueios. `catalog` persiste serviço com status/duração/buffer. `appointments` contém apenas `TimeInterval`; não há reservas persistidas nem repositório de ocupação.
- O gate público continua fechado por deny-all; `Tenant.ACTIVE` ou agenda configurada não equivalem a publicação. AGD-014 define a página e o fluxo público.
- Integração real de reservas, corrida com confirmação e proteção definitiva dependem de AGD-015. A porta de ocupações nesta história é somente leitura e tenant-scoped; adapter de teste comprova a composição, não a integração real.
- Cache não existe. Decisão deste incremento: cálculo sob demanda lendo PostgreSQL, sem adicionar cache ou Redis; `ScheduleChanged` não será tratado como invalidação efetiva.
- Nenhuma migration está prevista: a história deve ser derivada do schema já publicado (V1–V11). Criar migration somente se implementação demonstrar necessidade real de alteração de schema.

## Critérios verificáveis

1. Candidatos exigem serviço ativo, configuração completa, períodos aplicáveis, política, duração e buffer dentro do mesmo período.
2. Exceção `CLOSED` fecha a data; `SPECIAL` substitui a grade semanal; sem exceção, aplica-se a semana. Configuração ausente não equivale a semana fechada/configurada.
3. Bloqueios ativos e ocupações tenant-scoped removem candidatos que intersectem suas ocupações `[início,fim)`; adjacência é aceita e buffer zero é válido.
4. Antecedência, horizonte e consulta são calculados com `Clock` injetado, fuso IANA do tenant e limites documentados após decisão de produto.
5. Não são retornados horários locais inexistentes nem horários ambíguos sem regra aprovada; timestamps de resposta incluem offset e `timeZone`.
6. A consulta é somente leitura, não cria hold/reserva e não libera rota pública nem revela outros tenants, clientes ou motivos.
7. Testes de propriedade demonstram: ocupação não ultrapassa período; candidatos não intersectam bloqueio/ocupação; não há duplicação; mesmo input e `Clock` produzem mesmo resultado.

## Decisões pendentes

- **Âncora da grade de slots:** a especificação não informa se os inícios são ancorados ao começo de cada período ou a múltiplos de parede-relógio. Exemplo discriminante: período começa 09:15 e slot de 30 min. Pergunta enviada ao usuário em 16/10/2026; não fixar comportamento até resposta.
- **Consulta e igualdade de fronteiras:** o contrato diz `from`/`to` como datas locais e janela limitada ao horizonte, sem declarar explicitamente inclusividade de datas nem se início exatamente no limite de antecedência/horizonte é válido. Pergunta enviada ao usuário em 16/10/2026; não fixar filtro observável até resposta.
- A seção de API dá “por exemplo 31 dias” como limite máximo. Será adotado 31 dias como limite da consulta somente se isso não conflitar com resposta/contrato vigente; especificar a convenção de contagem após decisão sobre datas inclusivas.
- Horários locais de início ambíguos por DST serão omitidos (não escolher offset) se confirmado pela regra permissiva do critério; horários inexistentes nunca são oferecidos. A duração da ocupação é calculada como duração real em instantes; recorrência permanece em horário local IANA.
- Retenção, integrações de fornecedores, homologação e scan completo de dependências permanecem pendentes em suas histórias/registros.

## Estratégia

- Implementar domínio puro para geração/filtragem de candidatos e value objects de resultado com `Instant`, `OffsetDateTime`/offset e zona explícita; injetar `Clock` no caso de uso.
- Caso de uso recebe slug/tenant validado por fonte pública somente quando o gate de publicação existir. Até lá não registrar/expor controller público; exercitar o cálculo no domínio/caso de uso com tenant conhecido em fixtures protegidas.
- Reutilizar leituras existentes de `ScheduleRepository` e `ServiceRepository` sem atravessar ownership de módulos. Se necessários, introduzir portas de consulta no módulo availability e adapters de leitura tenant-scoped; nenhuma consulta deve escrever.
- Porta `AppointmentOccupancyQuery` recebe `tenantId`, serviço/recurso e faixa de instantes e devolve intervalos ocupados; produção sem reservas usa adaptação explícita que informa indisponibilidade da integração, sem inventar tabela/endpoint. Testes usam adapter controlado e rotulam essa prova como infraestrutura.
- Aplicar intervalos semiabertos. Um candidato só é emitido se a ocupação completa (serviço + buffer) couber em um período único; não unir períodos adjacentes para acomodar ocupação que atravesse sua fronteira. Bloqueios/reservas que apenas tocam extremos não conflitam.
- Evitar cache; não declarar invalidação real do evento `ScheduleChanged`.
- Testar matemática temporal unitariamente/propriedades, persistência com PostgreSQL real para as consultas/adapters, isolamento e não-escrita. Não criar migrations se o schema basta.
- Sem rota pública liberada nesta história: OpenAPI documenta o contrato somente como futuro/bloqueado até gate explícito de AGD-014; nenhuma API de teste artificial.

## Exemplos temporais a validar após decisão

- Período 09:15–12:00, slot 30, serviço 30, buffer 0: resultado varia conforme a âncora aprovada; exemplo é o teste que fixa a decisão.
- Período 09:00–10:00, duração 45 e buffer 15: ocupação termina em 10:00 e cabe por `[início,fim)`; ocupação terminando depois das 10:00 não cabe.
- Períodos 09:00–10:00 e 10:00–11:00 são adjacentes; candidato cuja ocupação ultrapassa 10:00 não pode ser aceito cruzando a junção.
- Período em lacuna DST não oferece hora local inexistente; repetição DST não recebe offset implicitamente.
- Inclusão de `from`/`to` e igualdade nos limites de antecedência/horizonte aguardam decisão registrada acima.

## Progresso

- [x] 16/10/2026 — revisado estado real do PR #7, checks, integrações e módulos relacionados.
- [x] 16/10/2026 — PR #7 integrado por merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`; branch AGD-013 criada a partir de `origin/main`.
- [x] 16/10/2026 — App CI e CodeQL pós-merge passaram em `main` (`38910598453` e `38910598421`).
- [x] 16/10/2026 — núcleo puro de geração implementado com âncora explícita, encaixe de duração+buffer, intervalos semiabertos e omissão conservadora de starts/limites locais não resolvidos por DST.
- [x] 16/10/2026 — testes puros de grade/exceção/cálculo: 17 passaram em JDK 21; incluem as ancoragens alternativas sem selecionar comportamento de produto, encaixe, adjacência, overlap, DST, limites parametrizados e propriedades determinísticas.
- [x] 16/10/2026 — leitura de serviço exposta por porta de consulta do módulo catálogo; `ServiceCatalogIT` passou com PostgreSQL real (10 testes), incluindo status, duração/buffer e isolamento por tenant.
- [x] 16/10/2026 — caso de uso interno por data combina PostgreSQL real de serviço, grade, política, exceção, bloqueios ativos e porta tenant-scoped de ocupação; `AvailabilityScheduleIT` passou (24 testes) com adapter de ocupação apenas para teste. A rota pública não foi criada.
- [x] 16/10/2026 — `spotless:check verify` passou em JDK 21: 55 testes unitários/arquiteturais/web + 71 integrações com PostgreSQL 16/Testcontainers; Flyway validou e reaplicou sem mudanças; `BUILD SUCCESS`.
- [x] 16/10/2026 — revisão sequencial do diff e invariantes: consultas de serviço tenant-scoped, cálculo read-only, ocupação inclui buffer, gate público permanece fechado, sem cache e sem migration. Revisores independentes não puderam ser iniciados pelo limite de threads do ambiente.
- [ ] concluir o contrato por janela e fixar escolhas temporais somente após resposta do usuário; não criar rota pública enquanto o gate de AGD-014 continuar fechado.
- [ ] associar evidências ao SHA, publicar branch e abrir PR; não fazer merge/deploy.

## Validação

| Critério | Procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Merge AGD-012 | PR #7 + CI push/PR | merge commit contém head validado; App CI e CodeQL passam | Passou; merge `6818781`; App CI `38910031408`/`38910037394`; CodeQL `38910031418`/`38910037301` |
| Algoritmo temporal | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=AvailabilitySlotGeneratorTest,AvailabilityDayScheduleTest,AvailabilitySlotCalculatorTest test` (`backend/`) | invariantes semiabertas, duração+buffer, configurações, DST e determinismo | Passou: 17 testes locais, JDK 21; âncora e inclusão permanecem parâmetros sem default até decisão |
| Dados PostgreSQL | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=AvailabilityScheduleIT,ServiceCatalogIT test` (`backend/`) | leituras tenant-scoped, exceções/bloqueios persistidos, serviço real e nenhuma escrita pelo cálculo | Passou: 24 + 10 testes locais com PostgreSQL 16/Testcontainers |
| Ocupações | adapter controlado em `AvailabilityScheduleIT` | intervalos removem slots corretos; sem alegar integração de reserva | Passou como prova de infraestrutura; integração real depende AGD-015 |
| Segurança pública | `SecurityBoundaryTest` e `SecurityConfiguration` | nenhuma rota pública liberada antes de AGD-014 | Passou no gate completo: `SecurityBoundaryTest` incluiu 401 anônimo/403 autenticado para rota ainda não implementada; `denyAll` preservado |
| Gate backend completo | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` (`backend/`) | formato, compile, testes e integração PostgreSQL | Passou em 16/10/2026: 55 testes + 71 integrações, V1–V11; `BUILD SUCCESS` em 54,8 s |
| Homologação | staging/publicação autorizada | fluxo real disponível | Continua pendente, fora desta entrega local |

## Encerramento ou retomada

- Próxima ação imediata: receber e registrar as decisões pendentes de âncora da grade, inclusividade de datas/fronteiras e limite máximo da janela; implementar o contrato de consulta somente sem abrir o gate público.
- Próxima história prevista após AGD-013: AGD-014 — Página pública. Confirmação de reserva e exclusão concorrente final seguem em AGD-015.
