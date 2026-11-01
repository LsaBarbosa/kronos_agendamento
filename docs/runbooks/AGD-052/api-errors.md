# Runbook: proporção elevada de respostas HTTP 5xx

Regra: razão de 5xx por requisições observadas acima de 5% durante cinco minutos,
com tráfego total maior que zero. Severidade warning; responsável: papel de
operações da aplicação; designação nominal/plantão pendente. O limiar é inicial e
não um SLO/SLA.

## Diagnóstico

1. Confirme janela, tráfego e resultado em `status`, `outcome`, `method` e `uri`
   normalizado. Não filtre por URL literal nem query string.
2. Compare distribuição de latência e estado do target da API.
3. Correlacione com logs redigidos por trace/correlation ID; retire IDs de tenant,
   telefones, tokens, bodies e exception messages antes de compartilhar.
4. Verifique releases/configuração e dependências no painel operacional aprovado.

## Ações permitidas

- Aplicar procedimento de mitigação/rollback já aprovado para o serviço afetado.
- Preservar traces e logs redigidos associados ao intervalo.
- Confirmar depois a queda da taxa com tráfego observado; ausência de tráfego não é
  taxa 5xx zero.

Não repetir comandos financeiros, de reserva ou de mensagem em resposta ao alerta.

## Escalonamento

Escalone ao papel de operações da aplicação após cinco minutos sustentados; se o
erro afetar dados ou pagamentos, envolva também os papéis proprietários. Papéis,
pessoas e plantão não foram designados nesta entrega.
