# Decisões pendentes

| Tema | Referência | Efeito | Tratamento atual |
|---|---|---|---|
| Identidade administrativa | AGD-003; documento 04 | login, sessão e autorização | endpoints de negócio fechados; escolher provedor antes da integração; ADR-0003 |
| Slugs e ciclo de vida do tenant | AGD-002; documentos 01–03 | palavras reservadas, liberação após encerramento e relação do status com onboarding/assinatura | AGD-002 fixa somente canonicalização técnica e unicidade global persistente; sem lista reservada ou reciclagem. `ACTIVE` não publica nem libera rotas. Aprovar semântica comercial e política de liberação antes de onboarding público; ADR-0005 |
| Troca de timezone do tenant | documento 03, seção 5.1 | impacto em horários futuros e auditoria | a recomendação de bloquear alteração é pendente, não decisão; não há mutação de timezone em AGD-002 |
| WhatsApp e templates | documento 01; AGD-024/030 | códigos e notificações | não implementados; isolar por adapter; ADR-0003 |
| Cobrança e sandbox | AGD-041 | assinatura e reconciliação | não implementadas; não assumir fornecedor; ADR-0003 |
| Identidade visual, fluxo UX e hospedagem | AGD-001/014; documento 04 | identidade, telas finais e entrega | tecnologia React/Vite adotada em ADR-0002; decisões de produto/UX permanecem pendentes; ADR-0003 |
| Janela excepcional de dez minutos | documento 01, seção 7 | semântica ainda indefinida | manter desabilitada; ADR-0003 |
| Retenção, SLOs e operação | documento 06, Sprint Zero | homologação e lançamento | validar com responsáveis antes dos gates correspondentes |
| Imagens/dependências de produção | AGD-001/053/055 | rastreabilidade e atualização | validar política, versões suportadas e digests no ambiente de destino |
| Gates de qualidade e staging | documento 04, seção 18 | scan completo de dependências e ambiente de promoção | commits publicados; App CI e CodeQL passaram nos runs `38663531502`/`38663531528`. Trivy FS completo segue pendente após HTTP 429; staging ainda sem destino; ADR-0003 |

Estas pendências não exigem reabrir a especificação inteira. Continuar a fundação e bloquear somente o comportamento que dependa de decisão ainda não tomada.
