# ADR-0020 — Pipeline persistente de notificações

**Data:** 19/10/2026
**Status:** adotada para o núcleo AGD-030; integração WhatsApp permanece pendente.

## Contexto

AGD-015/021/022/023 já gravam eventos mínimos na outbox na mesma transação da reserva. Ainda não existe consumidor nem estado persistente de notificação. O fornecedor, templates, credenciais e protocolo de webhook não foram aprovados.

## Decisão

- Uma intenção é identificada por tenant, reserva, versão, tipo de mensagem e cliente destinatário. A constraint PostgreSQL e o receipt `(consumer_name,event_id)` evitam duplicação local; nenhuma garantia exactly-once é afirmada fora do banco.
- Consumir evento, gravar receipt e criar intenção é uma transação. A intenção referencia cliente/reserva por FKs tenant-scoped e não copia telefone, nome, request, payload ou código. Snapshot e contato são resolvidos por portas autorizadas no tenant no momento da tentativa.
- Só se cria intenção quando estado e versão atuais continuam compatíveis com o evento. O worker repete a validação imediatamente antes de enviar. Versões obsoletas são encerradas sem transporte.
- Tentativa é persistida antes da chamada externa. A chave externa é estável por intenção. Falha comprovadamente sem efeito pode usar backoff/limite; falha final encerra; resultado incerto vai para reconciliação e não é retomado automaticamente após lease/crash. Claim token impede workers obsoletos de confirmar estado. O worker reivindica uma intenção imediatamente antes de processá-la; o lote limita a quantidade, não reserva leases antecipadamente.
- A ausência de `NotificationTransport` significa envio desabilitado, sem claim/consumo de tentativas. O transport controlado existe só em testes e não representa WhatsApp.
- Evidência de entrega é normalizada e autenticada por porta de adapter, então gravada em inbox PostgreSQL minimizada e deduplicada por `(provider_code, provider_event_id)`. Se ainda não houver referência correlacionada, permanece pendente até associação; a associação posterior e a aplicação monotônica por sequência compartilham a transação de aceite. V23 migra evidências V22 persistidas sem alterar seus dados.
- A consulta administrativa é tenant-scoped, OIDC/associação atual, `no-store` e minimizada; não inclui PII, conteúdo, credenciais ou referências do fornecedor.
- Não há expurgo automático nem retenção aprovada; V22/V23 não criam TTL.

## Consequências e trade-offs

- PostgreSQL continua como autoridade e broker não é requisito. A outbox local e o worker operam pelo menos uma vez na fronteira local; o resultado externo pode permanecer desconhecido.
- O desenho impede retry automático inseguro, mas um evento `OUTCOME_UNKNOWN` exige reconciliação operacional suportada pelo provedor antes de qualquer reenvio.
- Se o resultado ficar `OUTCOME_UNKNOWN` sem referência do provedor e sem API suportada de consulta/idempotência, não é possível correlacionar evidência nem provar o efeito externo. O estado requer intervenção operacional; não há retry automático nem garantia de reconciliação.
- Sem fornecedor/template, o pipeline prova criação, elegibilidade, tentativa controlada, falha, backoff, dedupe e persistência; não prova entrega, webhook real ou conteúdo aprovado.
- Notificações não alteram nem desfazem reservas. Lembretes, invalidação de lembretes e offsets permanecem em AGD-031.
