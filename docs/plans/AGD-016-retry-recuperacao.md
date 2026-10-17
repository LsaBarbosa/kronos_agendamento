# AGD-016 — Retry seguro e recuperação após reload

## Identificação e objetivo

- **História:** AGD-016 (P0), iniciada após integração de AGD-015.
- **Objetivo:** manter uma identidade por intenção pública, reconciliar resultado incerto após reload sem persistir PII no navegador e retirar SHA sem chave do digest de novos comandos de reserva.
- **Referências:** documento 01 §§6.3–6.5; documento 02 §13; documento 03 §1, §6.5 e contratos de reserva; documento 04 §§12/15; documento 06 AGD-015/016; ADR-0008, ADR-0014 e nova ADR de recuperação/HMAC.
- **Base:** merge de PR #10 em `2ea1914820c533092fcb5429367c97f432ce89c9`; branch `agd-016-retry-recuperacao`.
- **Exclusões:** identidade verificada do cliente, consulta por telefone, cancelamento/remarcação, retenção de negócio/auditoria/idempotência, readiness legal/comercial, staging e fornecedores.

## Comportamento e estados

1. Antes de `POST /appointments`, o frontend cria e persiste somente `Idempotency-Key`, identificador aleatório de recuperação e referência mínima da intenção (slug/serviço/slot); nenhum nome, telefone ou corpo completo vai a storage, URL, histórico ou telemetria.
2. `POST /booking-intents` com CSRF vincula, no servidor e antes do commit possível, tenant, chave, capability e digest HMAC do comando já normalizado. O servidor não persiste nome, telefone ou request bruto. Sem sucesso deste preparo, a UI não envia a reserva.
3. A capability aleatória de 256 bits é persistida no browser somente como identificador de acesso àquela intenção; o servidor armazena apenas SHA-256 da capability. Ela autoriza somente consulta de estado/resumo mínimo daquela intenção, não consulta por telefone, criação alternativa, sessão de cliente, cancelamento ou acesso administrativo. XSS/browser comprometido ainda pode exfiltrar essa capability; risco limitado por escopo e ausência de PII na resposta.
4. `GET /booking-intents/current` é read-only e exige slug/tenant correspondente e capability em header, nunca URL. Retorna `PREPARED_UNKNOWN` (receipt ainda não visível, que não prova ausência de request em execução), `COMPLETED` com receipt mínimo ou resposta não reveladora de vínculo inválido/inexistente. Nunca faz commit como efeito colateral.
5. Enquanto não houver receipt, a UI repete somente o GET em até oito tentativas com backoff limitado; isso cobre uma consulta feita enquanto outro POST ainda está em andamento sem produzir efeitos. Após esgotar as consultas, informa incerteza e permite somente retomar a identidade existente, com reentrada dos dados não armazenados. `COMPLETED` apresenta a reserva original. Retry compara o conteúdo com a ligação HMAC e só reenvia o mesmo comando. Mismatch é explícito; não gera chave nova. Falha de transporte/reconciliação permanece incerta. Rejeição definitiva conhecida preserva dados e permite que a pessoa escolha explicitamente iniciar uma intenção distinta.
6. A aquisição/criação da identidade ocorre sob Web Locks, por slug, e o evento `storage` sincroniza abas já abertas. Elas consultam/repetem a mesma capability/chave; a transação/idempotência do servidor serializa o commit. Em navegador sem Web Locks, o envio é bloqueado com mensagem segura, sem fallback sujeito a corrida. Não se promete deduplicação de intenções independentes.
7. Gate e autorização são revalidados pelo servidor no preparo e antes do replay. A consulta read-only de receipt usa somente capability, tenant e vínculo da intenção; não consulta reservas por identidade do cliente. Receipt confirmado não é apagado nem tornado inválido por falha posterior da capability.

## HMAC e compatibilidade

- Canonicalização de criação pública versionada (`appointments.public.create.canonical.v2`), UTF-8 length-prefixed, sobre tenant confiável, slug, serviceId, instant e offset, nome canônico e telefone E.164.
- Novo digest persistido como `hmac-sha256:v2:<keyId>:<hex>`; chave ativa e keyring são configuração externa obrigatória, estável entre instâncias/reinícios. Sem segredo padrão, aleatoriedade por startup ou fallback SHA.
- Rotação promove novo `keyId`, mantendo chaves anteriores enquanto receipts correspondentes puderem ser repetidos. O registro indica algoritmo/versão/keyId. Chave histórica ausente causa indisponibilidade segura (503 específico), nunca `IDEMPOTENCY_KEY_REUSED`.
- Reads de registros SHA-256 v1 permanecem suportadas calculando a canonicalização v1 original com os dados reintroduzidos; os valores não são reescritos e não há recomputação sem request original. A retenção de receipts e, por consequência, de chaves antigas continua sem política aprovada.
- Migração funcional V14 amplia o formato da coluna/constraint sem alterar ou recomputar V1–V13. Registros legados permanecem intactos.
- Segredos são provisionados pelo gerenciador de segredos no ambiente real. Testes/E2E usam chave sintética explícita, fora do profile de produção. O setup local público precisa receber configuração segura local ignorada pelo Git.

## Segurança e transações

- Recovery capability nunca é credencial de reserva nem prova de identidade. Não usa sessão OIDC administrativa.
- O vínculo de recuperação e digest são persistidos antes do POST confirmador. Mutação, receipt idempotente, história, auditoria e outbox permanecem na transação AGD-015.
- Retry exige mesmo tenant/slug, chave e digest; gates atuais são revalidados antes do replay. Uma consulta não muta, não cria cliente/reserva e não registra auditoria de sucesso.
- Respostas e logs não incluem capability, chave bruta, cookies, nome, telefone, request ou chaves HMAC. A capability não aparece em URL/referrer.
- A capability expira tecnicamente 30 dias após o prebind; após isso, o lookup retorna indisponível e não permite nova intenção automática. Essa validade é independente da retenção de negócio. Não há rotina de exclusão: vínculo, idempotência, auditoria, histórico, outbox e reserva não são apagados por esta expiração.

## Critérios verificáveis

| Critério | Evidência esperada |
|---|---|
| Uma identidade persiste antes da operação, com double-click/abas/retry sem duplicação | Testes de API e PostgreSQL com transações independentes; E2E de browser |
| Reload reconcilia a intenção e recupera receipt sem PII | Keycloak + PostgreSQL + Chrome, POST confirmado/resposta perdida, reload, mesmo `appointmentId`, uma reserva/história/auditoria/outbox |
| Incerteza não vira falha definitiva nem autorização para nova intenção | Tests para receipt ausente, operação concorrente, erros de rede, gate revogado e recovery indisponível |
| Mismatch de conteúdo/chave permanece visível e não modifica o original | Testes digest/canonicalização e UI |
| GET de recovery é read-only, tenant-scoped e minimiza resposta | Testes HTTP/PostgreSQL cross-tenant, contagens sem escrita e DTO allowlist |
| Novo digest é HMAC versionado, estável e rotacionável | Testes config, instâncias/restarts, rotação, keyId histórico ausente e registros v1 |
| V14 é compatível | PostgreSQL fresh V1–V14, upgrade V13→V14, Flyway validate/rerun, grants |
| PII não vai para storage/URL/log/telemetria | Testes browser/storage, logs capturados e resposta de recovery |

## Estado e evidências

- [x] Revisão e integração AGD-015 (PR #10) em merge commit `2ea1914820c533092fcb5429367c97f432ce89c9`; CI push/PR do head corrigido passou. A revisão final encontrou somente lacuna P2 de teste integrado específico do 404 após estado incerto.
- [x] Schema/adapters/API de prebind e recovery com validade técnica de 30 dias, sem expurgo de registros de negócio.
- [x] HMAC versionado, configuração validada e compatibilidade de replay legado.
- [x] Recuperação após reload, corpo em memória apenas, reentrada segura de nome/telefone, sincronização entre abas e ação explícita para iniciar uma nova intenção após receipt confirmado.
- [x] PostgreSQL real/Testcontainers: binding/recovery, read-only, isolamento por capability/slug, expiração lógica, digest legado/rotação, replay HMAC persistido depois da rotação e corrida compartilhada; Flyway V1–V14 fresh, V13→V14, validate/rerun e grants.
- [x] Backend completo: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -ntp spotless:check verify` em `backend/` — 69 testes unitários/arquiteturais e 96 integrações PostgreSQL 16.13/Testcontainers passaram em 16/10/2026.
- [x] Frontend local: format/format:check, lint, 23 Vitest, TypeScript/build e sintaxe Node do script E2E passaram em 16/10/2026.
- [ ] O script Chrome E2E cobre Keycloak/PostgreSQL/Chrome, perda pós-commit, reload e duas abas recuperando o mesmo appointmentId. Em `a7e1a12`, a segunda aba consultou cedo e não repetiu GET; polling read-only limitado foi adicionado em `e9ce946`. Em `0938208`, os diagnósticos provaram que a aba originadora não iniciava recovery GET após timeout. O frontend passou a reconciliar automaticamente a intenção original e recupera o receipt em ambas as abas. Em `f536325`, o browser chegou às duas confirmações, mas a asserção do estado “incerto” rodou depois da recuperação rápida. O teste agora retém somente a resposta GET da aba originadora, verifica controles bloqueados enquanto incerta, libera a resposta, compara IDs e então recarrega; isso torna observável a janela sem mudar o fluxo de produção. CI do novo head ainda deve comprovar a sequência. PR #11 permanece draft. Thread limit impediu nova rodada independente após a revisão inicial.

## Limites

AGD-016 não remove risco de PII: cliente/reserva continuam dados pessoais no banco; recovery capability é bearer com privilégio restrito e validade de 30 dias. Expiração lógica não executa expurgo físico. Prontidão legal/comercial, HTTPS/staging, preflight hospedado, scan completo, retenção aprovada e integrações reais continuam pendentes. Não habilitar dados reais nem alterar os gates de publicação.
