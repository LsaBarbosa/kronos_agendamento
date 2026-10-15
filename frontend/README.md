# Frontend

Aplicação administrativa mínima em React, TypeScript e Vite. O incremento AGD-003 inicia login no backend, apresenta tenant/sessão e encerra a sessão local; fluxos de negócio continuam fechados e entram conforme as histórias e decisões de UX aprovadas.

## Requisitos

- Node.js 24 ou compatível com Vite 8 (20.19+ ou 22.12+).
- npm incluído com Node.js.

## Desenvolvimento e validação

Na pasta `frontend/`:

```bash
npm ci
npm run dev
npm run format:check
npm run lint
npm test
npm run check
npm audit --audit-level=high
```

`npm run format` aplica Prettier aos arquivos frontend cobertos; `npm run format:check` valida sem modificar. `npm run lint` executa Oxlint com os plugins React, JSX accessibility e Vitest. `npm run check` valida os tipos TypeScript e gera o build de produção em `dist/`. `npm audit` consulta o registry npm para as dependências travadas. Copie `.env.example` para `.env.local` para definir `VITE_API_BASE_URL`; o padrão local é `/api`.

Para Keycloak local, use o proxy Vite `/api`, `/oauth2` e `/login` e siga [as instruções da raiz](../README.md#identidade-administrativa-local-agd-003). O browser mantém somente o cookie de sessão backend; tokens OIDC não são armazenados no frontend.
