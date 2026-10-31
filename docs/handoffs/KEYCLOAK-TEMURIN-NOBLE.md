# Handoff: protótipo Keycloak Temurin em Noble

**Resultado do gate automatizado: APROVADO, com limitações de advisories Canonical em aberto.**

- Worktree: `/tmp/kronos-keycloak-temurin-noble`
- Branch: `fix/keycloak-temurin-noble-prototype`
- Base: `a0431480dc1c26d45e90d997be7a80d34139aaac`
- Relatório detalhado: [`infra/keycloak/candidates/temurin-noble/README.md`](../../infra/keycloak/candidates/temurin-noble/README.md)

O candidato local `kronos-keycloak:temurin-noble-prototype` tem Image ID `sha256:00657612beaece60117887a6388c52c2227c115bca9cf45ae94527d00b5b9208`, Ubuntu 24.04.5 LTS/amd64, Keycloak oficial 26.8.1 por digest e Temurin JRE 21.0.13+9. O JRE tarball teve SHA-256 e assinatura GPG verificados, com SBOM CycloneDX oficial preservado. Guava 33.7.2-jre permanece com checksum.

Trivy 0.75.0, `HIGH,CRITICAL`, `ignore-unfixed=false`, sem suppressions, escaneou o artefato final: 92 pacotes Ubuntu e 518 dependências Java JAR, zero findings. O controle com pacote vulnerável oficial Noble `libbrotli1=1.1.0-2build2` foi detectado como CVE-2026-111497 HIGH, fix `1.1.0-2ubuntu0.1`. Depois do gate e da cobertura, importação e authorization-code OIDC sintético passaram em container standalone/loopback com TLS temporário.

Limitação que permanece: `libacl1` e os binários relevantes da família `util-linux` estão presentes. Noble continua com CVE-2026-54369 `Ignored — backport too intrusive` e CVE-2026-76642/78408/78409/78410 `Needs evaluation`. O scan sem findings não significa que Canonical confirmou esses componentes como corrigidos. O scanner também não cobre integralmente o JRE instalado por tarball; a análise específica do JRE é reportada separadamente.

Arquivos novos neste handoff:

- `infra/keycloak/candidates/temurin-noble/`: Dockerfile do candidato, controle de cobertura, fixture/script OIDC, SBOM/metadados Temurin, inventário e relatórios completos de scan.
- `docs/handoffs/KEYCLOAK-TEMURIN-NOBLE.md`.

Não houve mudança no Dockerfile principal, Compose, workflow, backend, runtime principal ou documentos compartilhados. Nenhum registry, deploy ou merge foi usado.
