# AGD-024 — Código de acesso por WhatsApp

## Identificação e objetivo
- História P0 AGD-024; branch `agd-024-codigo-acesso-whatsapp`; base integrada `139d59ec47f3e38f46789b59a77f60148f9bf0ca`.
- Referências: documentos 01 §7.1–7.2, 02 §§2/4/9, 03 §§4/10, 04 e 06 AGD-024.
- Implementar desafios de uso único, limites persistidos, verificação, sessão cliente segregada e interface. Exclui reserva/consulta/cancelamento de cliente (AGD-025), envio WhatsApp real (AGD-030), fornecedores, cobrança e retenção de negócio.

## Estado e dependências
- Main integrada contém V1–V18. OIDC administrativo e Spring Session JDBC são autoridade independente e não serão reutilizados como sessão de cliente.
- Fornecedor, template, configuração e segredos gerenciados não foram aprovados. Emissor sintético será exclusivo do profile E2E.
- OTP de 6 dígitos e sessão absoluta de 15 minutos sem renovação são propostas; especificação não fixa estes parâmetros. Produção não emite sessão sem TTL/chaves configurados explicitamente.

## Contrato e estratégia
- Solicitação `POST /api/v1/public/{slug}/access-challenges`, telefone internacional explícito e `Idempotency-Key`; resposta 202 neutra, `no-store`, sem telefone/código/resultado de cadastro. O código é enviado para todo telefone sintaticamente válido, exista ou não cadastro; um desafio de número não elegível nunca emite sessão.
- Verificação `POST /api/v1/public/{slug}/access-challenges/{challengeId}/verify`; apenas código no corpo. Tenant e telefone vêm do desafio persistido. Cookie próprio `AGDCLIENT`, HttpOnly, Secure em HTTPS, SameSite=Lax e Path `/api/v1/client`.
- `GET /api/v1/client/session` e `POST /api/v1/client/session/logout` permitem verificar/encerrar somente a sessão; nenhuma rota de reservas é liberada.
- V19 persiste desafio, solicitações idempotentes, intenções de transporte, bloqueio telefone/tenant e sessão. Locks PostgreSQL serializam múltiplas instâncias. Expiração é `now >= expires_at`; cinco erros bloqueiam o desafio por cinco minutos; a quarta intenção de transporte nos 15 minutos anteriores não chama o emissor e inicia bloqueio de 30 minutos. A cota conta intenções duráveis antes da chamada externa; `delivery_status` distingue `PENDING`, `ACCEPTED`, `FAILED`, `UNKNOWN` e `UNAVAILABLE`, sem alegar entrega. A consulta da janela usa `created_at > now - 15m` (o instante exato de 15m já saiu da janela); o bloqueio vale enquanto `now < blocked_until` e termina exatamente no limite. Limites têm a mesma forma para números elegíveis e não elegíveis, reduzindo diferença observável.
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
- [x] App CI/CodeQL push e PR no SHA final `d678069d41f8872e7c646d4b4345afdf96be27eb`; PR permanece aberto para revisão.

## Validação
| Critério | Comando | Resultado observado |
|---|---|---|
| Backend/PostgreSQL real | `env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ./mvnw -B -ntp spotless:check verify` em `backend/` | `BUILD SUCCESS` em 17/10/2026 no conteúdo funcional pré-commit: 73 testes unitários/arquiteturais + 152 integrações PostgreSQL 16.13/Testcontainers, zero falhas/erros. `ClientAccessIT` passou 12 testes; V19 validada em instalação/upgrade/rerun pelo conjunto de migration tests, grants, limites/janelas, replay, emissor UNKNOWN, sessão e concorrência. |
| Frontend | `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH npm run format:check && npm run lint && npm test -- --run && npm run check` em `frontend/` | Passou em 17/10/2026: Prettier, Oxlint, 23 testes Vitest, TypeScript e build Vite. |
| Browser E2E | `node --check scripts/dev/test-keycloak-browser.mjs`; execução por App CI | Sintaxe passou. E2E real Chrome/Keycloak/PostgreSQL passou em push e PR no head `73fb5fc`: solicitação, verificação, challenge expirado/bloqueado, sessão segregada, cookie/logout, CSRF recente e ausência de PII em URL/storage/logs. Local não executado para preservar Compose compartilhado. Falhas anteriores foram investigadas: fixture não era causa; 403 `ACTION_NOT_ALLOWED` coincidiu com token CSRF divergente, corrigido por refresh antes de cada mutação. Uma tentativa de push também encontrou uma asserção de reload do fluxo público anterior, não relacionada ao novo módulo; o E2E PR passou no mesmo SHA. |
| Diff/script | `git diff --check`; `node --check scripts/dev/test-keycloak-browser.mjs` | Passaram antes da atualização documental atual. |
| Push/PR | App CI e CodeQL no head final | App CI push [39087819372](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39087819372)/PR [39087821951](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39087821951) e CodeQL push [39087819422](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39087819422)/PR [39087822009](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/39087822009) passaram em todos os jobs, incluindo browser E2E. Head `d678069d41f8872e7c646d4b4345afdf96be27eb`; sem merge/deploy. |

## Decisões e retomada
- ADR-0019 documenta separação de sessões, segredos, transporte e replay seguro.
- Limites por IP/dispositivo requerem política e origem confiável; `X-Forwarded-For` não é confiável por padrão.
- Prazo proposto de 15 minutos para sessão de cliente e formato de 6 dígitos aguardam decisão de produto; estes valores ficam configuráveis e não são apresentados como requisitos aprovados.
- Segredos de verificador, cifra telefônica e fingerprint usam propósito e key ID separados; verificação/replay legado exige manter os keyrings referenciados. Rotação do fingerprint permanece fechada até decidir migração/retenção que preserve limites persistidos.
- A revisão foi sequencial: o limite de threads impediu abrir revisores independentes nesta sessão; não equivale a aprovação humana.
- Validade de challenge/session não define retenção de registros de negócio.
- AGD-023 foi integrada em `main` pelo merge `139d59ec47f3e38f46789b59a77f60148f9bf0ca`; continuam pendentes AGD-024/025, AGD-030/031, AGD-032/033 e pendências operacionais.
- AGD-024 permanece parcial até fornecedor/template/segredos aprovados e entrega real homologada.
