# ADR-0020 — Pipeline persistente de notificações

**Data:** 19/10/2026
**Status:** adotada para o núcleo AGD-030; integração WhatsApp permanece pendente.

## Contexto

AGD-015/021/022/023 já gravam eventos mínimos na outbox na mesma transação da reserva. Ainda não existe consumidor nem estado persistente de notificação. O fornecedor, templates, credenciais e protocolo de webhook não foram aprovados.

## Decisão

- Uma intenção é identificada por tenant, reserva, versão, tipo de mensagem e cliente destinatário. A constraint PostgreSQL e o receipt `(consumer_name,event_id)` evitam duplicação local; nenhuma garantia exactly-once é afirmada fora do banco.
- Consumir evento, gravar receipt e criar intenção é uma transação. A intenção referencia cliente/reserva por FKs tenant-scoped e não copia telefone, nome, request, payload ou código. Snapshot e contato são resolvidos por portas autorizadas no tenant no momento da tentativa.
- Só se cria intenção quando estado e versão atuais continuam compatíveis com o evento. O worker repete a validação imediatamente antes de enviar. Versões obsoletas são encerradas sem transporte.
- Tentativa é persistida antes da chamada externa. A chave externa é estável por intenção. Falha comprovadamente sem efeito pode usar backoff/limite; falha final encerra; resultado incerto vai para reconciliação e não é retomado automaticamente após lease/crash. Claim token impede workers obsoletos de confirmar estado. O worker reivindica uma intenção por iteração, executa preflight e consulta token/lease imediatamente antes do transporte; lote limita quantidade, não reserva leases antecipadamente. Se o claim deixar de ser atual, o transporte não é chamado. O sweeper pode classificar o claim vencido como `OUTCOME_UNKNOWN`, inclusive se a validade se perder antes do transporte, de forma conservadora.
- A ausência de `NotificationTransport` significa envio desabilitado, sem claim/consumo de tentativas. O transport controlado existe só em testes e não representa WhatsApp.
- Para lembretes, elegibilidade futura é distinta do resultado de transporte: V25 persiste `reminder_eligible`. Invalidar um lembrete pendente/retry torna-o `OBSOLETE`; invalidar um claim marca-o inelegível, mas preserva claim, lease e tentativa `STARTED`. A autorização imediatamente antes do envio consulta essa marca. Se a chamada externa já começou, a invalidação não apaga um aceite/referência nem `OUTCOME_UNKNOWN`; o resultado continua ligado ao claim e à tentativa original. Retry conhecido sem efeito após invalidação não reabre o envio. Nenhum lock/transação de banco é mantido durante a chamada externa.
- Evidência de entrega é normalizada e autenticada por porta de adapter, então gravada em inbox PostgreSQL minimizada e deduplicada por `(provider_code, provider_event_id)`. `recordEvidence` e `markAccepted` adquirem o mesmo advisory lock transacional pela chave `(provider_code, provider_message_reference)` antes de ler ou alterar inbox/intenção. Assim, se a admissão serializar primeiro, o aceite posterior enxerga e reconcilia a evidência; se o aceite serializar primeiro, a admissão posterior correlaciona à intenção aceita. Transações permanecem curtas e nenhuma chamada externa ocorre sob lock. O caminho de duplicata também tenta reconciliar pendências daquela referência antes de retornar `DUPLICATE`. Aplicação é monotônica por sequência. Não há reconciliador periódico separado: o lock comum cobre a corrida entre escritores e a reconciliação ocorre nos dois caminhos; um `OUTCOME_UNKNOWN` sem referência/consulta suportada permanece fora dessa garantia. V23 migra evidências V22 persistidas sem alterar seus dados.
- A consulta administrativa é tenant-scoped, OIDC/associação atual, `no-store` e minimizada; não inclui PII, conteúdo, credenciais ou referências do fornecedor.
- Não há expurgo automático nem retenção aprovada; V22/V23 não criam TTL.

## Consequências e trade-offs

- PostgreSQL continua como autoridade e broker não é requisito. A outbox local e o worker operam pelo menos uma vez na fronteira local; o resultado externo pode permanecer desconhecido.
- Advisory locks por hash de provedor/referência podem serializar chaves distintas em caso de colisão de hash, causando apenas contenção adicional. A garantia depende de todas as escritas de admissão e aceite usarem o mesmo protocolo transacional.
- O desenho impede retry automático inseguro, mas um evento `OUTCOME_UNKNOWN` exige reconciliação operacional suportada pelo provedor antes de qualquer reenvio.
- Se o resultado ficar `OUTCOME_UNKNOWN` sem referência do provedor e sem API suportada de consulta/idempotência, não é possível correlacionar evidência nem provar o efeito externo. O estado requer intervenção operacional; não há retry automático nem garantia de reconciliação.
- Uma reserva cancelada/remarcada não pode retractar uma chamada externa já iniciada. A elegibilidade é invalidada para novos envios e preflight; o resultado da tentativa em voo continua sendo aceite/correlação ou `OUTCOME_UNKNOWN`, não é reescrito como simplesmente obsoleto. Isso não oferece cancelamento de mensagem em voo nem garantia exactly-once externo.
- Sem fornecedor/template, o pipeline prova criação, elegibilidade, tentativa controlada, falha, backoff, dedupe e persistência; não prova entrega, webhook real ou conteúdo aprovado.
- Notificações não alteram nem desfazem reservas. Lembretes, invalidação de lembretes e offsets permanecem em AGD-031.
