# ADR-0001 — Base do backend e ferramentas

**Data:** 14/10/2026  
**Status:** adotada originalmente; baseline de versão supersedida pela ADR-0004
**Referências:** documento 04; AGD-001; pedido de projeto base em ZIP.

## Contexto

A especificação recomenda Java 21, Spring Boot 3.x, PostgreSQL 16+, monólito modular, Flyway e frontend separado. O pacote deve permitir começar o desenvolvimento sem antecipar o produto inteiro.

## Decisão

- Fixar Spring Boot 3.5.16 como baseline inicial, mantendo Java 21. Esta baseline foi supersedida pela ADR-0004 após achados de segurança.
- Usar Maven Wrapper oficial 3.3.4 com Maven 3.9.11. As dependências de Spring, Hibernate, Flyway, PostgreSQL e Testcontainers usam o gerenciamento do parent Boot.
- Usar PostgreSQL 16 no Compose e no Testcontainers. Permitir um PostgreSQL 16 descartável externo para testes, explicitamente configurado.
- Organizar o backend por módulos e camadas com regras ArchUnit. Usar um pequeno value object de intervalo como fundação de domínio, sem implementar reservas parcialmente.
- Criar a primeira migração para schema/`btree_gist`; adicionar tabelas conforme histórias implementadas. Não inserir dados de clientes nem seeds administrativos.
- Manter endpoints de negócio fechados até AGD-003 e seus casos de uso. Expor apenas saúde técnica sem detalhes.
- Entregar frontend somente como pasta documentada, pois este pacote pede a base do backend. Manter AGD-001 aberta.

## Pontos positivos

Preserva a arquitetura escolhida, reduz decisões iniciais, oferece build repetível e separa implementação real de escopo futuro. O wrapper oficial evita scripts de bootstrap inventados.

## Trade-offs e limites

- A linha 3.x foi mantida por compatibilidade com a baseline; antes de produção, verificar suporte e atualização das dependências conforme a política do projeto.
- Tags Docker de desenvolvimento são mutáveis. O pipeline registra a identidade da imagem construída; uma política de digest de produção ainda precisa ser adotada.
- Perfis de agentes dependem da versão do Codex; validação TOML não demonstra descoberta no cliente do usuário.
- JPA e pacotes preparados não representam modelo de negócio implementado.
- PostgreSQL real/Docker/CI precisam ser verificados em ambiente compatível. Ver `VALIDATION.md`.

## Fontes

- [Spring Boot 3.5: requisitos](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Metadados Maven Central do parent](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)
- [Maven Wrapper: distribuição oficial](https://maven.apache.org/tools/wrapper/maven-wrapper-distribution/)
- [Skills do Codex](https://learn.chatgpt.com/docs/build-skills)
- [Subagentes do Codex](https://learn.chatgpt.com/docs/agent-configuration/subagents)
