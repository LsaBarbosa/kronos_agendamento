# ADR-0023 — Contagem explicável e correção compensatória de faltas

**Data:** 19/10/2026  
**Status:** decisões de produto aprovadas nesta instrução; execução técnica em AGD-032.

## Contexto

AGD-032 já persistia o limite opcional de faltas por tenant, mas não podia calcular contagem nem corrigir `NO_SHOW` sem regras observáveis para fronteira temporal, estado resultante e política ausente. O usuário aprovou explicitamente essas regras nesta instrução; elas não são atribuídas retroativamente à especificação anterior.

## Decisões de produto

1. Cada avaliação captura exatamente um `asOf` pelo `Clock`. O início é `asOf` menos 12 meses de calendário em UTC, preservando hora e ajustando o dia ao último válido no mês-alvo (por exemplo, 29/02 → 28/02). A janela é `[windowStart, asOf)`. O instante do registro original de `NO_SHOW` ancora o fato. O timezone IANA do tenant é apenas apresentação.
2. Correção administrativa somente executa `NO_SHOW → COMPLETED`, com motivo obrigatório. Não há retorno a `CONFIRMED` nem mutação genérica de estado terminal. O fato original permanece e a correção compensatória referencia sua história; somente essa contribuição sai da contagem.
3. Limite ausente aparece como `NOT_CONFIGURED`, sem default. Fatos e contagem continuam visíveis, mas não se infere `CLEAR` nem bloqueio. Ao aprovar ADR-0023, `NOT_TRACKED` indicava a integração futura de AGD-033; a integração agora está implementada na branch AGD-033, e esse valor é legado somente em auditoria histórica.

## Decisões técnicas

- A consulta calcula contagem, política, timezone e fatos em leitura tenant-scoped sob snapshot PostgreSQL coerente. `asOf` é mantido em paginação de keyset; páginas são requisições independentes, não um snapshot físico de longa duração.
- O comando usa lock de tenant compatível com comandos de reserva, `expectedVersion`, digest canônico e escopo de idempotência próprios. Autorização é revalidada antes de executar ou devolver replay.
- Atualização de estado/versão, história compensatória, auditoria minimizada, receipt e evento de outbox `ClientNoShowCorrected` são uma transação. Uma referência única à história original impede correção duplicada; nenhuma história original é removida.
- Motivo completo fica somente na história de domínio autorizada; auditoria/outbox transportam apenas motivo presente, ação e versões/identificadores necessários.

## Consequências e limites

- No escopo original de AGD-032, a correção afetava a contagem derivada, mas não criava/desativava restrições. AGD-033 acrescenta materialização independente; as regras de liberação automática e retroatividade continuam pendentes em ADR-0024.
- O limite configurado não substitui consulta dos fatos; sem política, a interface continua explicável e neutra sobre restrição.
- O mecanismo não decide retenção, bloqueio público, desbloqueio, regras de reincidência ou workflows de AGD-033.
- O outbox registra a correção, não comprova notificação externa.
