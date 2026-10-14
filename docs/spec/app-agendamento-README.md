# App Agendamento — pacote de especificação

**Status:** baseline recomendada para planejamento e implementação  
**Data:** 17 de agosto de 2026  
**Público inicial:** prestadores de serviços de beleza e bem-estar  
**Estratégia:** monólito modular no MVP, com controles de consistência fortes no núcleo de agendamento e evolução gradual dos controles de alta garantia

## Objetivo

Este pacote consolida as decisões tomadas para um SaaS de agendamento no qual uma empresa oferece serviços por um link público, administra uma agenda única e cobra assinatura recorrente. Ele separa explicitamente:

- o que precisa existir no MVP;
- as invariantes que não podem ser relaxadas;
- as decisões ainda configuráveis;
- os controles avançados que devem ser implementados somente quando o risco ou a escala justificarem.

O principal recorte é deliberado: entregar primeiro um produto comercializável e confiável, sem transformar mecanismos de recuperação distribuída e adjudicação de evidências em dependências do lançamento.

## Documentos

| Documento | Conteúdo | Uso principal |
|---|---|---|
| [01 — Especificação do MVP](app-agendamento-01-especificacao-mvp.md) | Escopo, atores, regras funcionais, assinatura, notificações, exportação e requisitos não funcionais | Produto, UX e planejamento |
| [02 — Modelo de domínio e estados](app-agendamento-02-modelo-dominio-estados.md) | Entidades, invariantes, máquinas de estado e regras de concorrência | Backend, banco de dados e testes |
| [03 — APIs, eventos e integrações](app-agendamento-03-apis-eventos-integracoes.md) | Contratos REST propostos, erros, idempotência, eventos, webhooks e jobs | Backend, frontend e integrações |
| [04 — Arquitetura e implementação](app-agendamento-04-arquitetura-implementacao.md) | Arquitetura recomendada, módulos, persistência, segurança, observabilidade e implantação | Engenharia e operação |
| [05 — Resiliência e controles avançados](app-agendamento-05-resiliencia-controles-avancados.md) | Consolidação das decisões sobre `UNKNOWN`, capabilities, fences, evidências, quarentena, restore e reconciliação | Evolução enterprise/alta garantia |
| [06 — Backlog e critérios de aceite](app-agendamento-06-backlog-criterios-aceite.md) | Épicos, histórias, prioridades, critérios de aceite e plano de entrega | Execução do projeto e homologação |

## Leitura recomendada

1. Produto e UX validam o documento 01.
2. Engenharia valida as invariantes e estados do documento 02.
3. Frontend e backend acordam os contratos do documento 03.
4. A equipe implementa a fundação descrita no documento 04.
5. O documento 05 permanece como trilha de evolução; só seus fundamentos marcados como MVP entram agora.
6. O documento 06 vira backlog executável e roteiro de homologação.

## Decisões centrais já tomadas

### Produto

- Uma empresa possui uma agenda no MVP.
- O cliente agenda por link público.
- Nome e telefone são obrigatórios.
- A confirmação do agendamento é automática quando o horário é reservado com sucesso.
- O cliente recebe código pelo WhatsApp, válido por cinco minutos, para acessar suas reservas.
- Horários derivam de grade semanal, exceções, bloqueios, duração do serviço e intervalo posterior opcional.
- Não existe sobreposição de agendamentos confirmados; encaixe administrativo também deve respeitar essa invariante.
- Cancelamento, antecedência mínima, janela de reserva, duração dos slots e lembrete são configuráveis dentro de faixas definidas.
- Faltas acumuladas em janela móvel de 12 meses podem bloquear apenas o autoagendamento.

### Comercial

- Assinatura mensal ou anual.
- Plano anual equivale a dez mensalidades, concedendo dois meses de desconto.
- Teste de 14 dias, sem cartão.
- Tolerância de pagamento de sete dias e tentativas em D0, D+3 e D+6.
- Suspensão por inadimplência e reativação imediata após confirmação de pagamento.
- Renovação mensal automática; renovação anual exige autorização e aviso prévio de sete dias.

### Engenharia

- Java 21, Spring Boot 3 e PostgreSQL são a base recomendada.
- O MVP começa como monólito modular, com API REST e workers assíncronos no mesmo código-base.
- A transação de reserva, a restrição de não sobreposição e a chave de idempotência são obrigatórias desde o início.
- Integrações externas são tratadas com outbox, webhooks autenticados, deduplicação e reconciliação.
- Operações ambíguas nunca são convertidas silenciosamente em sucesso ou falha.

## Invariantes de lançamento

Estas condições bloqueiam a liberação se não forem demonstradas por teste:

1. Dois pedidos concorrentes não conseguem confirmar o mesmo intervalo.
2. Repetir a mesma requisição com a mesma chave de idempotência não duplica efeito.
3. Uma chave de idempotência reutilizada com payload diferente é rejeitada.
4. Um cliente bloqueado não consegue criar reserva pública, mas consegue consultar e cancelar reservas existentes.
5. Um administrador consegue criar reserva para cliente bloqueado, com auditoria.
6. Alterações e cancelamentos invalidam lembretes antigos e geram a comunicação correta.
7. O sistema não ativa ou reativa assinatura apenas porque recebeu um webhook repetido ou não autenticado.
8. Exportações são snapshots imutáveis, expiram e não expõem dados de outro tenant.
9. Todas as mutações administrativas relevantes geram trilha de auditoria.
10. Backup restaurado passa por validação antes de voltar a servir tráfego.

## Limites conscientes do MVP

O MVP não tenta resolver agenda de múltiplos profissionais, marketplace, pagamentos do serviço agendado, fila de espera, pacotes de sessões, recorrência de agendamentos, sincronização bidirecional com calendários externos ou mecanismos completos de adjudicação distribuída. Esses itens podem ser adicionados sem invalidar o núcleo, desde que preservem as invariantes de concorrência, idempotência, isolamento por tenant e auditoria.

## Convenções deste pacote

- **MVP:** obrigatório para a primeira versão comercial.
- **Pós-MVP:** extensão funcional prevista, mas não necessária para lançar.
- **Alta garantia:** controle indicado para integrações críticas, grande escala, múltiplas autoridades ou exigência regulatória elevada.
- **Proposto:** valor inicial recomendado que ainda deve ser confirmado por produto/operação.
- **Configurável:** valor escolhido por empresa dentro dos limites do produto.
- `operation_id`, `tenant_id`, `appointment_id` e nomes equivalentes representam identificadores opacos e globalmente únicos.

## Próxima decisão executiva

A equipe já possui informação suficiente para iniciar implementação. Antes da primeira sprint, resta confirmar poucas escolhas que não alteram a arquitetura:

1. provedor de WhatsApp;
2. provedor de cobrança;
3. provedor de identidade administrativa;
4. identidade visual e fluxo final das telas;
5. semântica da regra excepcional de dez minutos.

Essas escolhas devem ser isoladas por adapters para evitar dependência estrutural de fornecedor.
