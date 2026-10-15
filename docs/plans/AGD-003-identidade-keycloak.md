# Plano — AGD-003: identidade e autorização administrativa

**Estado:** em andamento; PR #1 integrado em `main` via merge commit `a708dcf358e551304018cde22a428a1275de4d13`. PR #2 está baseado em `main`, head `db1387f6b1bbd9aef12cb25689fb09f88bf49c48`. Esse head passou App CI push ([38767072349](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38767072349)) e PR ([38767089103](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38767089103)), além de CodeQL push ([38767072125](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38767072125)) e PR ([38767088614](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38767088614)). Correção `db1387f` invalida sessão JDBC quando usuário/vínculo está inativo; teste local e E2E remoto comprovam cookie antigo rejeitado após reativação. Revisões por agentes não são aprovação humana; GitHub não tinha review registrada. **Branch:** `agd-003-autenticacao-autorizacao`.

## Escopo

- Implementar login OIDC administrativo com Keycloak, Authorization Code, state/nonce/PKCE e redirects restritos; sessão backend JDBC; logout, expiração, revogação, CSRF e proteção de rotas administrativas.
- Persistir identidade por issuer+subject e associação AdminUser–tenant; autorização ativa consultada no servidor; provisionamento local explícito e reproduzível.
- Expor somente contexto administrativo autenticado e consulta do próprio Tenant já implementada; manter as demais rotas de negócio deny-all até suas histórias.
- Fornecer Compose local para Keycloak e banco próprio, realm importável, proxy Vite e UX mínima de entrada/saída/estado.
- Adicionar migrations V3 para identidades/vínculos e V4 para tabelas JDBC de sessão; V1/V2 são imutáveis e Flyway é o único dono do schema de sessão.

## Critérios verificáveis no incremento

- OIDC redireciona para realm compartilhado e client confidencial com redirect URI exata e PKCE S256; navegador não recebe token.
- Somente `(issuer, subject)` cadastrado com AdminUser e vínculo ativos recebe `TENANT_ADMIN`; associação inativa/revogada ou usuário não provisionado falha fechado, sem auto-criação.
- Cada requisição protegida revalida associação e papel no PostgreSQL; desativação bloqueia sessão existente. Contexto tenant não aceita parâmetro do cliente.
- `GET /api/v1/admin/session` mostra apenas tenant autorizado e `GET /api/v1/admin/tenant` lê somente o próprio registro. Demais APIs continuam fechadas.
- CSRF obrigatório em operações não idempotentes sob cookie; erros API 401/403 neutros; logout invalida a sessão local, mantendo a sessão SSO Keycloak ativa.
- PostgreSQL real aplica migrations em banco vazio e upgrade desde V2; sessões têm schema controlado somente por Flyway.
- Teste E2E com Keycloak e PostgreSQL reais simula estado persistido de 25/31 minutos, verifica `max_inactive_interval=1800`, acesso antes/depois, renovação por acesso, cookie local expirado negado e novo OIDC com SSO preservado. O teste rápido não substitui a homologação observada por mais de 30 minutos em ambiente HTTPS.

## Dependências que permanecem em AGD-003 / decisão

- Hosted Keycloak, domínio, TLS, segredo gerenciado, configuração de callback e homologação de produção dependem de decisão/ambiente ainda não fornecidos.
- A especificação cita um administrador principal por tenant e “papel apropriado”, mas não define matriz RBAC. Neste corte só `TENANT_ADMIN` é permitido; roles de operação/suporte e MFA de suporte ficam fechados.
- Operações de negócio não criadas em histórias próprias não são liberadas por autenticação; CRUD completo, auditoria AGD-004 e APIs de catálogo/agenda permanecem posteriores.

## Preparação de homologação HTTPS

O ambiente, o domínio e as credenciais de produção ainda não foram fornecidos. Requisitos de implantação e smoke test esperado estão em [AGD-003-homologacao-https.md](AGD-003-homologacao-https.md). Não reutilizar a configuração Compose local (`start-dev`, HTTP e `SESSION_COOKIE_SECURE=false`) como configuração hospedada. Nenhum teste HTTPS local ou homologação foi executado neste incremento.

## Implementação e evidência local (15/10/2026)

- ADR-0006 e OPEN-QUESTIONS atualizados; Keycloak `26.8.1` fixado no digest `sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`; `.env.keycloak` ignorado e secrets gerados por `scripts/dev/prepare-keycloak.sh`.
- Migrations V3 (identidade/vínculo) e V4 (Spring Session JDBC); V1/V2 preservadas. Flyway fresh V1–V4, upgrade desde V1 e desde V2, validate/rerun e teste de autorização passaram em PostgreSQL 16.13 real.
- Keycloak real importou realm e client confidencial; Chrome headless validou login associado, usuário sem associação negado, cookie HttpOnly/SameSite=Lax, rotação de sessão, tenant de vínculo apesar de query adulterada, usuário/vínculo inativos, CSRF inválido, logout e cookie revogado rejeitado.
- Gate local `./mvnw -B -ntp -Dmaven.repo.local=/tmp/agendamento-m2 spotless:check verify`: `BUILD SUCCESS`, 21 unitários/arquiteturais + 11 integrações; frontend `format:check`, lint, 2 testes e `check` passaram.
- Incremento de expiração: trocado `spring-session-jdbc` direto pelo `spring-boot-starter-session-jdbc` requerido pela auto-configuração do Spring Boot 4; `spring.session.store-type=jdbc` explícito; `AdminAccess` serializável para persistência do principal; cookie usa `Secure=true` por padrão e HTTP local só por override explícito. Browser E2E local com PostgreSQL 16.13, Keycloak 26.8.1 e Chrome passou: sessão armazenada no Flyway schema com `max_inactive_interval=1800`, válida a 25m, `last_access_time` atualizado por GET protegido, cookie rejeitado a 31m (401), UI indica expiração e novo OIDC cria sessão reaproveitando SSO. A idade foi preparada na linha JDBC, não houve espera de 30m nem exclusão manual. `./mvnw -B -ntp spotless:check verify` com JDK 21 passou (21 testes unitários/arquiteturais + 11 de integração); frontend format/lint/test/build passou.
- App CI no commit `4b3e9ad158095ec6e6cfede7eee096858075dd06`: `verify`, security, frontend, secrets e CodeQL passaram nos eventos push/PR; browser E2E falhou nos runs [38754518738](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754518738) e [38754528200](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38754528200), após comprovar expiração, por corrida entre login SSO e carregamento CSRF ao sair. Corrigido localmente: botão Sair fica desabilitado e informa que aguarda CSRF; E2E espera a proteção pronta. Teste completo local passou de novo; gates remotos da correção ainda aguardam publicação.
- Correção `8d78a9f`: botão desabilitado até CSRF, mas a execução push/retry mostrou que a proteção não ficava pronta nesse caminho. O E2E passou a conferir status 200 de `/api/v1/csrf` depois do login SSO, recarregar a UI e só então sair. Reexecução local passou; a mudança seguinte ainda requer App CI/CodeQL remoto.
- Commit `a041abbddc58a15e9809b7bd300916c83bf7266f`: App CI push/PR e CodeQL push/PR passaram, incluindo `oidc-browser-e2e`, verify, frontend, Trivy e segredos. O commit de atualização documental `591df5ae63aee8e8b24ad1d641a5061c5fbe9af8` também passou App CI push/PR e CodeQL push/PR: runs `38757958404`, `38757962737`, `38757958367` e `38757963338`; links em `docs/development/VALIDATION.md`.
- Commit documental `6c46d578740e2590969ce60aaf09f4ab509ba13b`: App CI push/PR e CodeQL push/PR passaram; runs `38759475993`, `38759484191`, `38759476198` e `38759484284`.
- Finding de revisão corrigido no commit `db1387f6b1bbd9aef12cb25689fb09f88bf49c48`: ao detectar associação/admin inativo, a autorização invalida a sessão local; prova E2E após reativar confirma `401` para o cookie anterior. App CI/CodeQL passaram em push/PR nos runs `38767072349`, `38767089103`, `38767072125`, `38767088614`.
- Runbook [AGD-003-homologacao-https.md](AGD-003-homologacao-https.md) criado. Não existe ambiente HTTPS disponível; nenhum smoke HTTPS local ou homologação observada foi executado.
- Commit `1b509c12b94c7bcbd841265f7edeb4abfe749eff`: App CI + browser E2E passaram em [run 38742515664](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38742515664); CodeQL Java/Kotlin + JS/TS passou em [run 38742515500](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38742515500). PR #2 aberto, dependente do PR #1. A revisão remota não foi concluída; resultados completos em `docs/development/VALIDATION.md`.

## Passos restantes

1. PR #1 foi integrado via merge commit e PR #2 foi retargetado para `main`; diff e checks foram confirmados. A integração do #2 está autorizada pelo usuário se checks obrigatórios permanecerem verdes; preservar commits com merge commit.
2. Executar o smoke test do runbook em ambiente HTTPS hospedado, incluindo 30 minutos reais de inatividade e SSO Keycloak persistente.
3. Evoluir E2E cross-tenant nas rotas de negócio assim que forem implementadas; a API atual não possui endpoint de mutação de negócio.
4. Obter matriz RBAC e política de MFA de suporte antes de habilitar papéis de operação.
5. Manter AGD-003 parcial enquanto esses critérios aplicáveis não tiverem prova; AGD-001 segue aberta pelo scan completo de dependências e homologação. AGD-002 continua parcial até integração deste PR e cobertura das rotas de negócio futuras.
