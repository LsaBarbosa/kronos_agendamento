# Runbook: worker habilitado sem progresso e com trabalho

Regra: cinco minutos sem timestamp de progresso (ou sem a série) mais dois minutos
de persistência, **somente** quando `configuration{state="enabled"}=1` e backlog
elegível maior que zero. Severidade warning; responsável: papel de operações da
aplicação; designação nominal/plantão pendente. Limiar de demonstração, não SLO.

## Diagnóstico

1. Confirme worker, instance, configuração enabled, heartbeat e progresso.
2. Confirme `backlog_eligible>0`; `no_work` é ciclo válido, e disabled/missing
   configuration não deve ser interpretado como incidente de progresso.
3. Compare heartbeat (polling) a progress (avanço persistido); não os confunda.
4. Verifique logs redigidos e último erro de execução do worker proprietário.

## Ações permitidas

- Investigar estado de lease/claim e fila persistida pela ferramenta autorizada do
  módulo, sem expor payload/PII.
- Reiniciar somente worker local dedicado se o procedimento do módulo permitir e
  preservar fencing/ownership.
- Para notificação/cobrança com resultado ambíguo, parar e reconciliar; não reenviar.

## Escalonamento

Escalone ao papel de operações da aplicação após sete minutos sem progresso com
trabalho elegível. Designação de pessoa e escala continua pendente.
