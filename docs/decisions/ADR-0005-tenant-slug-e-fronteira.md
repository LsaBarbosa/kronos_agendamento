# ADR-0005 — Canonicalização de slug e fronteira de Tenant

**Data:** 14/10/2026
**Status:** adotada para a implementação técnica de AGD-002; não decide identidade, onboarding público ou política de produto.

## Contexto

AGD-002 exige slug global único e concorrência protegida no PostgreSQL, mas não fixa algoritmo de canonicalização. A arquitetura exige schema compartilhado, acesso administrativo escopado e contexto tenant derivado de identidade autenticada. AGD-003 ainda não definiu provedor nem sessão.

## Decisão

- Canonicalizar slug com Unicode NFKC, `strip()` e lowercase invariável (`Locale.ROOT`); aceitar somente 1–63 caracteres ASCII no formato `[a-z0-9]+(?:-[a-z0-9]+)*`. Não transliterar acentos, substituir espaços internos ou adotar lista de palavras reservadas sem decisão de produto.
- Armazenar somente a forma canônica e impor no PostgreSQL `UNIQUE (slug)` global, sem filtro de status. O slug permanece reservado enquanto a linha existir, inclusive suspensa.
- Validar timezone usando os identificadores disponíveis em `ZoneId.getAvailableZoneIds()`; aceitar identificadores IANA, não offsets arbitrários. Armazenar identificador textual; instantes permanecem `TIMESTAMPTZ`/UTC.
- Manter `ACTIVE` e `SUSPENDED` como status suportados nesta fundação. A criação técnica inicia em `ACTIVE`, mas não habilita rotas ou publicação; prontidão de onboarding/assinatura e exposição pública não existem neste incremento.
- Casos de uso administrativos exigem `AuthenticatedTenant` vindo da API interna de identidade e consultam/alteram por `tenant_id` do contexto. Não há resolução de principal em produção nesta história, nenhuma rota tenant é liberada e os contextos controlados são exclusivos de testes.
- Manter V1 inalterada e introduzir a tabela funcional em V2; a constraint relacional de todas as futuras entidades de negócio deverá incluir/validar o tenant conforme o modelo de cada agregado.

## Consequências e trade-offs

- A canonicalização é determinística e a constraint do banco resolve corridas entre writers; checagem prévia não é autoridade.
- Unicode equivalente pode canonicalizar para ASCII compatível via NFKC; caracteres não conversíveis são rejeitados. Ausência de lista reservada pode permitir nomes potencialmente confusos até decisão de produto.
- `ACTIVE` ainda não expressa elegibilidade comercial nem prontidão de onboarding. Rotas permanecem fechadas; o gate público terá de combinar configuração e estado decidido em histórias futuras.
- A unicidade por índice global impede reciclar slug após suspensão. Remoção/retention/liberação exige requisito e migração separados.
- `AuthenticatedTenant` é um contrato de compilação, não prova criptográfica; AGD-003 deve ser a única origem de produção após autenticação e associação server-side verificadas.

## Referências

- Documento 01, seções 2.2 e 3; documento 02, seções 4.1 e 16; documento 03, seções 1 e 5.1; documento 04, seção 8; AGD-002/003 do documento 06; ADR-0003.
