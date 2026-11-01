# Runbook: notificação ambígua ou worker de notificações falhando

Aplica-se a `NotificationOutcomeUnknownPending` (estado persistido > 0 por 1 min)
e `NotificationWorkerRepeatedFailures` (3 falhas/10 min por 2 min com worker
enabled e backlog elegível). Severidade warning; responsável: operações de
notificações. Pessoa/plantão pendentes; limiares demonstrativos.

## Diagnóstico

1. Separe estado `OUTCOME_UNKNOWN` de falha do ciclo do worker.
2. Verifique se o worker está enabled, há trabalho elegível e a fonte de métricas
   está fresca.
3. Consulte apenas a reconciliação autorizada e dados redigidos. Não inclua
   destinatário, telefone, código, conteúdo de mensagem, token ou resposta bruta.
4. A ausência de fornecedor ou de série de entrega não significa mensagem
   entregue, falha de entrega ou backlog zero.

## Ações permitidas

- Preservar identidade da intenção e evidências autenticadas existentes.
- Consultar/reconciliar pelo protocolo do fornecedor quando houver integração
  homologada e consulta suportada.
- Se o fornecedor não estiver configurado, registrar a limitação operacional;
  não marcar aceite/entrega sintéticos.

Não reenviar mensagem em estado ambíguo às cegas. Aceite pelo transporte não é
entrega confirmada.

## Escalonamento

Escalone ao papel de operações de notificações e ao responsável do fornecedor
quando configurado. Não há destinatário ou plantão aprovado nesta entrega.
