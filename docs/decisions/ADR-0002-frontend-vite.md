# ADR-0002 — Fundação do frontend com React e Vite

**Data:** 14/10/2026  
**Status:** adotada para AGD-001  
**Referências:** documento 04, seções 1 e 20; AGD-001 no documento 06.

## Contexto

AGD-001 exige uma base React/TypeScript. O documento 04 recomenda Next.js para SSR/metadados públicos e admite React/Vite quando SSR não for necessário. Fluxo público, identidade visual e implantação final ainda não foram decididos.

## Decisão

Usar React 19 com TypeScript e Vite 8 para a fundação local e build de frontend. O incremento não implementa páginas públicas ou administrativas, não estabelece identidade visual e não escolhe hospedagem. Se a necessidade de SSR for aprovada, a escolha pode ser revista antes das páginas públicas.

## Motivos e trade-offs

- Vite atende ao critério de frontend React/TypeScript e mantém a configuração pequena enquanto não existe fluxo aprovado.
- A aplicação gera assets estáticos, com baixo custo local e sem introduzir um servidor Node de produção.
- A escolha deixa a camada SSR/metadados públicos para uma decisão posterior; migrar poderá exigir ajustes no roteamento e na entrega dos assets.
- Dependências estão travadas em `package-lock.json`; atualização deve ocorrer de forma deliberada.

## Referências técnicas consultadas

- [Vite — Getting Started](https://vite.dev/guide/), inclusive compatibilidade de Node.
- [React — Add React to an Existing Project](https://react.dev/learn/add-react-to-an-existing-project).
- Versões estáveis consultadas em 14/10/2026: React 19.3.0, Vite 8.3.4, `@vitejs/plugin-react` 6.1.2, TypeScript 7.0.2 e Vitest 5.0.4; o lockfile é a referência efetiva das dependências resolvidas.
