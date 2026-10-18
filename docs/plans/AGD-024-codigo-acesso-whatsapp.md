# AGD-024 — Código de acesso por WhatsApp

## Identificação e objetivo
- História P0 AGD-024; branch `agd-024-codigo-acesso-whatsapp`; base integrada `139d59ec47f3e38f46789b59a77f60148f9bf0ca`.
- Referências: documentos 01 §7.1–7.2, 02 §§2/4/9, 03 §§4/10, 04 e 06 AGD-024.
- Implementar desafios de uso único, limites persistidos, verificação, sessão cliente segregada e interface. Exclui reserva/consulta/cancelamento de cliente (AGD-025), envio WhatsApp real (AGD-030), fornecedores, cobrança e retenção de negócio.

## Estado e dependências
- Main integrada contém V1–V18; esta correção preserva V1–V19 e acrescenta V20. OIDC administrativo e Spring Session JDBC são autoridade independente e não serão reutilizados como sessão de cliente.
- Fornecedor, template, configuração e segredos gerenciados não foram aprovados. Emissor sintético será exclusivo do profile E2E.
- OTP de 6 dígitos e sessão absoluta de 15 minutos sem renovação são propostas; especificação não fixa estes parâmetros. Produção não emite sessão sem TTL/chaves configurados explicitamente.

## Contrato e estratégia
- Solicitação `POST /api/v1/public/{slug}/access-challenges`, telefone internacional explícito e `Idempotency-Key`; resposta 202 neutra, `no-store`, sem telefone/código/resultado de cadastro. O código é enviado para todo telefone sintaticamente válido, exista ou não cadastro; um desafio de número não elegível nunca emite sessão.
- Verificação `POST /api/v1/public/{slug}/access-challenges/{challengeId}/verify`; apenas código no corpo. Tenant e telefone vêm do desafio persistido. Cookie próprio `AGDCLIENT`, HttpOnly, Secure em HTTPS, SameSite=Lax e Path `/api/v1/client`.
- `GET /api/v1/client/session` e `POST /api/v1/client/session/logout` permitem verificar/encerrar somente a sessão; nenhuma rota de reservas é liberada. Para reconciliação incerta, `GET` aceita `slug` e `challengeId` juntos e retorna true somente se o cookie ativo foi emitido pelo challenge consumido daquele tenant; só expõe um booleano.
- V19 persiste desafio, solicitações idempotentes, intenções de transporte, bloqueio telefone/tenant e sessão. V20 vincula a sessão ao challenge consumido com FK e unicidade tenant-scoped, preservando sessões legadas com `challenge_id NULL` sem considerá-las receipt de outra tentativa. A expiração é avaliada por `now >= expires_at` após obter `FOR UPDATE`. Limites e estados de V19 permanecem inalterados.
- CSPRNG gera código; verificador com salt e pepper externo versionado; telefone cifrado e fingerprint com chave de finalidade/versão distinta (`fingerprint_key_id`). Verificador e cifra também têm identificadores ativos/keyrings separados. Sem segredo default/fallback. Mudança de fingerprint ativa diverge dos registros persistidos e falha neutra para novas solicitações até existir estratégia de rotação/retention aprovada. Envio fica fora da transação; timeout fica desconhecido e não dispara retry automático.
- Mesma Idempotency-Key/comando recupera desafio sem reconsumir cota ou chamar transporte. Challenge novo não limpa limites/bloqueio.
- Configuração real de produção exige `CLIENT_ACCESS_VERIFIER_ACTIVE_KEY_ID`/`CLIENT_ACCESS_VERIFIER_KEY_RING`, `CLIENT_ACCESS_PHONE_ENCRYPTION_ACTIVE_KEY_ID`/`CLIENT_ACCESS_PHONE_ENCRYPTION_KEY_RING`, `CLIENT_ACCESS_FINGERPRINT_KEY_ID`/`CLIENT_ACCESS_FINGERPRINT_KEY` e `CLIENT_ACCESS_SESSION_TTL_SECONDS` fornecidos fora do repositório. A aplicação não tem segredo default; TTL ausente/inválido bloqueia envio e emissão de sessão. Key IDs e keyrings são separados por finalidade. A porta real de transporte não existe até aprovação de fornecedor e template.

## Progresso
- [x] Inspeção da base e pendências de produto/infra (17/10/2026).
- [x] Plano e ADR-0019; atualização de status de AGD-023 integrado.
- [x] Backend, V19, sessão e testes PostgreSQL concorrentes.
- [x] Interface e browser E2E restrito ao profile de teste.
- [x] Gates locais backend/PostgreSQL, frontend e sintaxe do E2E (17/10/2026): 73 testes unitários/arquiteturais + 152 integrações, Prettier/Oxlint, 23 Vitest, TypeScript/Vite e sintaxe Node; associar ao SHA após commit.
- [x] Publicação do PR #16 e browser E2E/CI no head `73fb5fcbb4320fdc2f614e82e51a26b5a158af5c`.
- [x] App CI/CodeQL push e PR no SHA de código `a95d3118ad50c801d49cefcc7ad5da4e9cc26c10`; o commit documental `fb6eb9b` também passou em push/PR, incluindo E2E.
- [x] Investigação do App CI vermelho no head documental `eea67ee`: falha reproduzida e explicada por fixture de AGD-021 próximo da meia-noite; o intervalo de 45 minutos cruzava o fim do período SPECIAL. Fixado `Clock` de teste em 10:03, sem relaxar validações; teste direcionado e suíte completa passaram localmente em PostgreSQL real, e os checks do SHA corrigido passaram.
- [x] No head `2648b27c04496bb379b7cf1de3d811f92a40515f`, App CI push [39089264596](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39089264596)/PR [39089267746](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39089267746) e CodeQL push [39089264593](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39089264593)/PR [39089267650](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39089267650) passaram; E2E Chrome/Keycloak/PostgreSQL passou em ambos eventos.
- [x] Correções da revisão do PR #16: vínculo sessão↔challenge em V20, instante de verificação capturado após lock, HTTP 400 `INVALID_PHONE` como rejeição definitiva e regressões PostgreSQL/browser para sessões antigas e resultados perdidos.

## Validação
| Critério | Comando | Resultado observado |
|---|---|---|
| Backend/PostgreSQL real | `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` em `backend/` | `BUILD SUCCESS` em 17/10/2026 no conteúdo funcional pré-commit: 73 testes unitários/arquiteturais + 152 integrações PostgreSQL 16.13/Testcontainers, zero falhas/erros. `ClientAccessIT` passou 12 testes; V19 validada em instalação/upgrade/rerun pelo conjunto de migration tests, grants, limites/janelas, replay, emissor UNKNOWN, sessão e concorrência. |
| Frontend | `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH npm run format:check && npm run lint && npm test -- --run && npm run check` em `frontend/` | Passou em 17/10/2026: Prettier, Oxlint, 23 testes Vitest, TypeScript e build Vite. |
| Browser E2E | `node --check scripts/dev/test-keycloak-browser.mjs`; execução por App CI | Sintaxe passou. E2E real Chrome/Keycloak/PostgreSQL passou em push e PR no head `73fb5fc`: solicitação, verificação, challenge expirado/bloqueado, sessão segregada, cookie/logout, CSRF recente e ausência de PII em URL/storage/logs. Local não executado para preservar Compose compartilhado. Falhas anteriores foram investigadas: fixture não era causa; 403 `ACTION_NOT_ALLOWED` coincidiu com token CSRF divergente, corrigido por refresh antes de cada mutação. Uma tentativa de push também encontrou uma asserção de reload do fluxo público anterior, não relacionada ao novo módulo; o E2E PR passou no mesmo SHA. |
| Diff/script | `git diff --check`; `node --check scripts/dev/test-keycloak-browser.mjs` | Passaram antes da atualização documental atual. |
| Push/PR | App CI e CodeQL | App CI push [39088050744](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088050744)/PR [39088053109](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088053109) e CodeQL push [39088050747](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088050747)/PR [39088053135](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088053135) passaram no SHA de código; App CI push [39088286472](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088286472)/PR [39088288839](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088288839) e CodeQL push [39088286359](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088286359)/PR [39088288838](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39088288838) passaram no commit documental `fb6eb9b`, incluindo E2E. |

### Correções da revisão do PR #16

Head-base `981622dcc7c9cd30aa2f7fc936ffcf412e4db1b9`. O gate backend completo `spotless:check verify` passou localmente com Java 21: 73 testes unitários/arquiteturais e 155 integrações PostgreSQL 16.13/Testcontainers; `ClientAccessIT` tem 14 testes, incluindo expiração após espera sincronizada no lock e sessões antigas de telefone/tenant diferentes. Upgrade V19→V20 preservou sessão legada, validou Flyway/rerun e privilégios runtime. Frontend: Prettier, Oxlint, 23 Vitest, TypeScript e Vite build passaram; `node --check` e `git diff --check` também. E2E local não executado: com `.env.keycloak` carregado, Compose exige `POSTGRES_PASSWORD` e `APP_DB_PASSWORD` do `.env`; os arquivos ignorados foram preservados. No primeiro CI corretivo do SHA `3c7c383`, o App CI falhou apenas porque o E2E observou resultado stale antes da terceira requisição de retry; o teste foi corrigido para aguardar a terceira chamada. Nova validação remota no head atualizado pendente. Revisão sequencial; tentativa de revisão independente bloqueada pelo limite de threads; sem alegação de aprovação humana.

## Decisões e retomada
- ADR-0019 documenta separação de sessões, segredos, transporte e replay seguro.
- Limites por IP/dispositivo requerem política e origem confiável; `X-Forwarded-For` não é confiável por padrão.
- Prazo proposto de 15 minutos para sessão de cliente e formato de 6 dígitos aguardam decisão de produto; estes valores ficam configuráveis e não são apresentados como requisitos aprovados.
- Segredos de verificador, cifra telefônica e fingerprint usam propósito e key ID separados; verificação/replay legado exige manter os keyrings referenciados. Rotação do fingerprint permanece fechada até decidir migração/retenção que preserve limites persistidos.
- A revisão foi sequencial: o limite de threads impediu abrir revisores independentes nesta sessão; não equivale a aprovação humana.
- Validade de challenge/session não define retenção de registros de negócio.
- AGD-023 foi integrada em `main` pelo merge `139d59ec47f3e38f46789b59a77f60148f9bf0ca`; continuam pendentes AGD-024/025, AGD-030/031, AGD-032/033 e pendências operacionais.
- AGD-024 permanece parcial até fornecedor/template/segredos aprovados e entrega real homologada.
