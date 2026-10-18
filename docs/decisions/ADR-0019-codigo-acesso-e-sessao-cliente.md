# ADR-0019 — Desafio de acesso e sessão de cliente

- Estado: Decisão técnica para AGD-024; parâmetros de produto destacados permanecem pendentes.
- Data: 17/10/2026.

## Contexto
AGD-024 autentica telefone verificado por desafio de uso único. A solução deve resistir a concorrência/múltiplas instâncias e não elevar a autoridade administrativa.

## Decisão
- Desafios, limites telefone/tenant e sessões são persistidos em PostgreSQL; operação serializa no banco. Memória de JVM não é autoridade.
- Idempotency-Key identifica a intenção de envio. Retry idêntico não consome nova cota nem chama novamente o transporte; resultado ambíguo não implica reenvio automático.
- Código é CSPRNG; apenas salt/verificador com pepper externo e versão são persistidos. Telefone cifrado; fingerprint usa segredo externo de propósito e versão próprios, com `fingerprint_key_id` persistido. Verificador e cifra usam identificadores ativos e keyrings separados (`CLIENT_ACCESS_VERIFIER_*` e `CLIENT_ACCESS_PHONE_ENCRYPTION_*`), sem reutilizar automaticamente chaves entre finalidades. O protocolo recusa novos desafios se a configuração ativa de fingerprint divergir de registros existentes: não pode resetar limites por rotação unilateral. Rotação do fingerprint depende de uma estratégia de retenção/migração aprovada; não se presume descarte.
- Transporte é fora da transação. Sem integração/segredos de produção, falha de forma segura e preserva resposta neutra.
- Sessão/cookie de cliente é separado de `AGDSESSION`, do principal OIDC e da capability AGD-016. Só fornece autoridade sob `/api/v1/client`; nenhuma rota de reservas é entregue em AGD-024.
- Emissor sintético existe somente no profile `e2e`, com segredo sintético explícito.

## Parâmetros de produto pendentes
- Comprimento de OTP não está especificado; 6 dígitos é proposta, não aprovação.
- A sessão deve ser curta, mas TTL e renovação não estão definidos; propõem-se 15 minutos absolutos sem renovação deslizante. Produção requer configuração explícita e decisão do produto.
- Fornecedor, template, credenciais gerenciadas, hospedagem e homologação permanecem pendentes.
- Chaves antigas de verificador/cifra precisam permanecer provisionadas enquanto houver desafios/sessões que as referenciem. Segredos ficam em configuração externa/gerenciador, nunca em banco, logs, imagem ou Git. O emissor real deve ter idempotência e reconciliação do resultado desconhecido; não existe retry cego.
- Limites por IP/dispositivo não estão definidos; forwarded headers não são confiáveis sem proxy configurado.

## Consequências
- Teste do emissor controlado prova composição, não entrega WhatsApp.
- Timeout permanece resultado desconhecido e não é repetido cegamente.
- Nenhum código/telefone vai a logs, URL, telemetria, outbox ou storage do navegador. Expiração de acesso não aprova política de retenção.
