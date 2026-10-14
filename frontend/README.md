# Frontend

Fundação web em React, TypeScript e Vite. A interface ainda não contém fluxos de produto; eles entram conforme as histórias e decisões de UX aprovadas.

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
