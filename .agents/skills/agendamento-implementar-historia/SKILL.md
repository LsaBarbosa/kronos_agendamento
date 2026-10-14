---
name: agendamento-implementar-historia
description: Implementar ou completar uma história AGD do App Agendamento conforme a especificação e os critérios de aceite. Usar para desenvolvimento e correções do backlog; não usar para revisão somente leitura nem para inventar escopo de produto.
---

# Implementar história

1. Localizar a raiz do checkout atual e ler `AGENTS.md`. Consultar `docs/development/STATUS.md`, a história no documento 06 e as seções relacionadas de `docs/spec/`.
2. Inspecionar o código e as mudanças existentes. Identificar dependências, critérios e limites do incremento. Não repetir implementação já presente.
3. Registrar plano proporcional em `docs/plans/` conforme `docs/development/PLANS.md`. Resolver escolhas reversíveis dentro da arquitetura; registrar conflito de requisito em `OPEN-QUESTIONS.md` e continuar partes independentes.
4. Implementar o comportamento solicitado respeitando módulos, transações e contrato. Consultar a seção 22 do documento 05 antes de incluir controles avançados. Manter a regra de dez minutos desabilitada enquanto estiver pendente.
5. Usar subagentes somente para frentes independentes úteis, com responsabilidade por arquivo. Consolidar contratos e migrações no coordenador e validar a integração.
6. Usar `agendamento-verificar-invariantes` quando a mudança tocar riscos de domínio, persistência ou efeitos externos. Executar os comandos reais do README; preservar wrappers e lockfiles.
7. Revisar o diff e corrigir defeitos. Atualizar a especificação apenas para refletir decisão autorizada, sem diminuir critérios de aceite.
8. Atualizar `STATUS.md`, `TRACEABILITY.md` e o plano com resultados observados. Distinguir implementação local, testes, CI e staging. Relatar critérios pendentes e próximo passo.

Na raiz, `./scripts/verify.sh` inclui integração. Dentro de `backend/`, `./mvnw test` executa testes unitários, arquiteturais e web; não comprova integração com PostgreSQL.
