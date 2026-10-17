# ADR-0017 — Remarcação administrativa atômica

- **Status:** Aceita para AGD-022
- **Data:** 17/10/2026
- **Contexto:** Alterar a ocupação de uma reserva confirmada exige preservar identidade/snapshot e serializar a disputa com reservas, bloqueios e mudanças de agenda.

## Decisão

- A remarcação atualiza a linha de `appointment` existente; não cancela e recria a reserva. `appointmentId`, cliente, serviço, snapshots e timezone persistido não mudam. Duração e buffer vêm do snapshot confirmado, nunca de uma releitura do catálogo.
- O comando exige `expectedVersion` da reserva e `expectedScheduleRevision` da agenda. São controles distintos: o primeiro protege CAS da reserva; o segundo valida a revisão monotônica comum às mutações da agenda, sem somar versões independentes.
- O comando adquire o lock de tenant `FOR UPDATE` antes de ler a reserva e validar grade, política, exceção, bloqueio e ocupação. Usa `READ_COMMITTED`; leituras de conflito ocorrem em instruções SQL posteriores à espera do lock. A ordem coincide com criação de reserva/bloqueio.
- O próprio appointment é excluído da consulta de ocupação para permitir sobreposição entre o intervalo novo e antigo da mesma reserva. Constraint de exclusão GiST permanece como proteção final contra outras reservas.
- Update, incremento de versão, revisão da agenda, história before/after, auditoria allowlisted, resultado idempotente e `AppointmentRescheduled` compartilham a transação. Rejeições/rollback não avançam revisão nem persistem efeitos de sucesso.
- O digest canônico versionado inclui tenant, alvo, instante e offset, versões esperadas, políticas ordenadas e motivo normalizado. Replay revalida associação/acesso e retorna o receipt original antes de comparar o recurso com as versões já consumidas pelo próprio comando.
- Overrides não são herdados. Quando enviados, aplicam-se somente `OUTSIDE_WEEKLY_SCHEDULE` e `MIN_NOTICE`, com motivo novo. CLOSED, SPECIAL, bloqueios, ocupações, overlap, horizonte, serviço inelegível e configuração ausente seguem as regras não ignoráveis vigentes.
- **Decisão de produto aprovada pelo usuário em 17/10/2026:** uma nova remarcação exige serviço associado atualmente `ACTIVE` no mesmo tenant. A elegibilidade atual é consultada separadamente do snapshot e sincronizada pela leitura `FOR SHARE` do serviço antes do lock da revisão da agenda, preservando a ordem usada nos comandos administrativos de reserva. A ocupação, duração, buffer, nome e demais snapshots permanecem os originais; nenhum override ignora serviço inativo. A verificação ocorre somente na execução de nova mutação; replay autorizado de remarcação concluída retorna o receipt original mesmo após inativação.
- Não existe pipeline persistido de lembretes nesta base. `AppointmentRescheduled` é uma solicitação lógica versionada, não prova de cancelamento ou envio de mensagem. A invalidação e criação efetivas de lembretes ficam para AGD-030/031.

## Consequências e limites

- O lock de tenant serializa mutações por tenant; a simplicidade/garantia no MVP tem custo de concorrência intra-tenant.
- V16 amplia história e privilégios de update necessários ao runtime; V17 torna a CHECK do histórico de remarcação estritamente booleana (`IS TRUE`) e exige todos os limites e versões. V1–V16 permanecem imutáveis. Upgrade falha se encontrar história legada inválida, sem backfill ou exclusão.
- A integração com fornecedores, retenção, homologação e prontidão legal/comercial não são resolvidas por esta história.

## Referências

- ADR-0014, ADR-0016; documentos 01–04 e 06; AGD-022.
