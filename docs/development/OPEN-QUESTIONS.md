# Decisões pendentes

| Tema | Referência | Efeito | Tratamento atual |
|---|---|---|---|
| Identidade administrativa | AGD-003; documento 04 | login, sessão e autorização | endpoints de negócio fechados; escolher provedor antes da integração; ADR-0003 |
| WhatsApp e templates | documento 01; AGD-024/030 | códigos e notificações | não implementados; isolar por adapter; ADR-0003 |
| Cobrança e sandbox | AGD-041 | assinatura e reconciliação | não implementadas; não assumir fornecedor; ADR-0003 |
| Identidade visual, fluxo UX e hospedagem | AGD-001/014; documento 04 | identidade, telas finais e entrega | tecnologia React/Vite adotada em ADR-0002; decisões de produto/UX permanecem pendentes; ADR-0003 |
| Janela excepcional de dez minutos | documento 01, seção 7 | semântica ainda indefinida | manter desabilitada; ADR-0003 |
| Retenção, SLOs e operação | documento 06, Sprint Zero | homologação e lançamento | validar com responsáveis antes dos gates correspondentes |
| Imagens/dependências de produção | AGD-001/053/055 | rastreabilidade e atualização | validar política, versões suportadas e digests no ambiente de destino |
| Gates de qualidade e staging | documento 04, seção 18 | elegibilidade CodeQL em repositório privado, destino e ambiente de promoção | lint, npm audit e imagem passaram localmente; CodeQL/CI remoto pendentes por ausência de remote/acesso; Trivy FS limitado por HTTP 429 Maven Central; staging ainda sem destino; ADR-0003 |

Estas pendências não exigem reabrir a especificação inteira. Continuar a fundação e bloquear somente o comportamento que dependa de decisão ainda não tomada.
