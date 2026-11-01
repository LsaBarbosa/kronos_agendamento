# Runbook: resultado financeiro ambíguo

`FinancialOutcomeUnknownPending` dispara quando o gauge persistido de pagamentos
é maior que zero por 1 minuto; severidade critical; papel responsável:
operações financeiras. É uma observação de reconciliação já persistida, não cria
operação nem confirma pagamento. O limiar não é SLA; pessoa/plantão pendentes.

## Diagnóstico

1. Confirme `subsystem="payments"`, valor e freshness da fonte sem consultar
   payload, PAN, token, segredo ou referência privada em painel compartilhado.
2. Use a tela/procedimento autorizado de reconciliação financeira para localizar
   a operação e verificar evidência autenticada do provedor.
3. Compare estado e período de assinatura com a evidência persistida; registre
   horário UTC, estado e correlation ID redigido.

## Ações permitidas

- Aguardar ou executar reconciliação suportada e autorizada, preservando a
  identidade lógica existente.
- Atualizar estado apenas por fluxo de negócio autenticado e auditado.

Não iniciar cobrança, retry técnico sem garantia do provedor, operação com nova
identidade ou alteração manual do estado. O alerta não prova pagamento nem sua
ausência.

## Escalonamento

Escalone imediatamente ao papel financeiro e ao responsável técnico pela
integração. Pessoas e plantão continuam pendentes; até haver designação, siga o
processo organizacional aprovado de incidente financeiro.
