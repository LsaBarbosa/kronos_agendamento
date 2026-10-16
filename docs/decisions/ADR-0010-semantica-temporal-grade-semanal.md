# ADR-0010 — Semântica temporal da grade semanal

**Data:** 15/10/2026  
**Status:** aprovada pelo usuário para AGD-011.

## Contexto

O produto definia uma grade semanal recorrente por dia e hora local, mas não fixava granularidade dos limites nem o tratamento dos períodos que cruzam meia-noite. A decisão afeta validação de domínio, contrato HTTP, interface, idempotência e dados persistidos. A grade não deve virar offsets UTC fixos e continua subordinada ao timezone IANA do tenant.

## Decisão

- Início e fim usam `HH:mm` local, em minutos múltiplos de 15. Essa granularidade é independente de `slotStepMinutes`; por exemplo, `09:15–12:45` é válido também com slots de 30 ou 60 minutos.
- Os intervalos são semiabertos `[início,fim)`: períodos adjacentes são permitidos e sobreposição é rejeitada.
- O início deve ser estritamente anterior ao fim. Um período não cruza meia-noite neste MVP.
- A recorrência é persistida como `TIME WITHOUT TIME ZONE` junto ao dia ISO e interpretada no timezone IANA do tenant. Não são persistidos offsets UTC.
- A regra é validada no domínio, no DTO HTTP, na interface, na especificação OpenAPI e por `CHECK` PostgreSQL; a constraint de exclusão existente mantém a proteção de sobreposição no banco.

## Compatibilidade e dados existentes

V1–V8 permanecem imutáveis. V9 primeiro conta e rejeita, com erro explicativo, registros históricos cujos minutos não sejam múltiplos de 15, que tenham segundos/fração ou terminem em `24:00`. A migração não arredonda, apaga nem reescreve dados. O operador executa a consulta de preflight registrada no plano AGD-011, obtém aprovação do administrador do tenant e grava os horários escolhidos explicitamente pelo fluxo administrativo da versão V8 com `expectedVersion`/CAS antes de habilitar a aplicação que aplica V9. Depois, valida e repete Flyway. Se V9 falhar, a transação da migração deixa o schema e os dados na versão anterior; o serviço não deve ignorar a falha nem contornar a constraint.

## Consequências e trade-offs

- Granularidade de 15 minutos torna a grade consistente com a unidade de duração já documentada, mas não obriga o início a coincidir com a grade de slots de 30/60 minutos.
- O upgrade pode parar quando houver dado legado incompatível. Esse bloqueio deliberado exige intervenção e decisão explícita por tenant, porém evita alteração invisível da oferta de horários.
- A política de DST, configuração ausente, publicação pública, exceções/bloqueios, reservas e homologação não é alterada por esta decisão.

## Referências

Decisão aprovada pelo usuário em 15/10/2026; documentos 01 §5.2, 02 §4.3, 03 contrato de grade, critérios AGD-011 do documento 06 e plano `docs/plans/AGD-011-grade-semanal-politicas.md`.
