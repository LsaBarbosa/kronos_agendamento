# ADR-0020 — Pipeline persistente de notificações

**Data:** 19/10/2026
**Status:** adotada para o núcleo AGD-030; integração WhatsApp permanece pendente.

## Contexto

AGD-015/021/022/023 já gravam eventos mínimos na outbox na mesma transação da reserva. Ainda não existe consumidor nem estado persistente de notificação. O fornecedor, templates, credenciais e protocolo de webhook não foram aprovados.

## Decisão

- Uma intenção é identificada por tenant, reserva, versão, tipo de mensagem e cliente destinatário. A constraint PostgreSQL e o receipt `(consumer_name,event_id)` evitam duplicação local; nenhuma garantia exactly-once é afirmada fora do banco.
- Consumir evento, gravar receipt e criar intenção é uma transação. A intenção referencia cliente/reserva por FKs tenant-scoped e não copia telefone, nome, request, payload ou código. Snapshot e contato são resolvidos por portas autorizadas no tenant no momento da tentativa.
- Só se cria intenção quando estado e versão atuais continuam compatíveis com o evento. O worker repete a validação imediatamente antes de enviar. Versões obsoletas são encerradas sem transporte.
- Tentativa é persistida antes da chamada externa. A chave externa é estável por intenção. Falha comprovadamente sem efeito pode usar backoff/limite; falha final encerra; resultado incerto vai para reconciliação e não é retomado automaticamente após lease/crash. Claim token impede workers obsoletos de confirmar estado.
- A ausência de `NotificationTransport` significa envio desabilitado, sem claim/consumo de tentativas. O transport controlado existe só em testes e não representa WhatsApp.
- Evidência de entrega é normalizada, autenticada por porta de adapter, deduplicada e aplicada monotonicamente por sequência fornecida pelo protocolo. Sem provedor escolhido, verifier/controller de produção não são registrados; payload bruto não é persistido.
- A consulta administrativa é tenant-scoped, OIDC/associação atual, `no-store` e minimizada; não inclui PII, conteúdo, credenciais ou referências do fornecedor.
- Não há expurgo automático nem retenção aprovada; a V22 não cria TTL.

## Consequências e trade-offs

- PostgreSQL continua como autoridade e broker não é requisito. A outbox local e o worker operam pelo menos uma vez na fronteira local; o resultado externo pode permanecer desconhecido.
- O desenho impede retry automático inseguro, mas um evento `OUTCOME_UNKNOWN` exige reconciliação operacional suportada pelo provedor antes de qualquer reenvio.
- Sem fornecedor/template, o pipeline prova criação, elegibilidade, tentativa controlada, falha, backoff, dedupe e persistência; não prova entrega, webhook real ou conteúdo aprovado.
- Notificações não alteram nem desfazem reservas. Lembretes, invalidação de lembretes e offsets permanecem em AGD-031.
