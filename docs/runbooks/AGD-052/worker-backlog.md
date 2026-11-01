# Runbook: backlog antigo ou ciclos do worker com falha

Aplica-se a `WorkerEligibleBacklogOld` (idade > 600 s por 5 min) e
`WorkerRepeatedExecutionFailures` (ao menos 3 falhas em 10 min, persistentes por
2 min). Severidade warning; papel responsável: operações da aplicação. Os
limiares são demonstrativos, não SLO/SLA; designação nominal e plantão pendentes.

## Diagnóstico

1. Confirme worker, `configuration{state="enabled"}`, backlog elegível e idade.
   Gauges globais repetidos por instância são agregados por `max`, não `sum`.
2. Compare heartbeat e timestamp de progresso; verifique leases, fencing e último
   resultado agregado de execução no módulo proprietário.
3. Separe falha do ciclo do worker de falha definitiva ou efeito externo incerto.
   Use somente logs redigidos e métricas de baixa cardinalidade.

## Ações permitidas

- Investigar persistência/leases pelo procedimento autorizado do módulo.
- Reiniciar worker local apenas conforme procedimento do serviço, preservando
  ownership/fencing.
- Para cobrança/notificação com resultado ambíguo, interromper novas tentativas e
  seguir reconciliação; nunca criar outra identidade para contornar incerteza.

Não escalar alerta se o worker estiver intencionalmente desabilitado ou sem itens
elegíveis; essas condições não disparam estas regras.

## Escalonamento

Escalone ao papel de operações da aplicação; envolva o dono do worker conforme o
subsistema. Pessoa, escala e canal não foram designados nesta entrega.
