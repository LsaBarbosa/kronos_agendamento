# AGD-013 — Motor de disponibilidade

## Identificação e objetivo

- História: AGD-013 (P0). Branch `agd-013-motor-disponibilidade`, baseada na `main` após merge do PR #7, merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`. PR pronto para revisão [#8](https://github.com/LsaBarbosa/kronos_agendamento/pull/8), incremento de contrato/caso de uso `005761b6dc45be663461bfa9d54022e925f85972`, registros `94dbf11c035e86f27f68629e0b2075ed317ff297`.
- Objetivo: calcular disponibilidade indicativa por tenant, serviço e período sob demanda; não reservar, bloquear nem garantir confirmação.
- Referências: `docs/spec/app-agendamento-01-especificacao-mvp.md` §5; `docs/spec/app-agendamento-02-modelo-dominio-estados.md` §§2, 12 e 13; `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` seção “Disponibilidade”; `docs/spec/app-agendamento-04-arquitetura-implementacao.md` seção do módulo `availability`; `docs/spec/app-agendamento-06-backlog-criterios-aceite.md` AGD-013; ADR-0010 e ADR-0011.
- Inclui cálculo por janela local inclusiva de no máximo 31 datas, leitura tenant-scoped da configuração/serviço/bloqueios, porta mínima para ocupações e resultado ordenado com `configured`, `explicitlyClosed`, `timeZone`, `generatedAt` e timestamps com offset.
- Exclui confirmação/hold de reserva, cobrança, notificação, página pública, publicação pública, cache, broker e persistência artificial de reservas.

## Estado e dependências

- PR #7 / AGD-012 integrado em merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`; App CI push `38910031408` e PR `38910037394` passaram, incluindo browser E2E. CodeQL push `38910031418` e PR `38910037301` passaram.
- `availability` já persiste grade semanal, política, exceções e bloqueios. `catalog` persiste serviço com status/duração/buffer. `appointments` contém apenas `TimeInterval`; não há reservas persistidas nem repositório de ocupação.
- O gate público continua fechado por deny-all; `Tenant.ACTIVE`, serviço ativo ou agenda configurada não equivalem a publicação. OpenAPI descreve a rota futura, marcada como contrato-only, sem controller/endpoint. AGD-014 define o gate e a experiência pública.
- Integração real de reservas, corrida com confirmação e proteção definitiva dependem de AGD-015. A porta de ocupações nesta história é somente leitura e tenant-scoped; adapter de teste comprova a composição, não a integração real.
- Cache não existe. Decisão deste incremento: cálculo sob demanda lendo PostgreSQL, sem adicionar cache ou Redis; `ScheduleChanged` não será tratado como invalidação efetiva.
- Nenhuma migration está prevista: a história deve ser derivada do schema já publicado (V1–V11). Criar migration somente se implementação demonstrar necessidade real de alteração de schema.

## Critérios verificáveis

1. Candidatos exigem serviço ativo, configuração completa, períodos aplicáveis, política, duração e buffer dentro do mesmo período.
2. Exceção `CLOSED` fecha a data; `SPECIAL` substitui a grade semanal; sem exceção, aplica-se a semana. Configuração ausente não equivale a semana fechada/configurada.
3. Bloqueios ativos e ocupações tenant-scoped removem candidatos que intersectem suas ocupações `[início,fim)`; adjacência é aceita e buffer zero é válido.
4. `from`/`to` são locais inclusivos, `from <= to` e a janela contém até 31 datas; janela inválida retorna `INVALID_AVAILABILITY_WINDOW`.
5. Um único instante capturado por `Clock` controla consulta; antecedência inclui igualdade. Horizonte N significa N datas locais, incluindo hoje, com último dia inclusivo e nenhuma elegibilidade a partir do dia local seguinte.
6. Inícios são ancorados no começo de cada período; cada período `SPECIAL` ancora separadamente. Bloqueios, ocupações e antecedência filtram sem deslocar a grade; ocupação completa cabe em um único período.
7. Não são retornados horários locais inexistentes nem horários ambíguos sem regra aprovada; timestamps incluem offset e `timeZone`.
8. A consulta é somente leitura, executada com snapshot PostgreSQL `REPEATABLE READ` para suas leituras locais; não cria hold/reserva nem libera rota pública ou revela dados internos.
9. Testes de propriedade demonstram ocupação dentro do período, ausência de overlap/duplicação e determinismo com mesmo input/Clock.

## Decisões registradas

As decisões temporais foram aprovadas pelo usuário em 16/10/2026 e incorporadas aos requisitos 01/03 e ADR-0012: âncora por início de período; datas locais inclusivas até 31 datas; antecedência inclusiva em instante; horizonte por calendário local com N datas incluindo hoje; retorno de dias além do horizonte sem slots. Nenhuma dessas escolhas permanece como dúvida em `OPEN-QUESTIONS.md`.

Pendências não pertencentes a esse contrato: publicação/gate e experiência pública (AGD-014), ocupações persistidas e proteção final contra corrida (AGD-015), homologação HTTPS, preflight hospedado, scan completo de dependências e retenção conforme histórias/decisões próprias.

## Estratégia

- Implementar domínio puro para geração/filtragem de candidatos e value objects de resultado com `Instant`, `OffsetDateTime`/offset e zona explícita; injetar `Clock` no caso de uso.
- Caso de uso recebe slug/tenant validado por fonte pública somente quando o gate de publicação existir. Até lá não registrar/expor controller público; exercitar o cálculo no domínio/caso de uso com tenant conhecido em fixtures protegidas.
- Reutilizar leituras existentes de `ScheduleRepository` e `ServiceRepository` sem atravessar ownership de módulos. Se necessários, introduzir portas de consulta no módulo availability e adapters de leitura tenant-scoped; nenhuma consulta deve escrever.
- Porta `AppointmentOccupancyQuery` recebe `tenantId`, serviço/recurso e faixa de instantes e devolve intervalos ocupados; produção sem reservas usa adaptação explícita que informa indisponibilidade da integração, sem inventar tabela/endpoint. Testes usam adapter controlado e rotulam essa prova como infraestrutura.
- Aplicar intervalos semiabertos. Para cada período, começar no seu início local e somar `slotStepMinutes`; exceções `SPECIAL` têm âncoras próprias. Filtros nunca deslocam a sequência. Um candidato só é emitido se a ocupação completa (serviço + buffer) couber em um período único; não unir períodos adjacentes.
- Para consulta, capturar o `Clock` uma vez e calcular os limites a partir desse `Instant`. Uma transação PostgreSQL repeatable-read agrupa leituras de serviço, agenda, política, exceções e bloqueios. Consistência durante mudanças concorrentes é o snapshot da transação; chamadas externas não são cobertas até adapter real.
- Evitar cache; não declarar invalidação real do evento `ScheduleChanged`.
- Testar matemática temporal unitariamente/propriedades, persistência com PostgreSQL real para as consultas/adapters, isolamento e não-escrita. Não criar migrations se o schema basta.
- Sem rota pública liberada nesta história: OpenAPI documenta o contrato somente como futuro/bloqueado até gate explícito de AGD-014; nenhuma API de teste artificial.

## Exemplos temporais

- Período 09:15–12:00, slot 30, serviço 30, buffer 0: âncora 09:15; candidatos elegíveis 09:15, 09:45, 10:15, 10:45 e 11:15. 11:45 é excluído porque terminaria 12:15.
- Período 09:00–10:00, duração 45 e buffer 15: ocupação termina em 10:00 e cabe por `[início,fim)`; ocupação terminando depois das 10:00 não cabe.
- Períodos 09:00–10:00 e 10:00–11:00 são adjacentes; candidato cuja ocupação ultrapassa 10:00 não pode ser aceito cruzando a junção.
- Período em lacuna DST não oferece hora local inexistente; repetição DST não recebe offset implicitamente.
- Janela 16/10 a 15/11 contém 31 datas e é aceita; 16/10 a 16/11 contém 32 e é rejeitada. `from=to` consulta exatamente um dia.
- Hoje local 16/10 e horizonte 7: elegíveis 16/10–22/10; início exatamente no limite de antecedência é incluído; início no primeiro instante local de 23/10 é excluído. Consulta até 25/10 mantém 23–25/10 na resposta sem slots.

## Progresso

- [x] 16/10/2026 — revisado estado real do PR #7, checks, integrações e módulos relacionados.
- [x] 16/10/2026 — PR #7 integrado por merge commit `6818781cc7fa4c2e09c7e4de54c473c353ddf6d9`; branch AGD-013 criada a partir de `origin/main`.
- [x] 16/10/2026 — App CI e CodeQL pós-merge passaram em `main` (`38910598453` e `38910598421`).
- [x] 16/10/2026 — geração temporal usa âncora por início de cada período; período SPECIAL também ancora individualmente; bloqueios/ocupações/notice apenas filtram. Duração + buffer deve caber no mesmo período e DST não escolhe offsets implicitamente.
- [x] 16/10/2026 — testes temporais/propriedade cobrem 09:15 com step 30, períodos independentes, remoção sem deslocar âncora, encaixe no limite, overlaps, DST, calendário e determinismo.
- [x] 16/10/2026 — leitura de serviço exposta por porta de consulta do módulo catálogo; `ServiceCatalogIT` passou com PostgreSQL real (10 testes), incluindo status, duração/buffer e isolamento por tenant.
- [x] 16/10/2026 — consulta por intervalo implementada com serviço, grade, política, exceções, bloqueios persistidos e porta tenant-scoped de ocupação; PostgreSQL real em `AvailabilityScheduleIT` (26 testes). Adapter de ocupação é teste de infraestrutura, não integração real de reservas.
- [x] 16/10/2026 — critérios temporais aprovados pelo usuário em 16/10/2026; requisitos 01 §5.6, 02 §12, contrato 03, OpenAPI futuro e ADR-0012 atualizados; as perguntas foram removidas de OPEN-QUESTIONS.
- [x] 16/10/2026 — `spotless:check verify` passou em JDK 21: 58 testes unitários/arquiteturais/web + 73 integrações com PostgreSQL 16/Testcontainers; Flyway validou V1–V11, sem migration; `BUILD SUCCESS` em 50,4 s.
- [x] 16/10/2026 — revisão sequencial confirmou leituras tenant-scoped/read-only, snapshot PostgreSQL `REPEATABLE READ`, consulta única por fonte, gate deny-all, ausência de cache/migration e dependências AGD-014/015; subagentes independentes indisponíveis por limite de threads.
- [x] 16/10/2026 — implementação anterior e documentação foram publicadas em `e865d13`; os quatro checks App CI/CodeQL e browser E2E passaram nesse head (runs `38915571851`, `38915580313`, `38915571739`, `38915580417`). Isso não valida o delta atual.
- [x] 16/10/2026 — contrato temporal aprovado e consulta por janela validados localmente no SHA `005761b6dc45be663461bfa9d54022e925f85972`; V1–V11 preservadas. Commits publicados no mesmo PR #8.
- [x] 16/10/2026 — App CI push/PR e CodeQL push/PR passaram no head `94dbf11c035e86f27f68629e0b2075ed317ff297` (runs `38921233486`, `38921243280`, `38921233589`, `38921243619`). PR marcado pronto para revisão, sem merge/deploy.

## Validação

| Critério | Procedimento | Resultado esperado | Resultado observado |
|---|---|---|---|
| Merge AGD-012 | PR #7 + CI push/PR | merge commit contém head validado; App CI e CodeQL passam | Passou; merge `6818781`; App CI `38910031408`/`38910037394`; CodeQL `38910031418`/`38910037301` |
| Domínio temporal | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=AvailabilitySlotGeneratorTest,AvailabilitySlotCalculatorTest test` (`backend/`) | âncora por período, filtros sem deslocamento, encaixe completo, DST e propriedades | Passou; parte do gate completo no mesmo código: 15 testes dessas duas classes, mais testes de domínio de agenda. |
| Consulta por janela/PostgreSQL | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp -Dtest=AvailabilityScheduleIT test` (`backend/`) | inclusive endpoints, de 1 a 31 datas, 32/invertido inválidos, horizonte de calendário e fonte de ocupações | Passou: 26 testes com PostgreSQL 16/Testcontainers, inclusive `SHOW transaction_isolation = repeatable read`. Adapter de ocupação é prova da composição; não há reservas persistidas. |
| Ocupações | adapter controlado em `AvailabilityScheduleIT` | intervalos removem slots corretos; sem alegar integração de reserva | Passou como prova de infraestrutura; integração real depende AGD-015 |
| Segurança pública | `SecurityBoundaryTest` e `SecurityConfiguration` | nenhuma rota pública liberada antes de AGD-014 | Passou no gate completo: `SecurityBoundaryTest` incluiu 401 anônimo/403 autenticado para rota ainda não implementada; `denyAll` preservado |
| Gate backend completo | `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` (`backend/`) | formato, compile, testes e integração PostgreSQL | Passou em 16/10/2026 no conteúdo commitado como `005761b6dc45be663461bfa9d54022e925f85972`: 58 testes + 73 integrações, PostgreSQL 16, migrations V1–V11; `BUILD SUCCESS` em 50,4 s. |
| OpenAPI | `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH ./node_modules/.bin/prettier --check ../docs/openapi/admin-availability.yaml` (`frontend/`) | YAML válido e formatado; contrato distingue futuro/bloqueado de rota implementada | Passou após correção de whitespace; nenhuma rota pública/controller foi habilitado. |
| CI push/PR no incremento | App CI e CodeQL no novo SHA | jobs obrigatórios no head final | Passou no head `94dbf11c035e86f27f68629e0b2075ed317ff297`: App CI push/PR `38921233486`/`38921243280`; CodeQL push/PR `38921233589`/`38921243619`. |
| Homologação | staging/publicação autorizada | fluxo real disponível | Continua pendente, fora desta entrega local |

## Encerramento ou retomada

- Próxima ação imediata: publicar contrato e implementação no mesmo PR #8 e revisar checks remotos. Revisores independentes não estavam disponíveis neste ambiente; a revisão local foi sequencial.
- Próxima história prevista após AGD-013: AGD-014 — Página pública. Confirmação de reserva e exclusão concorrente final seguem em AGD-015.
