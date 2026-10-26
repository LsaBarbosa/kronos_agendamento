# Atualização parcial da imagem Keycloak

## Identificação e objetivo

- Escopo: branch `fix/keycloak-image-security`, baseada na `main` observada em `5be58533a84e16e07f598b6a5afc786c99bbd15b`.
- Objetivo: atualizar o rebuild oficial Keycloak 26.8.1 cujo pacote `ca-certificates` contém a correção de CVE-2026-114406, mantendo todos os gates de segurança.
- Imagem anterior: `quay.io/keycloak/keycloak@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244`.
- Candidata aplicada: `quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b`, rebuild oficial de 26.8.1 observado no registro de tags em 23/10/2026.
- Plataformas do manifesto: `linux/amd64`, `linux/arm64` e `linux/ppc64le`; entradas adicionais são attestations. O projeto não fixa `platform`; CI e E2E local executaram `linux/amd64`.

## Estado e decisões

- A mesma referência por digest é usada no serviço Keycloak do Compose e no step Trivy do App CI. O roteiro E2E inicia Keycloak via esse Compose, portanto usa o mesmo artefato verificado.
- Nenhuma alteração foi feita em scanner, versão, severidade, critérios, suppressions ou comportamento de aprovação.
- A comparação Trivy 0.75.0, com bases atualizadas em 26/10/2026 e critérios `--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false`, já existia e foi reutilizada. Não se repetiu o scan sem mudança de pacote/base.
- O rebuild resolve o achado crítico 114406, mas não torna o gate verde: 91403 e 91415 permanecem reportadas como HIGH. Esta mudança é parcial e não representa aceitação de risco.

## Diagnóstico do pacote

| Achado | Imagem anterior | Imagem candidata | Interpretação limitada |
|---|---|---|---|
| CVE-2026-114406 | `ca-certificates 2025.2.80_v9.0.305-91.el9` | Não reportada; inventário contém `ca-certificates 2026.2.92_v9.0.319-90.3.el9_8` | A versão candidata coincide com RHSA-2026:88459. O finding desapareceu no scan do digest candidato. |
| CVE-2026-91403 | `java-21-openjdk-headless 1:21.0.12.1.1-1.2.el9` | Mesmo RPM/versão, ainda reportado HIGH | Relacionada a trust store/eMudhra. Red Hat informa que o OpenJDK RPM não portátil depende de `ca-certificates` e recebe a correção por essa via; Trivy continua associando o finding ao RPM Java. Não se classifica automaticamente como falso positivo nem se suprime. |
| CVE-2026-91415 | Mesmo RPM/versão | Mesmo RPM/versão, ainda reportado HIGH | Vulnerabilidade OpenJDK 2D/font rendering. Não foi transplantada versão Oracle para o RPM Red Hat; permanece pendente de correção/publicação compatível. |

Fontes oficiais: [downloads Keycloak](https://www.keycloak.org/downloads), [Quay tags](https://quay.io/repository/keycloak/keycloak?tab=tags), [RHSA-2026:88459](https://access.redhat.com/errata/RHSA-2026:88459), [RHSB-2026-012](https://access.redhat.com/security/vulnerabilities/RHSB-2026-012), [Red Hat CVE-2026-91403](https://access.redhat.com/security/cve/cve-2026-91403) e [Red Hat CVE-2026-91415](https://access.redhat.com/security/cve/cve-2026-91415). O campo `FixedVersion` vazio do Trivy não é usado para inferir indisponibilidade universal; a evidência do RPM e o advisory são avaliados separadamente.

## Validação

| Critério | Procedimento | Resultado observado |
|---|---|---|
| Compose e scanner no mesmo digest | Busca de referências executáveis e `docker compose ... config --images` com env-file sintético descartável | Ambos apontam ao digest `a78d6b...`; config passou. Referência antiga permanece somente em registro histórico. |
| Scan comparativo | Relatórios Trivy 0.75.0 já gerados para os dois digests, HIGH/CRITICAL, sem ignore-unfixed | 114406 saiu da imagem candidata; 91403 e 91415 permanecem HIGH. O gate de segurança continua bloqueado. |
| Identidade/plataformas | Manifesto OCI do digest e tag oficial 26.8.1 | Digest oficial e plataformas amd64/arm64/ppc64le confirmados. |
| OIDC/browser | Cópia descartável do commit `8b36cfa`, Compose com projeto/portas/imagens/volumes próprios, realm, usuários e dados sintéticos; `CHROME_BIN=/usr/bin/google-chrome node scripts/dev/test-keycloak-browser.mjs` | Passou: login OIDC, tenant, sessão JDBC de 30 minutos, reautenticação SSO, revogação, CSRF/logout, negação de usuário sem associação e ausência de credenciais de teste nos logs do backend. |
| Ambiente compartilhado | Inspeção após cleanup | O projeto descartável foi removido. Os containers compartilhados PostgreSQL/Keycloak permaneceram ativos e inalterados. |
| Upgrade de dados persistidos | Cópia isolada de dados persistidos | Não aplicável: E2E usa banco descartável vazio e a tarefa não atualizou versão Keycloak. Não houve upgrade de realm/volume persistido. |
| CI do PR | App CI, CodeQL e check-runs no head final | Pendente até publicação; esperado que o gate de segurança permaneça vermelho pelos achados HIGH. |

## Encerramento

A mudança publicada atualiza somente os pins executáveis em `compose.keycloak.yaml` e `.github/workflows/backend-ci.yml`. Não altera migrations nem os PRs #24, #25 ou #26. O PR de infraestrutura deve permanecer draft e aberto. Sem merge, deploy ou aceitação do risco. Para reavaliar futuramente, obtenha novo advisory/pacote ou digest oficial, confira o inventário do manifesto por digest, compare com Trivy/base atualizados sob a mesma política, execute o E2E isolado e registre findings antes de alterar qualquer pin.
