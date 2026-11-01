# Runbook: worker de exportação falhando

Aplica-se a `ExportWorkerRepeatedFailures` (3 falhas/10 min por 2 min com worker
enabled e backlog elegível). Severidade warning; responsável: operações de exportação.
Limiares demonstrativos, não SLO/SLA; pessoa/plantão pendentes.

O contrato v1.4 não publica contagem `outcome_unknown` para exports, portanto não
há alerta automático de reconciliação de exportações nesta versão.

## Diagnóstico

1. Separe reconciliação persistida de falha no ciclo do worker; confira
   configuração, backlog e freshness das fontes.
2. Consulte job e artefato somente pela API autorizada, respeitando tenant,
   expiração, ETag/CAS e controles de storage.
3. Registre estado, versão e horário UTC sem conteúdo exportado, credencial,
   token, URL assinada ou dados pessoais.

## Ações permitidas

- Preservar o job e reconciliar a mesma identidade lógica.
- Investigar storage/worker pelo procedimento aprovado, sem publicar artefato
  alternativo ou ampliar acesso.

Não criar nova exportação para contornar resultado incerto nem marcar artefato
como disponível sem confirmação persistida. Uma contagem de falhas do worker não
indica por si só que o job falhou definitivamente.

## Escalonamento

Escalone ao papel de operações de exportação e ao responsável técnico de storage.
Designação nominal e plantão continuam pendentes.
