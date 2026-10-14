# ADR-0004 — Baseline de segurança Spring Boot 4

**Data:** 14/10/2026

**Status:** adotada para o incremento de segurança/qualidade de AGD-001

**Referências:** ADR-0001; documentos 04 e 06; AGD-001.

## Contexto

O scan local da imagem baseada em Spring Boot 3.5.16 encontrou vulnerabilidades HIGH/CRITICAL em Spring Framework 6.2.19, Tomcat 10.1.55, Jackson 2.21.4 e pgJDBC 42.7.11. A versão 6.2.20 do Framework não é uma atualização OSS disponível para esta linha, enquanto a baseline Boot 4 atualiza a plataforma principal. AGD-001 pede gates sem achados críticos/altos relevantes; ignorar os achados ou suprimir regras não é aceitável.

## Decisão

- Substituir a baseline Boot 3.5.16 da ADR-0001 por Spring Boot 4.1.1, mantendo Java 21, arquitetura modular, PostgreSQL 16, Flyway e contratos/comportamento do scaffold.
- Fixar Tomcat 11.0.27, Jackson 3.1.7 e pgJDBC 42.7.14 para obter versões corrigidas verificadas no scan; manter a dependência Testcontainers 1.21.4 explícita porque não é gerenciada pela plataforma Boot 4.
- Adaptar dependências/starter MVC, imports e testes às APIs da geração Boot 4; não alterar comportamento de produto.
- Manter abertas as histórias e gates de homologação, CI remoto e evolução de schema da AGD-001.

## Trade-offs e riscos

- A mudança de versão principal antecipa trabalho de migração e pode exigir adaptação adicional de APIs. O incremento limita o impacto à fundação existente e exige verify completo antes de integração.
- Boot 4 requer Spring Framework 7 e Jackson 3; bibliotecas e integrações futuras devem comprovar compatibilidade antes de serem adotadas.
- A atualização reduz vulnerabilidades detectadas, mas não substitui análise recorrente das dependências nem comprova CodeQL/GitHub Actions ou staging.
- A imagem base será atualizada e escaneada; identidade local por digest não equivale a publicação/promote imutável.

## Fontes oficiais

- [Spring Boot 4.1 — requisitos de sistema](https://docs.spring.io/spring-boot/4.1/system-requirements.html)
- [Spring Framework 6.2.20 OSS support note](https://spring.io/security/cve-2026-47890/)
- [Apache Tomcat 11 changelog](https://tomcat.apache.org/tomcat-11.0-doc/changelog.html)
- [pgJDBC releases](https://github.com/pgjdbc/pgjdbc/releases)

## Evidência

Resultados finais de testes e scans, incluindo digest da imagem, devem ser registrados em [VALIDATION.md](../development/VALIDATION.md) e ligados ao commit de código. Esta ADR não declara os gates remotos ou de homologação aprovados.
