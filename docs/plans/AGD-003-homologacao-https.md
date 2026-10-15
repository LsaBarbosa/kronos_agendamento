# Roteiro de homologação HTTPS — AGD-003

**Estado:** preparação documental; sem ambiente hospedado ou execução de homologação. O sucesso de Keycloak local em HTTP e dos runners de CI não comprova TLS, cookie `Secure` nem comportamento de produção.

## Pré-requisitos do ambiente

- Domínio público da aplicação, por exemplo `https://app.example.com`, com certificado TLS válido e cadeia confiável no navegador. Frontend e API devem compartilhar a origem pública ou ter CORS explícito e restrito; a topologia recomendada é proxy da API pelo domínio da aplicação.
- Domínio público HTTPS do Keycloak, por exemplo `https://id.example.com`, com certificado válido. Realm `agendamento` separado de `master`; tenants de negócio continuam no banco da aplicação.
- `OIDC_ISSUER_URI` deve ser exatamente o issuer emitido por `https://id.example.com/realms/agendamento`. O issuer deve permanecer idêntico na validação de ID token mesmo se token/JWKS/user-info usarem endereços internos de backchannel.
- `OIDC_AUTHORIZATION_URI` usa o endpoint HTTPS público acessível ao navegador. `OIDC_REDIRECT_URI` deve ser exatamente `https://app.example.com/login/oauth2/code/keycloak`, cadastrado como única redirect URI do cliente no ambiente. `APP_FRONTEND_URL` aponta para `https://app.example.com/admin`.
- Cliente Keycloak confidencial com Authorization Code, redirect URI e Web Origin exatos; PKCE S256 permanece habilitado. Não habilitar registro público nem Direct Access Grants para o cliente administrativo.
- Backend e proxy devem aceitar apenas hosts/origens esperados. Em proxy TLS-terminado, configurar forwarded headers apenas se o proxy substituir cabeçalhos enviados pelo cliente e restringir proxies confiáveis; validar o issuer fixo. Não confiar em `Host`/`X-Forwarded-*` arbitrários.
- `SESSION_COOKIE_SECURE=true`; manter `HttpOnly`, `SameSite=Lax`, CSRF ativo e timeout de sessão de 30 minutos. Não expor tokens OIDC no browser, local/session storage ou logs.
- Keycloak usa modo de produção (`start`, nunca `start-dev`), hostname público estrito, TLS conforme topologia e PostgreSQL/schema/credenciais próprios, separados da aplicação. Banco e serviços administrativos não ficam expostos à Internet.
- Client secret OIDC, senha administrativa inicial, senhas de banco e chaves TLS vêm de secret manager/gestor de certificados. Rotacionar após provisionamento, não versionar nem registrar nos tickets/logs. Aplicar backups/acesso restrito e monitorar validade de certificados.

## Smoke test

Registrar ambiente/domínios, versão do backend e Keycloak, commit, data/hora, operador, resultado de cada passo e evidência sem tokens, cookies ou credenciais. Usar uma conta e tenant descartáveis autorizados para homologação.

1. **TLS e issuer:** abrir `https://id.example.com/realms/agendamento/.well-known/openid-configuration` sem aviso de certificado; confirmar que `issuer` é exatamente `https://id.example.com/realms/agendamento`. O backend deve iniciar e validar esse issuer sem fallback HTTP.
2. **Entrada administrativa:** abrir `https://app.example.com/admin`; sem sessão, a API retorna 401 e a UI oferece login. Iniciar login e confirmar redirect HTTPS ao realm e callback restrito à URI configurada; state, nonce e PKCE são aceitos sem token OIDC visível no storage do navegador.
3. **Autorização:** login com usuário previamente associado e ativo retorna sessão 200, somente tenant/papel autorizados; identidade sem vínculo recebe 403 genérico. Alterar `tenantId` enviado pelo cliente não muda o tenant do principal. Desativar associação/admin em teste controlado bloqueia a sessão já emitida.
4. **Cookies e CSRF:** verificar `AGDSESSION` com `Secure`, `HttpOnly` e `SameSite=Lax`, sem bearer/refresh/ID token persistido no browser. Mutação/logout sem CSRF ou com token inválido retorna 403; chamada válida de logout invalida a sessão local.
5. **Expiração local:** autenticar e não fazer chamadas à API por mais de 30 minutos; depois, requisitar `/api/v1/admin/session` com o cookie anterior. Resultado esperado: 401, nenhum dado protegido, UI informa sessão expirada/não iniciada e oferece novo login.
6. **SSO independente:** sem limpar cookies do domínio Keycloak, iniciar login após a expiração local. O Keycloak pode reutilizar a sessão SSO ativa e retornar por OIDC sem formulário de credenciais; uma nova sessão local é criada e recebe novo identificador. Isso não invalida o teste de expiração da sessão local.
7. **Revogação/logout/logs:** com sessão válida, logout encerra a sessão local; reutilizar o cookie anterior não acessa API protegida. Revogar associação/admin também bloqueia acesso mesmo antes do cookie expirar. Logs e eventos não contêm client secret, senha, authorization code, cookies, access/ID/refresh token ou dados de tenant alheio.

## Registro de resultado

| Campo | Preencher na homologação |
|---|---|
| Ambiente, domínio da aplicação e domínio do Keycloak | Pendente |
| SHA do backend e versões implantadas | Pendente |
| Issuer observado e redirect URI conferida | Pendente |
| Passos 1–7 e resultado/evidência redigida | Pendente |
| Incidentes, correções e reexecução | Pendente |
| Aprovação de homologação | Pendente |

## Referências oficiais

- [Keycloak: configuração para produção](https://www.keycloak.org/server/configuration-production)
- [Keycloak: hostname](https://www.keycloak.org/server/hostname)
- [Keycloak: reverse proxy, TLS e cabeçalhos confiáveis](https://www.keycloak.org/server/reverseproxy)
- [Spring Boot: propriedades comuns, incluindo timeout de sessão](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)
- [Spring Session JDBC](https://docs.spring.io/spring-session/reference/configuration/jdbc.html)
