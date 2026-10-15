# Plano — AGD-003: identidade e autorização administrativa

**Estado:** em andamento; o head publicado `591df5ae63aee8e8b24ad1d641a5061c5fbe9af8` passou App CI (push [38757958404](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38757958404), PR [38757962737](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38757962737)) e CodeQL (push [38757958367](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38757958367), PR [38757963338](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38757963338)), incluindo E2E OIDC real. Os commits `4b3e9ad` e `8d78a9f` registraram falhas anteriores no E2E push; a correção final valida `/api/v1/csrf` após SSO e recarrega a UI. PR #2 continua baseado no PR #1 aberto, que deve ser integrado primeiro. **Branch:** `agd-003-autenticacao-autorizacao`.

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
- Runbook [AGD-003-homologacao-https.md](AGD-003-homologacao-https.md) criado. Não existe ambiente HTTPS disponível; nenhum smoke HTTPS local ou homologação observada foi executado.
- Commit `1b509c12b94c7bcbd841265f7edeb4abfe749eff`: App CI + browser E2E passaram em [run 38742515664](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38742515664); CodeQL Java/Kotlin + JS/TS passou em [run 38742515500](https://github.com/LsaBarbosa/kronos_agendamento/actions/runs/38742515500). PR #2 aberto, dependente do PR #1. A revisão remota não foi concluída; resultados completos em `docs/development/VALIDATION.md`.

## Passos restantes

1. Obter revisão e integrar PR #1 antes do PR #2, sem reescrever história publicada; só após integrar #1 atualizar a base do #2 para `main`, validar diff e checks.
2. Após decisão de merge do PR #1, atualizar a base do PR #2 para `main`, verificar o diff e confirmar novamente App CI/CodeQL; executar depois o smoke test do runbook em ambiente HTTPS hospedado, incluindo 30 minutos reais de inatividade e SSO Keycloak persistente.
3. Evoluir E2E cross-tenant nas rotas de negócio assim que forem implementadas; a API atual não possui endpoint de mutação de negócio.
4. Obter matriz RBAC e política de MFA de suporte antes de habilitar papéis de operação.
5. Manter AGD-003 parcial enquanto esses critérios aplicáveis não tiverem prova; AGD-001 segue aberta pelo scan completo de dependências e homologação. AGD-002 continua parcial até integração deste PR e cobertura das rotas de negócio futuras.
