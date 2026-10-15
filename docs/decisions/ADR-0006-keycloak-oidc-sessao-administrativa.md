# ADR-0006 — Identidade administrativa com Keycloak e sessão no backend

**Data:** 15/10/2026
**Status:** aceita pelo usuário para AGD-003
**Referências:** AGD-003; documentos 01–04 e 06; ADR-0003; ADR-0005.

## Contexto

O MVP precisa autenticar administradores sem transformar claims do provedor em autorização de negócio. A decisão explícita do usuário seleciona Keycloak. O MVP prevê um administrador principal por tenant; suporte operacional e múltiplos papéis ainda não têm matriz de permissões aprovada.

## Decisão

- Keycloak é o provedor OIDC administrativo. O backend Spring Security atua como confidential client, usando Authorization Code, `state`, `nonce` e PKCE `S256`; redirect URIs são allowlists exatas por ambiente.
- A sessão autenticada é mantida no backend com Spring Session JDBC, PostgreSQL e Flyway. A aplicação não entrega nem persiste tokens OIDC para uso do React; chamadas do navegador usam cookie HttpOnly e sessão do backend.
- Cookie usa `HttpOnly`, `SameSite=Lax` para o retorno top-level OIDC e `Secure=false` somente no Compose local HTTP. Ambientes HTTPS devem configurar `Secure=true`; CSRF permanece habilitado em mutações autenticadas por cookie.
- A identidade externa persistida é o par `(issuer, subject)`. A autorização exige AdminUser e associação ativos consultados no PostgreSQL em cada requisição protegida. Email, `tenant_id` recebido do cliente, papéis/claims do OIDC e dados de perfil não criam associação nem concedem acesso.
- Login OIDC sem associação ativa é recusado; não há auto-provisionamento. Uma rotina local explícita pode provisionar um tenant e uma associação de demonstração, em configuração descartável ignorada pelo Git.
- Escopo inicial de papel: `TENANT_ADMIN`, limitado ao tenant associado, coerente com o administrador principal documentado. Operação de plataforma e granularidade RBAC ficam fechadas até decisão de produto.
- Logout invalida e remove a sessão local; não encerra a sessão SSO do Keycloak, que pode autenticar novamente sem pedir credenciais. Expiração/revogação local não depende da sessão SSO. Revogação/desativação do administrador ou da associação é revalidada server-side em cada requisição.
- Expiração por inatividade é configurável e limitada a 30 minutos como baseline; revogação remove a sessão. Produção deve usar TLS, segredo OIDC externo gerenciado, issuer fixo, redirects de domínio exato e Keycloak em modo `start` com banco gerenciado isolado. `start-dev`, HTTP, credenciais geradas e bancos locais distintos são exclusivamente desenvolvimento.

## Consequências e trade-offs

- Sessões JDBC permitem revogação e sobrevivem a reinício do backend, ao custo de consultas à associação em rotas protegidas e dependência operacional do PostgreSQL.
- `SameSite=Lax` permite o callback OIDC de navegação top-level e mantém CSRF obrigatório nas mutações.
- O realm é único para a aplicação; tenants de negócio continuam internos e não são realms OIDC.
- `ADR-0003` continua pendente para hospedagem, domínio/TLS de produção, operação/homologação, provedores comerciais, UX final, retenção/SLO e regra excepcional de dez minutos.
- Ainda é necessário aprovar matriz detalhada de papéis e política MFA para suporte antes de liberar operações privilegiadas fora de `TENANT_ADMIN`.

## Fontes oficiais consultadas

- Keycloak 26.8.1 e documentação do container: https://www.keycloak.org/downloads e https://www.keycloak.org/server/containers
- Keycloak hostname e backchannel: https://www.keycloak.org/server/hostname
- Importação de realms: https://www.keycloak.org/server/importExport
- Spring Security Authorization Code e PKCE: https://docs.spring.io/spring-security/reference/servlet/oauth2/client/authorization-grants.html
- Spring Session JDBC: https://docs.spring.io/spring-session/reference/configuration/jdbc.html
