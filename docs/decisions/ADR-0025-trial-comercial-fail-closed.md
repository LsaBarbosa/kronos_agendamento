# ADR-0025 — Concessão de trial com elegibilidade comercial fail-closed

- **Status:** decisão técnica deste incremento; fonte comercial real e política de retenção pendentes.
- **Data:** 21/10/2026.
- **Contexto:** AGD-040 exige trial único por identidade comercial, mas a especificação não identifica uma fonte verificadora nem autoriza inferir identidade de tenant, slug, e-mail, subject OIDC ou dado autodeclarado.

## Decisão

- O núcleo recebe uma identidade comercial opaca e verificada exclusivamente por `CommercialEligibilityIdentitySource`, uma porta server-side. `Optional.empty()` (fonte ausente ou inconclusiva) não concede trial; não há endpoint para declarar uma identidade verificada.
- O adaptador normal do produto é fail-closed. O adaptador sintético existe somente no perfil E2E; a fixture de browser é provisionada por script explícito e não é habilitada no perfil normal.
- A identidade opaca é reduzida a SHA-256 antes da persistência. O valor original não entra no banco, auditoria, outbox, logs ou resposta. A fonte deve retornar token opaco estável, nunca PII ou identificador de baixa entropia.
- `commercial_trial_grant` é um ledger separado do ciclo corrente de `subscription`, com unicidade PostgreSQL global por digest e por tenant. Cancelar/recriar uma assinatura não reabre o benefício. Não há expurgo automático.
- Um trial `TRIALING` dura exatamente 336 horas (`14 × 24h`) entre instantes UTC. É elegível somente enquanto `now < trialEndsAt`; na igualdade ou depois, o CAS `TRIALING/v1 → SUSPENDED/v2` é materializado com história/auditoria na mesma transação. A tolerância de `PAST_DUE` não se aplica ao trial.
- A expiração é verificada no gate de disponibilidade e novamente dentro do commit de uma nova reserva, sob o lock tenant-scoped já compartilhado. Assinatura ausente ou suspensa fecha novas reservas; não altera reservas existentes nem seus fluxos de consulta/cancelamento.
- O catálogo mensal/anual é somente informativo; anual equivale a dez mensalidades. Sem preço/moeda aprovados, checkout e confirmação de pagamento não existem neste incremento. Trial não substitui os gates legais/comerciais de publicação.

## Consequências e limites

- V30 adiciona concessão, assinatura e histórico com FKs restritivas, CAS, índice de expiração e grants runtime mínimos. As migrations V1–V29 permanecem imutáveis.
- A garantia de não repetir trial depende da estabilidade e correção da fonte confiável ainda não aprovada. Até lá, nenhum trial é concedido no perfil normal; E2E/Testcontainers sintéticos demonstram somente o núcleo.
- Retenção e expurgo do digest de elegibilidade exigem decisão de produto/jurídico/operação. Esta ADR não aprova retenção perpétua nem autoriza exclusão.
- Checkout, cobrança, webhooks financeiros, reativação, preços, moeda, integração comercial real e homologação permanecem em AGD-041+ ou nos gates correspondentes.
