# 01 — Especificação funcional do MVP

## 1. Visão do produto

O App Agendamento é um SaaS para pequenos prestadores de serviços organizarem disponibilidade, receberem reservas por um link público e reduzirem faltas. O primeiro recorte atende negócios de beleza e bem-estar, mas o domínio deve permanecer genérico o suficiente para outros serviços com duração conhecida.

### Resultado esperado

- O prestador publica seus serviços e horários sem depender de atendimento manual.
- O cliente encontra um horário válido e confirma a reserva em poucos passos.
- O sistema impede conflito de agenda mesmo sob requisições concorrentes.
- O prestador acompanha agenda, faltas, bloqueios e assinatura em uma área administrativa.
- Comunicações e cobranças externas falham de forma recuperável, auditável e sem duplicar efeitos.

### Métricas de produto propostas

As metas abaixo são hipóteses iniciais; produto deve revisá-las após instrumentar o funil.

| Métrica | Definição | Meta inicial proposta |
|---|---|---|
| Conversão de reserva | reservas confirmadas / sessões que visualizaram disponibilidade | medir baseline nas primeiras quatro semanas |
| Conflito percebido | reservas rejeitadas após o cliente escolher um slot já tomado | < 2% das tentativas |
| Entrega de confirmação | confirmações aceitas pelo provedor / confirmações solicitadas | >= 98% em 15 min |
| No-show | faltas / reservas encerradas | medir por tenant e serviço |
| Ativação do trial | tenants que publicam serviço e horário / trials iniciados | >= 60% |
| Conversão paga | tenants ativos após o trial / trials encerrados | definir após validação comercial |

## 2. Escopo e atores

### 2.1 Atores

| Ator | Capacidades no MVP |
|---|---|
| Visitante/cliente | consultar serviços e disponibilidade; iniciar reserva; receber e validar código; consultar ou cancelar suas reservas |
| Cliente bloqueado | consultar e cancelar reservas existentes; não criar reserva pelo fluxo público |
| Administrador do tenant | configurar negócio, serviços, agenda, exceções, bloqueios, políticas; criar, remarcar, cancelar e encerrar reservas; desbloquear cliente; solicitar exportação; administrar assinatura |
| Operação da plataforma | suporte com acesso controlado; analisar falhas; executar procedimentos excepcionais com auditoria |
| Provedor de WhatsApp | entregar códigos, confirmações, alterações e lembretes; informar status por webhook |
| Provedor de pagamento | criar/renovar cobrança; informar pagamento, falha, estorno ou cancelamento por webhook |

### 2.2 Modelo de tenancy

- Cada empresa é um `tenant` isolado.
- O MVP possui uma única agenda operacional por tenant.
- Existe um usuário administrador principal por tenant; suporte a vários usuários é pós-MVP.
- Todo registro de negócio carrega `tenant_id`, inclusive auditoria e idempotência.
- Consultas e mutações nunca aceitam `tenant_id` fornecido pelo frontend como autoridade; ele vem da autenticação ou do slug público resolvido no servidor.

## 3. Onboarding administrativo

O onboarding deve permitir que o tenant publique o primeiro horário sem intervenção do suporte.

### Dados obrigatórios

- nome do negócio;
- slug público único;
- fuso horário;
- telefone de contato;
- pelo menos um serviço ativo;
- grade semanal com ao menos um período disponível;
- política de antecedência e cancelamento;
- consentimentos e aceite dos termos aplicáveis.

### Critérios

- Slug é normalizado, validado e reservado atomicamente.
- Fuso horário é obrigatório e armazenado como identificador IANA, por exemplo `America/Sao_Paulo`.
- Valores monetários usam moeda definida pelo produto e inteiros na menor unidade; nunca `float`.
- O link público permanece despublicado até a configuração mínima ser válida.
- Publicar é uma ação explícita do administrador autenticado; o estado inicial é despublicado e `Tenant.ACTIVE` não publica automaticamente. O servidor verifica a prontidão no comando de publicação e em toda leitura pública. Despublicar preserva configuração e histórico.
- Prontidão deve apresentar bloqueios para dados ou evidências obrigatórias ausentes. Não criar defaults, texto/versão de termos, consentimento, trial ou assinatura ativa sem aprovação e integração correspondentes.

## 4. Catálogo de serviços

Cada serviço possui:

| Campo | Regra |
|---|---|
| Nome | obrigatório; único entre serviços ativos do mesmo tenant por comparação normalizada |
| Descrição | opcional |
| Duração | múltiplo de 15 minutos; valor positivo |
| Intervalo posterior | opcional; múltiplo de 15 minutos; reserva a agenda, mas não é exibido como duração do atendimento |
| Preço informativo | opcional no MVP; não representa cobrança do atendimento |
| Status | ativo ou inativo |
| Cor/ordenação | opcional, apenas apresentação |

Regras:

- Serviço inativo não aparece em novas reservas.
- Inativar um serviço não cancela reservas existentes.
- Alterar duração ou intervalo não modifica retroativamente reservas já confirmadas.
- A reserva armazena snapshot mínimo do serviço — nome, duração, intervalo e preço informativo — para preservar a história.

## 5. Disponibilidade

### 5.1 Entradas do cálculo

A disponibilidade de um serviço é a interseção de:

1. grade semanal do tenant;
2. exceções de calendário para a data;
3. ausência de bloqueio administrativo;
4. ausência de reserva que ocupe o intervalo;
5. duração do serviço mais intervalo posterior;
6. antecedência mínima;
7. janela máxima de reserva;
8. limites de slot configurados;
9. fuso horário do tenant.

### 5.2 Grade semanal

- Cada dia pode ter zero ou mais períodos, por exemplo 09:00–12:00 e 13:00–18:00.
- Períodos do mesmo dia não podem se sobrepor.
- Os limites são horários locais `HH:mm` alinhados a múltiplos de 15 minutos, independentemente do slot configurado.
- Os períodos são semiabertos `[início,fim)`: períodos adjacentes são permitidos; sobreposição é rejeitada. O início deve ser anterior ao fim; períodos que cruzam meia-noite são rejeitados no MVP.
- A grade é interpretada no fuso do tenant.
- Mudança de grade afeta apenas o cálculo futuro; reservas existentes permanecem válidas e visíveis, ainda que fiquem fora da nova grade.

### 5.3 Exceções e bloqueios

- Ausência de exceção mantém a grade semanal. Exceção `CLOSED` fecha a data; `SPECIAL` substitui integralmente a grade pela lista final de períodos da data. A lista final pode manter períodos semanais e incluir períodos adicionais, escolhidos explicitamente pelo administrador. Remover a exceção restaura a aplicação da grade semanal.
- Horários especiais usam a data e o timezone IANA do tenant. Cada limite local é múltiplo de 15 minutos, independentemente do slot; os intervalos são `[início,fim)`, adjacência é permitida, sobreposição é rejeitada, início é anterior ao fim e períodos não cruzam meia-noite.
- Limites locais inexistentes por DST são rejeitados. Limites locais ambíguos sem offset explícito são rejeitados; a aplicação não ajusta horário nem escolhe offset implicitamente. Horários especiais recebem `localStartOffset`/`localEndOffset` opcionais quando uma ocorrência ambígua precisa ser selecionada; o offset deve ser válido para aquela data/hora na zona IANA confiável do tenant e fica persistido com o período. A interface oferece as ocorrências válidas; offsets não são inferidos do relógio do navegador.
- Bloqueio administrativo ocupa `[início,fim)`, pode atravessar meia-noite e datas diferentes, e seus limites locais são alinhados a 15 minutos. Motivo é obrigatório na criação e no cancelamento. A criação usa a zona IANA do tenant, rejeita hora local inexistente ou ambígua sem offset e valida offsets explícitos contra as regras da zona. A persistência guarda instantes UTC e a zona usada; um intervalo pode cruzar mudança de offset e sua duração real é a diferença entre os instantes, não uma duração presumida de dias civis.
- Bloqueio que intersecta reserva confirmada é recusado. Não cancelar, mover ou ocultar reserva automaticamente. A confirmação de reserva e a criação de bloqueio precisarão de proteção transacional/constraint compartilhada quando reservas forem implementadas; um precheck isolado não resolve a corrida.
- Cancelamento é lógico, preserva identidade, versão e histórico, e torna o intervalo elegível após recomputação. Não há regra geral de proibição de sobreposição entre bloqueios administrativos.
- Exceções e bloqueios administrativos usam as rotas tenant-scoped do contrato administrativo; consultas exigem intervalo válido limitado a, no máximo, 366 datas locais inclusivas. Mudanças confirmadas emitem `ScheduleChanged` após gravação transacional. O mecanismo de cache/invalidação de disponibilidade depende de AGD-013.

### 5.4 Configurações

| Política | Valores permitidos no MVP |
|---|---|
| Granularidade de início dos slots | 15, 30 ou 60 minutos |
| Antecedência mínima | de 1 a 24 horas, configurável |
| Janela máxima de reserva | 7, 30, 60 ou 90 dias |
| Limite para cancelamento pelo cliente | de 2 a 24 horas antes do início |

**Decisão de produto aprovada pelo usuário em 17/10/2026 e registrada na ADR-0018:** o cancelamento é permitido quando `now <= startsAt - cancellationCutoffHours`, inclusive no instante exato do cutoff. AGD-023 mantém apenas a política de domínio; a rota mutável continua fechada até identidade verificada em AGD-024/025.
| Lembrete único | 2, 6, 12 ou 24 horas antes do início |

### 5.5 Horário de verão e relógio

- Persistir instantes em UTC e a zona IANA usada no cálculo.
- Em horário local inexistente devido à mudança de relógio, não oferecer o slot.
- Em horário local ambíguo, representar cada instante possível separadamente ou omitir o horário até existir regra explícita; nunca confirmar usando conversão implícita.
- A API devolve timestamps ISO 8601 com offset e o `timeZone` do tenant.
- Para exceções e bloqueios administrativos, horários locais inexistentes ou ambíguos sem offset não são ajustados nem desambiguados automaticamente; entradas com offset precisam corresponder a uma ocorrência válida na zona IANA do tenant. Para bloqueios, os instantes UTC e a zona usada são persistidos.

### 5.6 Consulta e geração de disponibilidade (AGD-013)

- A grade dos inícios é ancorada no início de cada período aplicável. Os candidatos avançam pelo `slotStepMinutes`; bloqueios, ocupações e antecedência removem candidatos, mas não reiniciam nem deslocam a grade. Cada período de uma exceção `SPECIAL` tem sua própria âncora.
- A ocupação completa do serviço e buffer deve caber em um único período. Períodos adjacentes não são unidos para formar um intervalo maior.
- `from` e `to` são datas locais inclusivas no timezone IANA do tenant; `from <= to` e `DAYS.between(from, to) + 1 <= 31`. Janela invertida ou com mais de 31 datas retorna `400 INVALID_AVAILABILITY_WINDOW`.
- O instante atual é capturado uma única vez por consulta. O limite mínimo é esse instante mais `minimumNoticeHours`; início exatamente nesse limite é permitido.
- O horizonte `N` inclui `N` datas locais contando hoje. A última data permitida é hoje no timezone do tenant mais `N - 1` dias, inclusiva até seu último instante local. Candidatos a partir do início da data seguinte são excluídos; essa regra não é `N * 24` horas e respeita mudanças de DST sem escolher offset implicitamente.
- Exemplo: em 16/10, horizonte 7 inclui as datas locais 16/10 a 22/10, também respeitada a antecedência. A consulta pode incluir datas além do horizonte; elas permanecem na resposta ordenada, com slots vazios.
- `configured=false` identifica ausência de grade ou política; `explicitlyClosed=true` identifica fechamento explícito. Ausência de oferta nunca autoriza defaults.
- O cálculo é somente leitura e indicativo. Não cria hold/reserva nem garante confirmação. A rota pública permanece bloqueada até o gate aprovado e implementado por AGD-014; ocupações reais e exclusão concorrente dependem de AGD-015.

## 6. Fluxo público de reserva

### 6.1 Passos

1. Cliente abre `/{slug}`.
2. Escolhe serviço ativo.
3. Consulta dias e horários disponíveis.
4. Escolhe o horário.
5. Informa nome e telefone.
6. O servidor valida políticas, bloqueio do cliente e disponibilidade atual dentro da transação.
7. O servidor confirma a reserva atomicamente.
8. A confirmação é exibida imediatamente e a mensagem é solicitada de forma assíncrona.

### 6.2 Dados do cliente

- Nome e telefone são obrigatórios.
- Telefone é normalizado para E.164 quando possível.
- O telefone normalizado identifica o cliente dentro do tenant; ele não cria identidade global entre tenants.
- Alterações de nome não apagam o valor histórico registrado na reserva.
- O frontend deve informar de forma clara como os dados serão usados.

### 6.3 Confirmação e disputa de slot

- A confirmação é automática; não existe aprovação manual no fluxo normal.
- A disponibilidade exibida não é uma reserva temporária.
- Se outra transação ocupar o intervalo antes do commit, a tentativa retorna `SLOT_NO_LONGER_AVAILABLE` e oferece atualização dos horários.
- Repetir uma requisição após timeout com a mesma chave de idempotência devolve o mesmo resultado conhecido.
- A confirmação não depende de o WhatsApp estar disponível.

### 6.5 Contrato mínimo de criação pública

- `POST /api/v1/public/{slug}/appointments` recebe somente `serviceId`, `startsAt` ISO 8601 com offset e `client{name,phone}`. O servidor resolve tenant pelo slug e obtém timezone, serviço, duração, buffer, grade, política, publicação, bloqueios e ocupações das fontes confiáveis.
- O telefone precisa identificar explicitamente o código de país em formato E.164; separadores de apresentação permitidos podem ser removidos antes da validação. Não inferir país da UI, navegador, idioma ou timezone. Telefone informado não é identidade verificada nem autoriza leitura de reservas.
- `startsAt` inclui offset; o servidor confirma que esse offset representa um slot oferecido na zona IANA do tenant. Horário final, duração, buffer, preço e tenant não são aceitos como autoridade do navegador.
- A resposta de sucesso só é emitida depois do commit e contém referência da reserva, serviço, início/fim e timezone. Não inclui telefone nem concede acesso ao histórico.
- A chamada exige `Idempotency-Key` e CSRF. Uma resposta perdida pode ser repetida com a mesma chave e conteúdo para recuperar os identificadores originais; chave reutilizada com outro conteúdo retorna conflito. Conflito de agenda retorna `SLOT_NO_LONGER_AVAILABLE` e não descarta os dados digitados.
- Antes do POST confirmador, a UI persiste uma identidade opaca da intenção e o servidor registra o vínculo da capability ao tenant, à chave idempotente e ao digest HMAC do comando. Após reload, o receipt é recuperado por capability de escopo único, sem consultar reservas pelo telefone ou criar identidade autenticada de cliente.
- O browser pode persistir somente a chave idempotente, a capability aleatória e referências não pessoais mínimas da intenção. Nome, telefone e corpo completo nunca são persistidos em storage, URL, histórico ou telemetria. Depois do reload, a pessoa reintroduz os dados para retry; o servidor compara o digest e deixa visível qualquer incompatibilidade.
- Estado sem receipt visível significa resultado incerto, nunca falha definitiva. A consulta de recovery é somente leitura e não confirma a reserva. Retry usa a mesma chave/comando; timeout, ausência de receipt, capability/rede indisponível ou operação concorrente não gera automaticamente uma nova intenção. A capability autoriza apenas consultar o estado e resumo mínimo daquela intenção, não PII, outras reservas, cancelamento, remarcação ou sessão de cliente verificado.
- Digests novos de reserva usam HMAC-SHA-256, canonicalização versionada e `keyId` persistido. Segredo vem de configuração segura estável; não existe fallback sem chave. Chaves antigas continuam disponíveis enquanto necessárias para replay. Digests legados SHA-256 são lidos sem reescrever registros; falta de chave histórica necessária é indisponibilidade segura, não conflito de conteúdo.
- O vínculo opaco de recuperação tem validade técnica de 7 dias após seu preparo, como limite para retornar e reconciliar a mesma intenção após falha/reload. Expirar torna a consulta indisponível e não autoriza nova intenção automaticamente, sem apagar nem desfazer reserva, receipt, idempotência, auditoria ou histórico; expurgo físico segue a política geral de retenção ainda pendente.

### 6.4 Cliente bloqueado por faltas

- Antes do commit, o sistema avalia a restrição ativa do cliente naquele tenant.
- Cliente bloqueado recebe mensagem pública neutra, sem expor contagem ou dados internos.
- O bloqueio impede somente novas reservas pelo próprio cliente.
- O administrador pode reservar para o cliente e pode desbloqueá-lo, registrando motivo.

## 7. Acesso do cliente às reservas

### 7.1 Código pelo WhatsApp

- Código de uso único, válido por cinco minutos.
- O código é armazenado somente como hash com salt/pepper apropriado.
- A resposta de solicitação é neutra para reduzir enumeração de telefones.
- Após validação, emitir sessão curta e limitada ao telefone e tenant verificados.

### 7.2 Limites de abuso

| Controle | Regra recomendada |
|---|---|
| Envios | no máximo 3 códigos por telefone/tenant em 15 minutos |
| Bloqueio de envio | 30 minutos após exceder o limite |
| Tentativas incorretas | no máximo 5 por desafio |
| Bloqueio por erros | 5 minutos após exceder tentativas |
| Dimensões adicionais | IP, dispositivo, tenant e padrões agregados, sem depender só de um identificador |

Os limites devem ser aplicados atomicamente. Mensagens e tempos podem ser calibrados por operação, mas reduções de segurança exigem decisão versionada.

### 7.3 Operações permitidas

- listar reservas futuras e histórico mínimo;
- ver detalhes da própria reserva;
- cancelar reserva dentro da política;
- visualizar orientação para contato quando o cancelamento online não for mais permitido.

O cliente não altera diretamente serviço ou horário no MVP; remarcação é feita pelo administrador. Uma experiência de remarcação autônoma é pós-MVP.

## 8. Administração da agenda

### Visões

- agenda diária e semanal;
- filtro por status e serviço;
- detalhes de cliente e histórico relevante;
- indicadores de comunicação e auditoria.

### Operações

- criar reserva;
- remarcar;
- cancelar em nome do prestador ou do cliente;
- marcar como concluída;
- marcar falta;
- corrigir status por operação explícita e auditada;
- criar exceção e bloqueio;
- desbloquear cliente;
- consultar tentativas de notificação sem expor segredos do provedor.

**Decisão de produto aprovada em 17/10/2026 (ADR-0017):** na remarcação administrativa, uma nova mutação exige que o serviço associado esteja atualmente ativo no tenant. A elegibilidade é consultada separadamente do snapshot: inativar o serviço preserva a reserva e sua ocupação, e duração, buffer, nome e demais dados históricos permanecem os confirmados originalmente. Overrides não contornam serviço inativo. Replay autorizado de uma remarcação já concluída devolve o resultado original mesmo se o serviço tiver sido inativado depois; essa verificação não impede o replay.

### Encaixe administrativo

O encaixe permite ao administrador criar uma reserva fora da grade publicada ou das políticas de antecedência, mas **não** autoriza sobreposição. A tela precisa mostrar qual regra foi excepcionalmente ignorada e exigir motivo.

Em AGD-021, a allowlist é limitada à grade semanal e antecedência mínima. `OUTSIDE_WEEKLY_SCHEDULE` ignora somente a grade semanal: sem exceção para a data, permite encaixe fora dela mediante confirmação explícita e motivo; `CLOSED` permanece fechado; `SPECIAL` substitui a grade e seus períodos finais não podem ser ultrapassados, devendo a ocupação completa, inclusive buffer, caber em um único período. `MIN_NOTICE` é independente e pode ser ignorada também em uma data `SPECIAL`, mediante motivo, desde que o início permaneça em um período especial. Horizonte, serviço inativo, bloqueio, ocupação, falta de configuração e `OVERLAP` não podem ser ignorados. A autorização administrativa é independente da publicação do link; publicação continua sujeita ao gate próprio. O POST não altera nem amplia exceções ou grade como efeito colateral.

Se o negócio futuramente precisar de sobreposição deliberada, isso deve ser modelado como capacidade paralela ou múltiplos recursos; não como bypass da restrição de integridade.

### Regra de dez minutos

Há uma decisão registrada sobre uma janela excepcional de dez minutos, mas sua semântica de negócio não está suficientemente definida neste pacote. Para não introduzir comportamento incorreto:

- manter a regra desabilitada no MVP inicial;
- não usá-la para alterar ocupação, cancelamento ou falta;
- confirmar com produto se representa tolerância de atraso, janela de correção ou outra exceção;
- implementá-la depois como política nomeada, versionada e auditável.

Essa pendência não bloqueia a construção do restante do sistema.

## 9. Ciclo de vida da reserva

### Estados de negócio

- `CONFIRMED`: criada e válida.
- `COMPLETED`: serviço concluído.
- `NO_SHOW`: cliente não compareceu.
- `CANCELLED_BY_CLIENT`: cancelada pelo cliente.
- `CANCELLED_BY_PROVIDER`: cancelada administrativamente.

Remarcação é um evento histórico sobre a mesma reserva, não um estado terminal. Toda transição registra autor, instante, motivo opcional e versão anterior.

Regras:

- Apenas reserva confirmada pode ser concluída, marcada como falta, cancelada ou remarcada.
- Estado terminal não volta para confirmado por edição simples; correção exige operação administrativa explícita e trilha própria.
- Cancelamento libera a ocupação somente após commit.
- Remarcação valida e troca o intervalo atomicamente; se o novo intervalo falhar, o antigo permanece.
- Em AGD-023, cancelamento/conclusão/falta são comandos administrativos distintos e somente partem de `CONFIRMED`. Decisão aprovada em 17/10/2026 (ADR-0018): conclusão e falta exigem `Clock.instant() >= occupiedUntil`, incluindo buffer; cancelamento do cliente permite igualdade em `startsAt - cancellationCutoffHours`; cancelamento administrativo não recebe o prazo do cliente. Não há tolerância de dez minutos.

## 10. Faltas e bloqueio de autoagendamento

### Política

- Contagem em janela móvel de 12 meses. AGD-032 captura um único `asOf` por avaliação via `Clock`, calcula `windowStart` subtraindo 12 meses de calendário em UTC (preservando horário e ajustando dia inexistente, como 29/02 para 28/02) e usa `[windowStart, asOf)`. A ocorrência é o instante original do registro de `NO_SHOW`; o timezone do tenant é apenas para apresentação.
- Limite configurável entre 1 e 5 faltas.
- Ao atingir o limite, criar restrição ativa para autoagendamento.
- Cancelamentos não contam como falta.
- Corrigir falta é comando administrativo específico `NO_SHOW → COMPLETED`, com motivo obrigatório, autorização atual, CAS e idempotência. Preserva falta/história original e registra correção compensatória referenciada; a falta corrigida deixa de contar. Não retorna a `CONFIRMED`, nem autoriza edição genérica de estado terminal.
- A política aplicada e a contagem usada são registradas para auditoria.

### Administração

- Administrador vê contagem, eventos que a compõem e status do bloqueio.
- Desbloqueio manual exige motivo e não apaga eventos de falta.
- Uma nova falta posterior pode voltar a bloquear conforme a política vigente.
- Alteração do limite não reescreve história nem executa reconciliação implicitamente no `PUT`. A redução pode ser aplicada retroativamente por comando administrativo explícito e versionado, posterior ao salvamento; a avaliação identifica a versão da política e é recuperável/idempotente.

Política ausente é `NOT_CONFIGURED`, sem limite padrão. Contagem e fatos continuam consultáveis, mas a ausência não permite inferir `CLEAR` nem bloqueio. O status da política é independente do status da restrição (`NOT_CONFIGURED`, `CLEAR` ou `RESTRICTED`). Uma restrição ativa é tenant-scoped e verificada no servidor antes do commit do autoagendamento público, mesmo se a política for removida; não impede consulta/cancelamento do cliente nem reserva administrativa. Sem política e sem restrição ativa, o autoagendamento pode prosseguir como `NOT_CONFIGURED`, ainda condicionado à publicação, requisitos legais/comerciais, serviço ativo e disponibilidade. Correção de falta e envelhecimento atualizam a contagem, mas não removem restrição ativa. Desbloqueio exige ação administrativa explícita com motivo e não apaga faltas. Redução do limite pode ser aplicada por reconciliação administrativa explícita posterior ao PUT. Estas decisões foram aprovadas pelo usuário em 20/10/2026 e estão detalhadas na ADR-0024.

## 11. Notificações

### Mensagens do MVP

| Evento | Canal | Destinatário | Obrigatório |
|---|---|---|---|
| Código de acesso | WhatsApp | cliente | sim |
| Reserva confirmada | WhatsApp | cliente | sim |
| Reserva remarcada | WhatsApp | cliente | sim |
| Reserva cancelada | WhatsApp | cliente | sim |
| Lembrete único | WhatsApp | cliente | configurável, padrão ligado |
| Pagamento/assinatura | canal disponível definido pela operação | administrador | sim para eventos críticos |

### Regras

- Mensagem é efeito assíncrono; falha de envio não desfaz reserva confirmada.
- Cada intenção possui chave de deduplicação.
- Remarcação ou cancelamento invalida lembretes pendentes da versão anterior. Conclusão e falta também tornam inelegível qualquer lembrete ainda pendente.
- O worker verifica status, versão, início temporal e claim vigente imediatamente antes de iniciar o transporte. Alteração posterior a esse ponto não pode retirar uma mensagem já em voo.
- O lembrete único é agendado pelo `reminderOffsetHours` explicitamente configurado (2, 6, 12 ou 24), como instante UTC derivado do início da reserva. Política ausente não cria lembrete nem inventa offset; “padrão ligado” exige política configurada.
- Decisão de produto aprovada nesta instrução para AGD-031: job atrasado pode ser enviado enquanto a reserva permanecer `CONFIRMED`, na versão atual, e seu início for estritamente futuro. Na igualdade com `starts_at` ou depois, o lembrete é obsoleto, sem tolerância.
- Reserva criada/confirmada depois de `due_at` torna a intenção imediatamente elegível se o início ainda for futuro e todas as demais validações/dedupe passarem.
- O vencimento é materializado ao criar a intenção usando a política vigente quando o evento é consumido. Mudanças de política não recalculam intenções existentes; eventos ainda não consumidos e novas versões após remarcação usam a política vigente quando processados.
- Tentativas, resposta do provedor e estado final são registradas sem armazenar credenciais ou conteúdo sensível desnecessário.
- Retries usam backoff e limite; após falha final, o sistema sinaliza operação.

## 12. Assinatura e cobrança

### 12.1 Planos

| Plano | Cobrança | Regra |
|---|---|---|
| Mensal | recorrente | renovação automática |
| Anual | uma cobrança anual | preço equivalente a 10 mensalidades; renovação exige autorização |

### 12.2 Trial

- 14 dias corridos.
- Não exige cartão.
- Começa uma vez por tenant/identidade comercial, sujeito a controles antifraude.
- O tenant pode configurar e publicar durante o trial.
- Antes do fim, comunicar prazo e ação necessária.

**Pendência de elegibilidade:** a especificação ainda não define a fonte, o identificador nem a verificação da “identidade comercial” usada para evitar trial repetido após recriação do tenant. Não inferir esse identificador de slug, e-mail, subject OIDC ou dado autodeclarado. O núcleo aceita apenas identidade opaca retornada por porta server-side confiável; enquanto a fonte real não for escolhida e configurada, concessões em produção permanecem desabilitadas. A concessão persistida é única por identidade e a validade termina exatamente quando `now >= trialEndsAt`, com transição `TRIALING → SUSPENDED`; os sete dias de `PAST_DUE` não são tolerância de trial.

### 12.3 Pagamento e inadimplência

- Tentativas de cobrança em D0, D+3 e D+6.
- Tolerância total de sete dias a partir do vencimento.
- Durante a tolerância, o tenant continua operando com aviso visível.
- Sem pagamento confirmado ao fim da tolerância, a assinatura passa a `SUSPENDED`.
- Webhook duplicado não duplica cobrança, crédito ou transição.
- Reativação ocorre após confirmação autoritativa do pagamento.

### 12.4 Efeito da suspensão

- Área administrativa entra em modo limitado para cobrança, exportação permitida por política e suporte.
- Link público deixa de aceitar novas reservas.
- Reservas existentes e dados não são apagados.
- Reativação restaura acesso sem recriar configuração.
- Retenção pós-cancelamento/suspensão: 12 meses como política inicial, sujeita a base legal, solicitação de exclusão e legal hold válido.

### 12.5 Renovação anual

- Aviso sete dias antes.
- Exige autorização válida do titular.
- Ausência de autorização não deve produzir cobrança silenciosa.
- A expiração segue política comercial documentada e oferece migração para mensal quando aplicável.

## 13. Exportações e relatórios

### Conteúdo

- reservas;
- clientes;
- serviços;
- faltas e restrições;
- dados de assinatura e cobrança permitidos ao tenant;
- auditoria administrativa exposta conforme política.

### Formatos e filtros

- CSV para dados tabulares.
- PDF para relatório legível e resumido.
- Filtros por período, status, serviço e categoria disponível.
- Toda exportação usa snapshot imutável dos critérios e da versão de dados no início do job.

### Controles

| Controle | Regra |
|---|---|
| Concorrência | uma exportação ativa por tenant |
| Retentativa automática | 30 s, 1 min e 2 min |
| Disponibilidade do arquivo | 30 minutos |
| Link excepcional de suporte | uso único, 15 minutos e dupla aprovação |
| Auditoria de exportação | retenção de dois anos, respeitando obrigações legais e minimização |

- Arquivo deve ser criptografado em repouso.
- Download exige autorização atual; URL assinada não substitui validação quando houver endpoint intermediário.
- Expiração remove a possibilidade de acesso e agenda eliminação do objeto.
- Nova exportação após expiração gera novo snapshot; não reativa arquivo antigo.

## 14. Auditoria, privacidade e suporte

### Eventos auditáveis mínimos

- login e falhas relevantes;
- configuração de agenda e políticas;
- criação, remarcação, cancelamento e encerramento administrativos;
- marcação/correção de falta e desbloqueio;
- mudanças de assinatura;
- exportação solicitada, disponibilizada e baixada;
- ações de suporte;
- alterações de permissões;
- processamento de exclusão ou retenção.

Cada evento contém `event_id`, tenant, ator, ação, alvo, instante, correlation ID, resultado e diferenças mínimas necessárias. Não registrar códigos, tokens, senhas ou payloads completos sensíveis.

### Privacidade

- Coletar apenas dados necessários ao agendamento e operação.
- Documentar finalidade e base aplicável por categoria.
- Suportar acesso, correção, exclusão e retenção legal.
- Exclusão remove conteúdo quando não houver outra base válida e preserva apenas auditoria minimizada sem o conteúdo apagado.
- Backups e restores devem respeitar tombstones de exclusão antes de liberar dados restaurados.

## 15. Requisitos não funcionais

### Obrigatórios

- Isolamento entre tenants testado em todos os endpoints.
- Proteção contra sobreposição no banco, não só na aplicação.
- Idempotência em criação/remarcação, webhooks e operações de cobrança.
- HTTPS em todo tráfego e segredos fora do código.
- Backup automatizado e teste periódico de restauração.
- Logs estruturados com correlation ID.
- Métricas de reserva, fila, webhook, notification delivery e cobrança.
- Alertas para erros sustentados e filas paradas.
- Migrações de banco versionadas e compatíveis com rollback de aplicação quando possível.

### Metas operacionais propostas

| Indicador | Meta inicial |
|---|---|
| Disponibilidade mensal | 99,5% excluindo manutenção anunciada |
| Consulta de horários | p95 <= 500 ms para janela comum, sem contar rede do cliente |
| Commit de reserva | p95 <= 1 s, sem depender do WhatsApp |
| RPO | <= 24 h no lançamento; evoluir conforme tração |
| RTO | <= 4 h no lançamento; exercitar restauração |
| Retenção de logs operacionais | 30–90 dias conforme custo e necessidade |

Metas são SLOs de planejamento, não promessas contratuais até validação de carga e operação.

## 16. Fora do MVP

- múltiplos profissionais ou recursos por tenant;
- sobreposição baseada em capacidade;
- pagamento do serviço pelo cliente;
- marketplace e descoberta de prestadores;
- fila de espera;
- pacotes, créditos e recorrência de reservas;
- aplicativo móvel nativo;
- sincronização bidirecional com Google/Outlook;
- campanhas promocionais;
- múltiplos canais de mensagem;
- adjudicação completa de evidências, capabilities distribuídas e plano de controle out-of-band.

## 17. Pendências que não bloqueiam a fundação

1. Escolher provedor de WhatsApp e templates aprovados.
2. Escolher provedor de pagamento e métodos aceitos.
3. Confirmar a semântica da regra excepcional de dez minutos.
4. Definir identidade visual, copy e protótipo final.
5. Confirmar política jurídica de retenção por região e contrato.

Todas devem ser resolvidas antes da homologação da funcionalidade correspondente, mas nenhuma exige mudar o modelo central de reservas.
