# ADR-0015 — Recuperação de intenção pública e digest HMAC

- **Status:** Aceita; validade técnica de 30 dias para a capability, sem política de expurgo físico dos registros.
- **Data:** 16/10/2026.
- **Contexto:** o retry AGD-015 recupera resultado enquanto a página permanece montada, mas reload perde chave/corpo em memória. O SHA-256 sem chave do comando público permite enumeração offline de campos prováveis se o banco for copiado.

## Decisão

1. Antes de enviar `POST /appointments`, a aplicação cria uma identidade de idempotência e uma capability aleatória de 256 bits no browser. Persiste somente esses identificadores não-PII e referências mínimas da intenção no storage local. A UI não persiste nome, telefone ou request completo.
2. Um endpoint protegido por CSRF de preparo vincula capability, chave, tenant/slug e digest canônico HMAC no PostgreSQL antes da operação confirmadora. A tabela guarda somente hash da capability, referências estritamente necessárias e digest. O digest não é tratado como anonimização nem substitui controles de acesso dos dados de cliente.
3. A capability é bearer acessível ao JavaScript e portanto vulnerável a XSS ou comprometimento do navegador. Seu único poder no servidor é ler estado/resumo mínimo da intenção correspondente, para tenant/slug correspondente. Não autoriza criar reserva alternativa, acessar PII, consultar por telefone, criar identidade/sessão de cliente, cancelar/remarcar ou acessar APIs administrativas. Não é colocada em URL, query, fragment, telemetry ou logs.
4. `GET` de recovery é somente leitura. Se ainda não houver receipt visível, retorna estado de resultado incerto; não declara que a confirmação falhou, não autoriza nova chave e não confirma a reserva. Reenvio exige mesma chave e comando, e revalida gates atuais. Mismatch é explícito. Receipt concluído retorna DTO mínimo sem nome/telefone.
5. Digest novo usa `HmacSHA256`, canonicalização `appointments.public.create.canonical.v2` length-prefixed e valor persistido `hmac-sha256:v2:<keyId>:<hex>`. Keyring vem de configuração externa validada: key id ativo presente, IDs únicos e chaves de pelo menos 256 bits. Falta/invalidade não permite startup/uso do fluxo, não há fallback nem segredo gerado a cada startup.
6. Rotação troca keyId ativo e mantém as chaves anteriores necessárias para validar digests já persistidos. Digest contém versão/algoritmo/keyId. Chave antiga necessária mas ausente resulta em falha segura de disponibilidade, não conflito falso. Digests SHA v1 preexistentes são lidos pela canonicalização SHA v1 original com os dados fornecidos; nenhuma linha antiga é reescrita nem recomputada sem dados originais.
7. V14 altera apenas tipo/constraint compatível de `idempotency_record.request_digest` e cria storage mínimo do vínculo de recovery; V1–V13 permanecem imutáveis. A capability expira 30 dias após o prebind; lookup após esse prazo é não revelador e não autoriza criar outra intenção automaticamente. Expiração lógica não apaga vínculo, idempotência, auditoria, reserva, história ou outbox. Não existe job de expurgo; retenção e exclusão física permanecem sujeitas a política aprovada.
8. A operação pública e o replay continuam subordinados ao gate de publicação e aos gates de prontidão; OIDC administrativo não autentica visitante. Nenhum dado real ou fixture de prontidão é liberado por esta ADR.

## Consequências e trade-offs

- O storage no browser mantém um identificador capability; XSS pode consultar somente o estado e resumo mínimo da reserva da intenção. Nenhum segredo de HMAC é enviado ao navegador.
- Nome/telefone precisam ser reintroduzidos após reload. Digest determinístico valida equivalência e rejeita alterações sem persistir PII; diferenças de normalização/canonicalização exigem nova versão explícita.
- Operação não confirmada pode ficar incerta após os 30 dias ou se a capability ficar indisponível; UI não deve transformar isso em reserva nova automaticamente.
- O HMAC reduz enumeração offline do digest sem chave, mas não remove PII de tabelas cliente/reserva nem protege contra comprometimento da aplicação, keyring ou browser.
- A retenção atual de idempotência/auditoria segue pendente; chaves antigas podem precisar ser mantidas enquanto houver receipts replayáveis.

## Referências

Documento 01 §6.3; documento 02 §13; documento 03 criação pública/idempotência; documento 04 §12 e seção de dados pessoais; documento 06 AGD-016; ADR-0008 e ADR-0014.
