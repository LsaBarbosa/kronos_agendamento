# Orientações do backend

Complementar o `AGENTS.md` da raiz sem duplicar requisitos de produto.

- Executar Maven pelo `./mvnw` desta pasta, com JDK 21.
- Usar `test` para unidade/arquitetura/web e `verify` para incluir PostgreSQL real.
- Respeitar `com.appagendamento.<modulo>.<camada>`. Entidades JPA e adapters ficam em infraestrutura; regras de domínio não importam Spring nem persistência.
- Não expor entidades JPA diretamente em contratos HTTP. Usar DTOs e validação de borda conforme o documento 03.
- Preservar migrations aplicadas. `ddl-auto=validate` e Flyway são a referência de schema.
- A segurança inicial nega as rotas de negócio. Implementar autenticação e autorização explicitamente em AGD-003, sem liberar todos os caminhos como solução temporária.
- Uma reserva concorrente exige garantia no PostgreSQL; `TimeInterval` e verificação prévia não substituem constraint/transação.
- Testes atuais não comprovam histórias futuras. Atualizar a evidência somente após execução real.
