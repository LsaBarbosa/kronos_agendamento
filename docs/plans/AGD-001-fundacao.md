# AGD-001 — Fundação do projeto

## Objetivo e escopo

Completar a fundação backend/frontend e produzir evidências locais para AGD-001. A história permanece aberta até CI remoto, upgrade de migration funcional, elegibilidade SAST e homologação/staging serem demonstrados.

Referências: documento 04; documento 06, incremento A e AGD-001; ADRs 0001, 0002 e 0004.

## Progresso

- [x] Organizar sete documentos sem alterar seu conteúdo.
- [x] Preparar orientação do Codex, skills e perfis de revisão.
- [x] Criar backend Java 21/Spring Boot; baseline 3.5.16 supersedida por Boot 4.1.1 após achados de segurança, conforme ADR-0004.
- [x] Preparar módulos, segurança inicial, correlação e `TimeInterval`.
- [x] Preparar Flyway, Compose, testes e workflow de CI.
- [x] Consolidar o resultado final de validação em VALIDATION.md.
- [x] Registrar React/Vite como escolha reversível e preservar UX/hospedagem em aberto (14/10/2026; ADR-0002).
- [x] Implementar base frontend com configuração, testes, build e job de CI (14/10/2026; lockfile e build local).
- [x] Executar suíte backend em JDK 21 e PostgreSQL 16 real (14/10/2026; 18 unitários/arquiteturais + 5 integração).
- [x] Executar Compose, health/readiness e build de imagem (14/10/2026; endpoints UP e identidade em VALIDATION.md).
- [x] Adicionar formatter/linter frontend/Java, npm audit, CodeQL e Trivy para configuração/segredos/imagem (14/10/2026; resultados locais e limite HTTP 429 documentados em VALIDATION.md).
- [x] Inicializar Git na raiz correta; primeiro commit local coeso a ser registrado nas evidências após criação, excluindo artefatos e `.env`.
- [ ] Criar commit local de evidências e relacioná-lo ao commit de código em VALIDATION.md.
- [ ] Executar job de CI em pipeline limpo.
- [ ] Demonstrar migração funcional V1→V2 junto de AGD-002; também aplicar ambas do zero.
- [ ] Demonstrar CodeQL remoto, staging/homologação, smoke/E2E e promoção autorizada quando o destino estiver definido.

## Descobertas e decisões

- A especificação recomenda Spring Boot 3.x; o scaffold foi atualizado para Boot 4.1.1 para corrigir achados do scan. A mudança principal e os riscos estão na ADR-0004; verificar compatibilidade de dependências novas.
- Usar agente Mockito explícito na JVM de testes para evitar dependência de autoanexação no Java 21; consultar documentação oficial do Mockito e configuração do POM.
- O socket Docker foi inicialmente bloqueado pelo sandbox; após autorização, os testes PostgreSQL e o smoke Compose passaram. Ver VALIDATION.md.
- Entregar somente preparação de módulos ainda sem história implementada, com `package-info.java`, em vez de CRUDs ou entidades inventadas.
- O documento 04 admite React/Vite se SSR ainda não for necessário; escolha reversível registrada em ADR-0002. UX e hospedagem permanecem pendentes.
- O checkout fornecido não contém metadados de Git. Dois subagentes fizeram revisões independentes somente leitura; os achados corrigíveis foram incorporados e as pendências registradas.
- Oxlint foi escolhido no lugar de typescript-eslint porque o TypeScript 7.0.2 existente não satisfaz o peer range de typescript-eslint 8.71.1 (`typescript <6.1`); nenhuma resolução por `--force`/`--legacy-peer-deps` foi usada. Oxlint 1.87.0 declara suporte a lint com TypeScript 7.0+.
- `backend/.dockerignore` exclui `.env*`, chaves privadas e `.git` do contexto. As bases JDK/JRE do backend são Ubuntu 24.04, fixadas por digest; a imagem não foi publicada.
- Não há contrato OpenAPI de produto ainda; esse gate fica não aplicável até um contrato ser aprovado. O upgrade de schema não será simulado com migration vazia.

## Validação

| Critério de aceite | Procedimento/comando | Resultado esperado | Resultado observado |
|---|---|---|---|
| Frontend React/TypeScript | `npm ci && npm run format:check && npm run lint && npm test && npm run check && npm audit --audit-level=high` | qualidade e dependências sem high/critical | passou; 2 testes, TypeScript/build e audit 0 vulnerabilidades; VALIDATION.md |
| PostgreSQL real | `./mvnw -B -ntp spotless:check verify` com Docker | integração PostgreSQL 16 aprovada | passou: 18 testes e 5 integrações em PostgreSQL 16.13; VALIDATION.md |
| Imagem | `docker build --pull --iidfile /tmp/agendamento-image-id.txt -t agendamento-backend:ci backend`; Trivy image | digest identificado, sem high/critical | passou localmente após fixar Ubuntu 24.04 por digest; 0 achados em OS/JAR; VALIDATION.md |
| Formatação, lint e segredos/config | Prettier, Oxlint, Spotless e Trivy config/secret | sem violações | passou localmente; VALIDATION.md |
| Dependências fonte | npm audit e Trivy FS | sem high/critical | npm audit passou; scan FS não concluiu por Maven Central HTTP 429. Imagem runtime passou; repetir fonte após rate limit; VALIDATION.md |
| SAST/CI remoto | workflows CodeQL e `App CI` no GitHub Actions | análise executada no commit publicado | configurados, não executados: ausência de remote e acesso GitHub detectável |
| Migration compatível | `./mvnw verify` com cenários de schema vazio e upgrade V1→V2 | duas trajetórias aprovadas | pendente da migration funcional AGD-002; V1 vazia passou, sem segunda versão |
| Staging/DoD | deploy em staging autorizado e smoke/E2E | evidência funcional e aprovação do ambiente | pendente: UX, ambiente e credenciais não definidos |
| Pipeline limpo | workflows `App CI` e `CodeQL` no GitHub Actions | jobs passam no commit local publicado | não executado: sem remote e sem acesso GitHub detectável |

## Encerramento ou retomada

Manter AGD-001 aberta. Próximo passo para CI: fornecer URL `OWNER/REPOSITORY` e acesso GitHub local autorizado (não compartilhar token); então publicar e executar `App CI` e `CodeQL`. Em paralelo, iniciar AGD-002 com tenant e isolamento; criar migration V2 funcional e testar banco vazio e upgrade de V1 antes de fechar versionamento.
