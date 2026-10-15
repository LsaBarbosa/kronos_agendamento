# Decisões pendentes

| Tema | Referência | Efeito | Tratamento atual |
|---|---|---|---|
| Identidade administrativa — provedor | AGD-003; documento 04 | login e sessão | **Resolvido pelo usuário:** Keycloak via OIDC, sessão JDBC server-side e autorização por vínculo persistido; ADR-0006. |
| RBAC administrativo e suporte | AGD-003; documentos 01, 02 e 06 | autorização granular, acesso da operação e MFA | Especificação define um administrador principal por tenant e menciona “papel apropriado”, sem matriz. AGD-003 limita-se a `TENANT_ADMIN`; aprovar matriz e política de MFA para suporte antes de liberar papéis adicionais. |
| Hospedagem e homologação da identidade | AGD-001/003; documento 04 | issuer/redirects/TLS e prova no ambiente | Keycloak local é reprodutível; domínio, hospedagem, secrets gerenciados e ambiente/staging continuam pendentes. |
| Slugs e ciclo de vida do tenant | AGD-002; documentos 01–03 | palavras reservadas, liberação após encerramento e relação do status com onboarding/assinatura | AGD-002 fixa somente canonicalização técnica e unicidade global persistente; sem lista reservada ou reciclagem. `ACTIVE` não publica nem libera rotas. Aprovar semântica comercial e política de liberação antes de onboarding público; ADR-0005 |
| Troca de timezone do tenant | documento 03, seção 5.1 | impacto em horários futuros e auditoria | a recomendação de bloquear alteração é pendente, não decisão; não há mutação de timezone em AGD-002 |
| Retenção da trilha de auditoria | documentos 01 seção 14, 05 seção 15 e 06 | base legal/contratual, prazo por categoria, legal hold, descarte e minimização | decidir com produto/jurídico/ operação antes de configurar retenção ou excluir eventos; AGD-004 não aplica prazo nem exclusão |
| Retenção de idempotência, outbox e receipts | AGD-005; documentos 03/04/05/06 | prazo de replay, tombstones, retenção de eventos entregues/terminais, deduplicação do consumidor, legal hold e expurgo | pendente de aprovação de produto/jurídico/operação; V6 não define expiração nem job de exclusão. Manter registros até decisão evita reabrir chave ou duplicar efeito. |
| WhatsApp e templates | documento 01; AGD-024/030 | códigos e notificações | não implementados; isolar por adapter; ADR-0003 |
| Cobrança e sandbox | AGD-041 | assinatura e reconciliação | não implementadas; não assumir fornecedor; ADR-0003 |
| Identidade visual, fluxo UX e hospedagem | AGD-001/014; documento 04 | identidade, telas finais e entrega | tecnologia React/Vite adotada em ADR-0002; decisões de produto/UX permanecem pendentes; ADR-0003 |
| Janela excepcional de dez minutos | documento 01, seção 7 | semântica ainda indefinida | manter desabilitada; ADR-0003 |
| Retenção, SLOs e operação | documento 06, Sprint Zero | homologação e lançamento | validar com responsáveis antes dos gates correspondentes |
| Imagens/dependências de produção | AGD-001/053/055 | rastreabilidade e atualização | validar política, versões suportadas e digests no ambiente de destino |
| Gates de qualidade e staging | documento 04, seção 18 | scan completo de dependências e ambiente de promoção | commits publicados; App CI e CodeQL passaram nos runs `38663531502`/`38663531528`. Trivy FS completo segue pendente após HTTP 429; staging ainda sem destino; ADR-0003 |

Estas pendências não exigem reabrir a especificação inteira. Continuar a fundação e bloquear somente o comportamento que dependa de decisão ainda não tomada.
