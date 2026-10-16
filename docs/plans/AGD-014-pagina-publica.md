# AGD-014 — Página pública

## Objetivo e origem

Entregar a página pública por slug e o gate explícito de publicação, conforme AGD-014 em `docs/spec/app-agendamento-06-backlog-criterios-aceite.md`, onboarding em `docs/spec/app-agendamento-01-especificacao-mvp.md` §3, API pública em `docs/spec/app-agendamento-03-apis-eventos-integracoes.md` §3, frontend/segurança em `docs/spec/app-agendamento-04-arquitetura-implementacao.md` e ADR-0005/ADR-0012. A aprovação de publicação explícita e os limites desta história foram registrados em ADR-0013.

## Escopo

- Persistir publicação explicitamente, sempre iniciando despublicada; administrar publicar/despublicar com identidade OIDC atual, CSRF, CAS, replay idempotente e auditoria na mesma transação.
- Mostrar prontidão e seus bloqueios; revalidar prontidão e publicação em cada leitura pública.
- Expor `GET /api/v1/public/{slug}`, `/services`, `/services/{serviceId}` e `/availability`, além do POST exato de telemetria agregada com enum fechado e sem PII; os demais métodos/caminhos permanecem deny-all.
- Construir página pública responsiva de negócio/serviços/datas/horários; seleção não reserva nem confirma. Evitar respostas antigas sobrescreverem a consulta atual.
- Telemetria mínima, sem PII, sem novo fornecedor/cache/broker.

## Exclusões

Confirmação/hold/reserva, cobrança/assinatura, área do cliente, WhatsApp, página administrativa completa de onboarding, disponibilidade real de reservas, garantia concorrente, cache e deploy/homologação. Não criar consentimento, termos, versões legais, trial ou assinatura ativa fictícios.

## Critérios verificáveis

1. O estado persistido começa despublicado; `Tenant.ACTIVE` não concede publicação.
2. Admin autenticado pode consultar status/prontidão e solicitar publish/unpublish por comando versionado e idempotente. A autorização é revalidada no replay; mudança de estado, auditoria e resultado idempotente confirmam atomicamente; sem outbox inexistente no contrato.
3. Publish retorna bloqueios acionáveis quando qualquer requisito obrigatório não tem evidência. Nenhum termo/consentimento ou gate comercial é sintetizado.
4. Toda leitura pública resolve o tenant pelo slug no servidor e valida publicado + ACTIVE + prontidão atual; estado não elegível é não revelador. Despublicar preserva serviços, agenda e reservas.
5. Catálogo público só inclui serviço ativo do tenant resolvido; ID alheio/inativo/inexistente não revela existência. DTOs excluem buffer, configuração interna, motivos, clientes/reservas, assinatura e auditoria.
6. Disponibilidade reutiliza AGD-013; falha/ausência da fonte obrigatória de ocupações resulta em indisponibilidade temporária (503), nunca agenda vazia implícita. Cálculo continua indicativo.
7. UI responsiva/acessível apresenta estados loading, vazio, erro e indisponibilidade, descarta seleções incompatíveis e ignora respostas obsoletas. Não oferece CTA de reserva/confirmado.
8. Eventos mínimos de visualização/seleção/abandono não contêm PII.

## Prontidão, bloqueios e dependências

Dados exigidos pelo onboarding: nome, slug, timezone IANA, telefone de contato, serviço ativo, período semanal, políticas válidas e consentimentos/termos aplicáveis. A base atual possui nome/slug/timezone, catálogo e grade/política; ainda não possui telefone de contato, modelo/conteúdo/versão de termos, registro de consentimento nem subscrições. Implementar persistência mínima para telefone/publicação, mas a ausência de evidência legal e do gate comercial deve manter publicação bloqueada em produção e ser informada no status administrativo. Um adaptador apenas de teste pode fornecer evidências controladas no perfil E2E, sem uso em produção.

Dependências de assinatura/trial/estado comercial pertencem a AGD-041 e módulo `subscriptions`; nenhum estado `ACTIVE` ou trial será inventado. Caso o requisito de assinatura seja confirmado como gate estrito para publicar, a dependência continuará bloqueadora até integração real de AGD-041.

## Segurança e isolamento

Tenant e ator vêm de `AuthenticatedAdmin`/associação atual, nunca do cliente. Admin mutations exigem sessão + CSRF, expectedVersion e chave idempotente; autorização é checada antes de qualquer replay. APIs públicas usam slug canônico resolvido no servidor e retornos 404 não reveladores. GET está limitado aos quatro recursos implementados. A exceção POST de telemetria aceita só `SERVICE_SELECTED`, `SLOT_SELECTED` ou `FLOW_ABANDONED`, sem corpo adicional/PII e não altera domínio; métricas agregadas podem ser falsificadas e não devem fundamentar decisões comerciais. O restante permanece deny-all.

## Persistência e transação

Inspecionar V1–V11 e criar V12 somente para campos/tabela de perfil público e estado de publicação. Backfill é explicitamente despublicado; publicar usa versionamento CAS. V1–V11 são imutáveis. A publicação/despublicação e a auditoria/evento de idempotência participam da mesma transação. Leituras públicas rechecadas a cada requisição; sem cache.

## Testes/evidências esperadas

PostgreSQL/Testcontainers para instalação, upgrade, Flyway, CAS/replay, isolamento e rollback de auditoria; HTTP para sessão/CSRF, associação revogada, publish bloqueado por evidência de produção, público não publicado/despublicado, DTOs mínimos e ID cross-tenant; disponibilidade via AGD-013 com ocupação indisponível como erro, não vazio; frontend unitário e browser E2E Keycloak/Chrome para imagem especial que inclui fixtures fora do build padrão de produção, visitante, catálogo, consulta/seleção e estados em mobile/desktop.

## Estado

Código local validado em `89347a29a7afc4faf13d0787bfa3c62663c32e94`; head PR `c9a5dd81889c18d8e5174f8220227b435c96010f` passou App CI/CodeQL em push e PR, inclusive browser E2E OIDC. A prontidão de produção continua bloqueada por consentimentos/termos aprovados e integração do gate comercial; não afirmar publicação real nem homologação. Reservas/ocupações reais e exclusão concorrente dependem de AGD-015; scan completo, staging, preflight hospedado e retenção continuam pendentes nas respectivas histórias. PR permanece aberto e sem deploy.
