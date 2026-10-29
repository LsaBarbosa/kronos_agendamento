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

## Correção completa em avaliação — 28/10/2026

### Artefato

- Worktree/branch: `/tmp/kronos-keycloak-security`, `fix/keycloak-image-security`.
- Base de trabalho: head do PR #27 `7b97abaff18b498c1190db0f8f62880d4b809a4b`; PR segue draft.
- Fonte do produto: imagem oficial Keycloak `26.8.1`, Quay digest `sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b`. O Dockerfile copia apenas `/opt/keycloak` dessa imagem.
- Runtime final: `debian:forky-slim@sha256:5533be417c8ed39dbf2ac33e1fdbf08ccfa5a58e62d84238999ff98ab3357068`, Debian Forky/testing, snapshot `20261028T183000Z`, alvo validado `linux/amd64`.
- Pacotes relevantes instalados no alvo: `openjdk-21-jre-headless=21.0.13+9-1`, `ca-certificates=20260816+nmu1`; metadados completos de `dpkg-query` e digests de origem ficam em `/usr/share/keycloak-image/BUILD-METADATA.txt` na imagem.
- Guava: conteúdo upstream `33.7.2-jre`, SHA-256 `b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c`; o nome do JAR original é mantido porque o índice Quarkus do Keycloak 26.8.1 referencia esse caminho. O metadata Maven do artefato substituído permanece disponível ao scanner.
- ID local do artefato final: `sha256:0d7265b3a5e9644b4fceb84c684cc05ab20496d2be9e248cd9670153e6ad1a09`. Usuário `1000:0`, diretório `/opt/keycloak` e entrypoint upstream `/opt/keycloak/bin/kc.sh`.

Debian Forky é testing, não Debian stable. A solução elimina os findings bloqueadores observados no scan completo, mas assume uma base com maior frequência de mudanças e menor garantia operacional que Trixie. O snapshot e os pins tornam a construção repetível; não tornam Forky uma baseline estável nem comprovam prontidão de produção. Apenas amd64 foi construída, escaneada e exercitada nesta rodada; outras arquiteturas do manifesto upstream não foram validadas.

Fontes oficiais reavaliadas nesta rodada: o [índice de releases Debian](https://www.debian.org/releases/) identifica Trixie como stable e Forky como testing; as [notas de Forky](https://www.debian.org/releases/testing/) alertam que a equipe de segurança ainda não gerencia atualizações de testing em tempo hábil. A [documentação de containers Keycloak](https://www.keycloak.org/server/containers) descreve a customização da imagem e a cópia de `/opt/keycloak`; a [matriz de configurações suportadas](https://www.keycloak.org/server/supported-configurations) inclui OpenJDK 21. A conclusão de compatibilidade deste projeto continua limitada ao E2E descartável; usar Java suportado não transforma Debian testing em plataforma operacionalmente suportada.

### Procedimento reproduzível

Em checkout isolado, com Docker disponível e sem usar o Compose compartilhado:

```bash
KEYCLOAK_PLATFORM=linux/amd64 scripts/dev/build-keycloak-image.sh kronos-keycloak:26.8.1-custom
TRIVY_CACHE_DIR=/tmp/kronos-trivy-keycloak-cache scripts/dev/scan-keycloak-image.sh kronos-keycloak:26.8.1-custom
```

O scan executa Trivy `0.75.0` com `--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; não há suppressions nem mudança de gate. Para atualizar: verificar primeiro os advisories oficiais, a disponibilidade e o pacote instalado na arquitetura alvo; atualizar digest/snapshot/versão com checksums; construir uma vez; inspecionar e escanear o ID resultante; então usar esse mesmo artefato no Compose e no E2E. Não publicar a imagem em registry externo nesta tarefa.

No CI, o job `keycloak-image` constrói uma única imagem `linux/amd64`, exporta archive, SHA-256 e Docker Image ID. Os jobs `security` e `oidc-browser-e2e` baixam o mesmo artifact, verificam o checksum do archive e o Image ID após `docker load`; o primeiro escaneia e o segundo executa esse ID. O Compose recebe a tag do commit. Não são duas construções independentes.

### Evidências desta rodada

- Trivy `0.75.0`, banco de vulnerabilidades `UpdatedAt=2026-10-28T13:01:10Z`, `DownloadedAt=2026-10-28T18:26:47Z`; Java DB `UpdatedAt=2026-10-26T00:59:12Z`, dentro da validade informada pelo próprio metadata. JSON de scan em `/tmp/kc-trivy-report/forky-final.json`: dois targets (OS Debian e JAR), zero findings HIGH/CRITICAL. A identidade inspecionada foi o ID local acima.
- E2E Chromium/PostgreSQL/Keycloak passou no projeto descartável `kronos-kc-oidc-final-20261028-2056`, usando o mesmo image ID. O realm e os usuários sintéticos foram preparados com a imagem oficial e o container Keycloak foi substituído pelo runtime derivado sobre o banco Keycloak descartável já populado; realm existente permaneceu, usuário foi consultável e o fluxo de login funcionou. O roteiro também cobriu vínculo tenant/admin, negação sem associação, sessão JDBC, revogação, CSRF/logout e respostas privadas atrasadas.
- Dois projetos Docker criados pela validação, `kronos-kc-oidc-20261028-1849-a7d2` e `kronos-kc-oidc-final-20261028-2056`, foram removidos com seus containers, redes e volumes. O projeto compartilhado `agendamento` permaneceu ativo e não foi alterado.
- Iterações iniciais encontraram duas incompatibilidades reais e foram corrigidas antes da evidência final: o índice Quarkus exigia manter o nome original do JAR Guava; `kcadm` exigia `HOME=/opt/keycloak`. Uma tentativa intermediária do roteiro também mostrou que o projeto Compose e as URLs de app precisavam ser parametrizados; o roteiro foi ajustado e a execução final isolada passou.
- CI do head `e1577117925f19e9f1d65d3261384a7d70d638e6`: `keycloak-image`, `security`, frontend, secret scans e CodeQL push/PR passaram; o scan remoto analisou o artefato compartilhado. App CI PR OIDC E2E passou integralmente. App CI PR `verify` falhou em `PublicAppointmentIT.absentPolicyAllowsUnrestrictedPublicBookingButDoesNotReleaseExistingRestriction`: o registro recebeu `created_at=2026-10-29T00:59:04Z` e `expires_at=2026-10-27T18:00:01Z`, violando `ck_public_booking_intent_expiry` (`expires_at > created_at`). App CI push teve a mesma falha de `verify`; seu OIDC E2E falhou mais tarde aguardando o texto de sessão encerrada, sem causa adicional demonstrada. Portanto, scan Keycloak e compatibilidade OIDC no evento PR estão aprovados, mas o CI agregado não está verde. Esse defeito dos testes/backend não foi alterado nesta frente de infraestrutura.

### Limites

A atualização troca o runtime Red Hat/Ubi vulnerável por Debian Forky e atualiza Guava dentro da distribuição do Keycloak. Esse trade-off precisa de revisão de manutenção e suporte antes de qualquer adoção em produção. Não houve homologação hospedada, validação multi-arquitetura, upgrade de ambiente real ou integração de fornecedor. Não há aprovação humana registrada. O gate desta correção só será considerado aprovado após App CI executar Trivy e E2E no head publicado e todos os jobs/check-runs concluírem.

## Correção P1 da cobertura Debian — Trixie stable (29/10/2026)

A revisão demonstrou que o scan anterior de Forky não comprovava cobertura: Trivy identificava `forky/sid`, mas a fonte Debian organiza certos advisories de Forky sob versão numérica 14. O resultado anterior de zero findings HIGH/CRITICAL está invalidado como evidência de cobertura e não é aprovação de segurança.

### Base e inventário

- A worktree permanece `/tmp/kronos-keycloak-security`, branch `fix/keycloak-image-security`, sobre PR #27 draft. Head remoto confirmado antes da alteração: `08eada765b3209d3acf3d0684ac7d343fb08af02`.
- Produto: Keycloak oficial 26.8.1 `quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b`; somente `/opt/keycloak` é copiado.
- Runtime: `debian:trixie-slim@sha256:f6b08b09a05725f9431502d3b5714f5c4576e82c1a84fa1debe0c24a7e0c3ec4`, `linux/amd64`, OS observado `Debian GNU/Linux 13 (trixie)`, imagem local `sha256:c67df194054922f1b4b220b01ca3c8917d9919d4ca80d216da670af3dd1c07fe`.
- Apt é exclusivamente Trixie: snapshot main e `trixie-security` `20261028T183000Z`. O inventário está preservado em `/usr/share/keycloak-image/BUILD-METADATA.txt` e no relatório completo `/tmp/kronos-trivy-trixie-report/kronos-keycloak_26.8.1-trixie-candidate.json`.
- Pacotes centrais observados: `openjdk-21-jre-headless=21.0.13+9-1~deb13u2`, `ca-certificates=20250419+deb13u1`, `libc6=2.41-12+deb13u4`, `util-linux=2.41.5-0+deb13u1`, `libacl1=2.3.2-2+b1`, `systemd`/`libsystemd0=257.13-1~deb13u1`, `ncurses=6.5+20250216-2`, `perl-base=5.40.1-6+deb13u1`. Guava permanece `33.7.2-jre` com SHA-256 registrado no Dockerfile; a procedência oficial do Keycloak e o caminho do JAR esperado pelo índice Quarkus são preservados.
- Trivy é `0.75.0`; política mantida: `vuln`, `HIGH,CRITICAL`, `ignore-unfixed=false`, exit code 1. Database `UpdatedAt=2026-10-28T19:18:03Z`, baixado `2026-10-29T01:34:43Z`; Java DB `UpdatedAt=2026-10-28T01:07:04Z`, baixado `2026-10-29T01:36:16Z`.

### Cobertura e findings

`scripts/dev/scan-keycloak-image.sh` valida antes do resultado que `/etc/os-release` declara `ID=debian`, `VERSION_ID=13`, `VERSION_CODENAME=trixie`, confirma a digest da base, o pacote OpenJDK corrigido e componentes do inventário. Salva JSON completo, afirma que o target de pacotes é `debian 13`, imprime contagens e preserva o exit status do gate.

`scripts/dev/verify-keycloak-scan-coverage.sh` constrói controle descartável Debian 13 com `openjdk-21-jre-headless=21.0.12.1+1-1~deb13u1` do snapshot main (deliberadamente vulnerável), roda o mesmo Trivy e exige target `debian 13` e CVE-2026-91415 associada ao pacote/versão vulneráveis. O controle local encontrou o CVE; o runtime final instala `21.0.13+9-1~deb13u2` e esse CVE não aparece no relatório final. O script gera tag temporária única, recusa sobrepor imagem preexistente e remove somente o controle que construiu.

O scan do artefato final identificou **43 ocorrências HIGH, 0 CRITICAL, 8 CVEs únicas**: CVE-2025-69720 (ncurses), CVE-2026-16742 (systemd), CVE-2026-54369 (acl), CVE-2026-76642/CVE-2026-78408/CVE-2026-78409/CVE-2026-78410 (util-linux e binários/bibliotecas derivados), e CVE-2026-9538 (perl-base). CVE-2026-114406, CVE-2026-91403 e CVE-2026-91415 não constam nesse relatório. A execução de `scan-keycloak-image.sh` retornou 1 conforme gate, apesar de gravar/validar target e inventário.

Na consulta dos advisories Debian em 29/10/2026, Trixie permanece vulnerável para essas oito CVEs; os trackers indicam correções apenas em Forky/unstable para os casos corrigidos lá, e `no-dsa`/`postponed` para parte dos itens. Portanto não há atualização Trixie oficial demonstrada que zere os findings. Trivy seleciona severidade HIGH de outras fontes para o gate; as classificações Debian não são usadas para suprimir ou reduzir o critério. Não se misturam suites nem se removem componentes/metadados. Fontes: [util-linux/CVE-2026-78408](https://security-tracker.debian.org/tracker/CVE-2026-78408), [acl/CVE-2026-54369](https://security-tracker.debian.org/tracker/CVE-2026-54369), [ncurses/CVE-2025-69720](https://security-tracker.debian.org/tracker/CVE-2025-69720), [systemd/CVE-2026-16742](https://security-tracker.debian.org/tracker/CVE-2026-16742), [perl/CVE-2026-9538](https://security-tracker.debian.org/tracker/CVE-2026-9538), [OpenJDK/CVE-2026-91415](https://security-tracker.debian.org/tracker/CVE-2026-91415) e [OpenJDK/CVE-2026-91403](https://security-tracker.debian.org/tracker/CVE-2026-91403).

### Compatibilidade executada e limite

O projeto descartável `agd-kc-trixie-20261029-0230` primeiro iniciou a imagem oficial, importou o realm e provisionou usuários; contagem PostgreSQL antes da troca: 2 realms, 14 clients e 3 users. A imagem candidata, exatamente o ID escaneado acima, foi iniciada sobre o mesmo volume descartável; permaneceu healthy, realm OIDC retornou issuer e backend readiness `UP`. O navegador Chrome concluiu login OIDC, associação administrativa e dezenas de fluxos até o cenário sintético de checkout. A suíte inteira **não concluiu**: em `test-keycloak-browser.mjs:3018`, aguardava `Resultado ainda incerto.`; nenhuma operação de checkout foi persistida no banco isolado. Isso não prova falha do Keycloak nem autoriza atribuir a causa à infraestrutura; compatibilidade OIDC/login/importação e preservação do DB foram observadas, mas o E2E completo permanece pendente. Os únicos recursos removidos foram os containers, rede e volumes do projeto `agd-kc-trixie-20261029-0230`; o Compose compartilhado continuou ativo.

CI no head final, E2E completo, review independente e checks/check-runs serão registrados após publicação. PR #27 continua draft. Nenhuma mudança em backend, migrations, outras branches ou PRs funcionais; sem merge, deploy, registro externo ou aceitação de risco.
