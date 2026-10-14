# ADR-0003 — Decisões de integração e experiência ainda pendentes

**Data:** 14/10/2026  
**Status:** proposta de acompanhamento; nenhuma decisão de produto adotada  
**Referências:** `docs/development/OPEN-QUESTIONS.md`; documentos 01, 03, 04 e 06.

## Contexto

O MVP depende de decisões sobre identidade administrativa, WhatsApp, cobrança, identidade visual/fluxos, hospedagem, gates de segurança/qualidade e semântica da exceção de dez minutos. A base atual não tem requisitos aprovados suficientes para selecionar fornecedores, ferramentas ou definir esses comportamentos.

## Proposta

Manter as escolhas como pendentes até validação pelos responsáveis. Registrar cada decisão aprovada em ADR própria antes da integração ou do comportamento observável correspondente. Adapters continuam sendo a fronteira arquitetural para provedores externos; a regra dos dez minutos permanece desabilitada. Scans e deploy em staging devem ser incorporados quando suas ferramentas, credenciais e ambiente forem aprovados.

Esta ADR organiza as pendências e não aprova provedor, preço, autorização, retenção, UX ou comportamento de produto. `OPEN-QUESTIONS.md` continua sendo o índice operacional das dúvidas.

## Motivos e trade-offs

- Evita que a infraestrutura ou um adapter simulado seja confundido com aprovação comercial/operacional.
- Permite continuar a fundação independente das integrações.
- A resolução de cada item fica como pré-requisito da história que efetivamente o utiliza.
- Decisões tardias podem exigir ajuste de configuração, contratos ou implantação antes dos respectivos gates.
