# ADR-0013 — Publicação explícita da página pública

- **Status:** aprovada para AGD-014 com pré-requisitos legais/comerciais ainda bloqueadores
- **Data:** 16/10/2026
- **Contexto:** `Tenant.ACTIVE`, catálogo e configuração de agenda não podem, sozinhos, tornar dados publicamente acessíveis. Onboarding exige perfil e consentimentos/termos; controles de assinatura pertencem ao módulo `subscriptions`/AGD-041, ainda não implementado.

## Decisão

- A página inicia **despublicada**. Um administrador autenticado solicita publicação ou despublicação; `Tenant.ACTIVE` não publica por si.
- O comando é tenant-scoped e usa autorização atual do servidor, CSRF, expectedVersion/CAS, idempotência e auditoria transacional. Replay revalida autenticação, associação e autorização. Despublicar só muda o estado; não remove serviços, agenda ou reservas.
- O servidor avalia prontidão no comando de publicação e em **cada leitura pública**: nome, slug único, timezone IANA, telefone, serviço ativo, período semanal, políticas válidas e evidências aplicáveis de termos/consentimentos, além de gates comerciais aprovados.
- Dados ausentes são bloqueios explícitos, nunca defaults. Não sintetizar aceite, texto/versão legal, trial ou assinatura. Até existir modelo e conteúdo aprovados para termos/consentimentos e integração do gate comercial requerido, a produção não consegue ser efetivamente publicada.
- Somente os GET dos quatro recursos públicos e o POST exato `/api/v1/public/{slug}/telemetry` são liberados. O POST aceita apenas três enums fixos, não recebe identificadores/PII e não altera estado de negócio; seus contadores são agregados e não são fonte de verdade, pois clientes anônimos podem falsificá-los. Nenhum outro método/caminho público é permitido. Slug/tenant é resolvido no servidor; dados internos e recursos de outro tenant não são revelados. Falha da fonte real de ocupações não é interpretada como agenda livre.
- A experiência permite selecionar serviço/data/horário, mas não cria hold nem reserva. Telemetria é mínima e sem dados pessoais. Sem analytics externo/cache/broker.
- Fixtures de prontidão/ocupação ficam fora do source set e do artefato padrão de produção; somente o build Compose dedicado ao browser E2E as inclui. Nunca representam aceite ou prontidão do tenant real.

## Consequências

- Persistir telefone e estado/versionamento de publicação sem editar V1–V11; migration de upgrade inicializa tudo despublicado.
- Exposição pública permanece bloqueada por default até requisitos reais serem evidenciados. AGD-041 e a decisão de conteúdo legal podem permanecer como dependências externas ao código desta história.
- Ocupação persistida e proteção concorrente continuam em AGD-015. A página não afirma reserva concluída.
- Despublicação é reversível e não destrutiva; o gate é reavaliado em toda leitura mesmo se o bit `published` seguir ativo.

## Dúvidas remanescentes

Produto/jurídico deve aprovar texto e versões dos termos/privacidade, tipos e momento dos consentimentos e evidência de aceite. Produto/operação deve confirmar quais estados/planos de assinatura autorizam publicação e integração necessária com AGD-041. Esta ADR não resolve essas políticas.
