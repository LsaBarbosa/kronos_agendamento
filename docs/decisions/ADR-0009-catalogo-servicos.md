# ADR-0009 — Identidade e comandos do catálogo de serviços

**Data:** 15/10/2026
**Status:** adotada para o recorte administrativo AGD-010; buffer e bloqueio público confirmados pelo usuário em 15/10/2026; demais dependências abaixo permanecem abertas.

## Contexto

AGD-010 exige um catálogo tenant-scoped, comparação normalizada dos nomes ativos, mudanças seguras e auditoria. A arquitetura exige PostgreSQL autoritativo, versão/CAS para configuração e uso da fronteira comum de idempotência/auditoria/outbox. Os documentos não fixam algoritmo de normalização nem status inicial implícito.

## Decisão técnica

- Serviço tem UUID estável; seu nome não identifica o agregado nem a operação idempotente.
- Nome de apresentação é removido de espaços externos e normalizado em Unicode NFC. A chave comparável usa NFKC + `Locale.ROOT` lowercase. O banco garante unicidade `(tenant_id, normalized_name)` somente para `ACTIVE`; PostgreSQL decide corridas, não uma consulta prévia.
- Duração é positiva e múltipla de 15. Buffer aceita zero ou valores positivos múltiplos de 15 minutos, decisão aprovada pelo usuário em 15/10/2026 ao autorizar a recomendação. O critério AGD-010 foi alinhado ao modelo 02.
- Criação exige status explícito para não assumir ativação. Mutação usa `expectedVersion` e incrementa versão por CAS.
- Criação, edição e ativação/inativação usam o coordenador comum com escopo versionado, digest determinístico do comando validado e revalidação do vínculo/tenant antes do replay. Resultados persistidos contêm somente DTO lógico do serviço, nunca requisição, cookie ou token.
- Auditoria de mutação compartilha a transação PostgreSQL; eventos de auditoria contêm somente ação, alvo, ator e nomes de campos permitidos. A outbox grava somente `ServiceCreated`, evento já previsto no documento 03 §7, com ID e versão e payload mínimo. Não inventar evento de atualização/status nem publisher para esta história.
- Não há DELETE físico de serviço. Inativação preserva identidade/histórico e libera o nome para outro serviço ativo segundo a regra vigente.
- Rotas públicas permanecem deny-all enquanto a prontidão/publicação do tenant não puder ser verificada com critérios existentes. Não inferir que `Tenant.ACTIVE` basta; consultar pendência de gate em `OPEN-QUESTIONS.md`.
- Preço informativo permanece omitido da API/UI: é opcional e a moeda definida pelo produto ainda não foi aprovada. Não há valor monetário padrão implícito.

## Consequências

- Constraints do PostgreSQL são a proteção final para duplicação sob concorrência; leituras e mutações usam tenant do principal confiável e filtram o tenant na query.
- O algoritmo de comparação e o CAS são decisões técnicas versionáveis. O dado de exibição não é substituído por `normalized_name`.
- AGD-010 fica parcial até implementar o gate explícito de publicação e o fluxo de reserva que prove snapshot imutável do serviço. Sem delete, associações futuras podem referenciar o ID de serviço.
- Snapshot de nome/duração/buffer/preço confirmado será responsabilidade do comando de reserva (AGD-015); catálogo não pode demonstrá-lo antes de haver reservas.

## Referências

Documento 01 §4; documento 02 §§2 e 4.2; documento 03 §§3, 5, 7 e 14; documento 04 §§4–5 e 7; critérios AGD-010, AGD-014 e AGD-015.

## Confirmação de produto — 15/10/2026

O usuário aprovou buffer zero ou positivo em múltiplos de 15 minutos e manter a publicação pública bloqueada até existir condição explícita aprovada. Isso resolve o conflito de buffer, mas não define os critérios de prontidão nem entrega o gate. `Tenant.ACTIVE` continua insuficiente. Benefício: serviços consecutivos quando desejado; trade-off: o administrador precisa configurar a folga necessária. O bloqueio evita exposição prematura e adia o uso público.
