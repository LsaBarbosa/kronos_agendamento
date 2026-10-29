# Protótipo Keycloak: Temurin 21.0.13+9 em Ubuntu Noble

## Resultado

**Gate Trivy: APROVADO.** A imagem final escaneada não tem findings HIGH/CRITICAL; o controle vulnerável Noble detectou CVE-2026-111497 com a versão esperada e a correção correspondente. O fluxo de inicialização, importação e login OIDC também passou em container descartável.

Isso é aprovação do gate automatizado executado neste protótipo, não aprovação para trocar o runtime principal. `libacl1` e binários de `util-linux` permanecem presentes, enquanto o tracker Ubuntu classifica CVE-2026-54369 como `Ignored — backport too intrusive` e os quatro CVEs de util-linux como `Needs evaluation`. Esses estados continuam pendentes e não são tratados como correção, embora o Trivy não os tenha reportado.

## Identidade e procedência do candidato

- Código base: `a0431480dc1c26d45e90d997be7a80d34139aaac`.
- Worktree/branch: `/tmp/kronos-keycloak-temurin-noble`, `fix/keycloak-temurin-noble-prototype`.
- Tag local: `kronos-keycloak:temurin-noble-prototype` (não publicada).
- Image ID final escaneado: `sha256:00657612beaece60117887a6388c52c2227c115bca9cf45ae94527d00b5b9208`; `linux/amd64`.
- OS observado dentro da imagem: Ubuntu 24.04.5 LTS, `ID=ubuntu`, `VERSION_ID=24.04`, `VERSION_CODENAME=noble`.
- Base: `ubuntu:24.04@sha256:fe81560990fae86dc4743202b2cb4d3df98d0bcf949173cecef15ce1127ef852`.
- Keycloak: distribuição oficial 26.8.1 copiada somente de `quay.io/keycloak/keycloak@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b`.
- Guava: `33.7.2-jre`, SHA-256 `b530942257fb935f8b2cfaa5f8eb5bd59c555fd8e8d01b8ce98912e077ea606c`; mantida no caminho de arquivo esperado pelo índice Quarkus do Keycloak 26.8.1.
- Processo final roda como UID `1000:0`. O estágio de verificação do JRE instala `ca-certificates`/`gnupg` apenas no estágio descartado; eles não são copiados ao runtime.
- O inventário Ubuntu completo de 92 pacotes observado pelo Trivy está em [`ubuntu-noble-package-inventory.tsv`](ubuntu-noble-package-inventory.tsv). O JSON Trivy inclui também seus PURLs e arquivos associados.

### Imagem oficial Temurin JRE Noble comparada

O manifesto oficial publicado durante a avaliação foi `eclipse-temurin:21.0.12.1_1-jre-noble@sha256:de07a7c89a9430922ee1db1e2f848d75533c7f8f2ab3e4ba5eb52cc4550cdb2a`; o container executou Temurin `21.0.12.1+1`, anterior à correção eMudhra. O rótulo/tag não foi usado como runtime final. A documentação Adoptium diz que JDK 21 `21.0.13+9` remove as raízes relacionadas ao comprometimento. [Release Temurin de outubro de 2026](https://adoptium.net/en-GB/news/2026/10/temurin-october-2026-emudhra), [imagem Docker oficial Temurin](https://hub.docker.com/_/eclipse-temurin).

### JRE usado no candidato

- Artefato oficial: `OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz`, release `adoptium/temurin21-binaries`, tag `jdk-21.0.13+9`.
- `java -version` no JRE extraído e no container final: `OpenJDK Runtime Environment Temurin-21.0.13+9 (build 21.0.13+9-LTS)`; OpenJDK `21.0.13`, data da build `2026-10-20`.
- SHA-256: `a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd`; valor confere com o arquivo SHA-256 publicado pelo Adoptium e é validado pelo Dockerfile.
- A assinatura detached `.sig` foi validada com GPG: `Good signature` da chave Adoptium, fingerprint `3B04 D753 C905 0D9A 5D34 3F39 843C 48A5 65F8 F04B`. O fingerprint coincide com a chave documentada pelo Adoptium; a confiança GPG local continua `unknown` (sem certificação de web-of-trust). O estágio de build repete verificação do checksum, fingerprint e assinatura.
- Arquivos preservados: [checksum](OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz.sha256.txt), [assinatura](OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz.sig), [chave pública](adoptium-release-key.asc), [metadados do release](temurin-21.0.13+9-release-metadata.json) e [SBOM CycloneDX oficial](temurin-21.0.13+9-sbom.cdx.json).
- O SBOM disponível é CycloneDX 1.6 do release Temurin, declara versão `21.0.13+9-LTS` e inclui componente JRE entre cinco componentes de framework. Ele também informa ambiente de build CentOS 7; isso não é o OS runtime. O SBOM está preservado como evidência fora da imagem final para não interferir no inventário/scanner do container.
- O Adoptium identifica JDK 21 `21.0.13+9` como correção do incidente eMudhra (JDK-8395690/CVE-2026-114820): a trust store usa dados NSS 3.131.1 e remove `emSign ECC Root CA - G3` e `AAA Certificate Services`; `emSign Root CA - G1` permanece por decisão Mozilla e não está entre as raízes comprometidas descritas. `keytool -list -cacerts -storepass changeit` no runtime final listou somente o alias G1 entre esses nomes; G3 e AAA não apareceram. Essa checagem de aliases não testa a validação de código assinado. [Adoptium: correção e instruções de trust store](https://adoptium.net/en-GB/news/2026/10/temurin-october-2026-emudhra), [OpenJDK advisory](https://openjdk.org/emudhra).

Trivy reportou zero findings Java e identificou 518 artefatos JAR do Keycloak. Isso é evidência sobre os JARs encontrados, não uma análise completa da implementação nativa do JRE instalado por tarball. A leitura do SBOM oficial pelo `trivy sbom` produziu apenas o envelope de metadados, sem resultados de pacotes; o comando também avisou que SBOM de terceiros pode levar a detecção imprecisa. Assim, a conclusão para o runtime Java baseia-se na versão observada, assinatura/procedência, SBOM publicado e checagem de trust store, não em alegar que o banco de pacotes Ubuntu ou Trivy cobriu integralmente todos os binários do JRE.

## CVEs da imagem Trixie reavaliadas contra os componentes efetivamente presentes

Versões abaixo vêm dos PURLs dpkg do relatório Trivy da imagem final. “Fixed” refere-se ao estado/versão publicado pelo tracker Canonical para Noble. Findings ausentes no scan não alteram os status `Ignored` ou `Needs evaluation`.

| CVE | Fonte / componente afetado | Pacotes binários afetados relevantes no candidato | Versão observada | Tracker Ubuntu Noble / leitura |
|---|---|---|---|---|
| CVE-2025-69720 | `ncurses`; parser de `infocmp` (`analyze_string`) | `libtinfo6`, `ncurses-base`, `ncurses-bin` | `6.4+20240113-1ubuntu2.2` | Fixed em `6.4+20240113-1ubuntu2.1`; versão instalada é posterior. [Tracker](https://ubuntu.com/security/CVE-2025-69720) |
| CVE-2026-16742 | `systemd`; componente relacionado a `systemd-homed` | `libsystemd0`, `libudev1` | `255.4-1ubuntu8.18` | Fixed em `255.4-1ubuntu8.17`; versão instalada é posterior. [Tracker](https://ubuntu.com/security/CVE-2026-16742) |
| CVE-2026-54369 | `acl`; funções pathname `acl_get_file`, `acl_set_file`, `acl_extended_file`, `acl_delete_def_file` | `libacl1` está presente | `2.3.2-1build1.1` | **Ignored — backport too intrusive**; não é correção. O scanner não reportou finding, mas o componente afetado está no runtime. [Tracker](https://ubuntu.com/security/CVE-2026-54369) |
| CVE-2026-76642 | `util-linux`; mount helper executa hooks privilegiados após falha | `bsdutils`, `libblkid1`, `libmount1`, `libsmartcols1`, `libuuid1`, `login`, `mount`, `util-linux` presentes; `liblastlog2-2` ausente | Família util-linux `2.39.3-9ubuntu6.6`; `login=1:4.13+dfsg1-4ubuntu3.2`; `bsdutils=1:2.39.3-9ubuntu6.6` | **Needs evaluation** para Noble. Presença dos demais binários não é resolvida pela ausência de `liblastlog2-2`. [Tracker](https://ubuntu.com/security/CVE-2026-76642) |
| CVE-2026-78408 | `util-linux`; `nsenter --join-cgroup` e descritor privilegiado `cgroup.procs` | Mesmos binários de `util-linux` presentes acima; `liblastlog2-2` ausente | Mesmas versões acima | **Needs evaluation** para Noble. [Tracker](https://ubuntu.com/security/CVE-2026-78408) |
| CVE-2026-78409 | `util-linux`; opção `X-mount.subdir` e resolução de symlink | Mesmos binários de `util-linux` presentes acima; `liblastlog2-2` ausente | Mesmas versões acima | **Needs evaluation** para Noble. [Tracker](https://ubuntu.com/security/CVE-2026-78409) |
| CVE-2026-78410 | `util-linux`; bind mounts restritos e opções `X-mount.owner/group/mode` | Mesmos binários de `util-linux` presentes acima; `liblastlog2-2` ausente | Mesmas versões acima | **Needs evaluation** para Noble. [Tracker](https://ubuntu.com/security/CVE-2026-78410) |
| CVE-2026-9538 | `perl`; módulo `Archive::Tar` e tamanho declarado em header TAR | `perl-base` está presente | `5.38.2-3.2ubuntu0.6` | Fixed em `5.38.2-3.2ubuntu0.4`; versão instalada é posterior. [Tracker](https://ubuntu.com/security/CVE-2026-9538) |

### CVE-2026-111497 (Brotli)

`libbrotli1` **não está instalado/distribuído** no candidato Ubuntu-base final. A imagem oficial Temurin JRE Noble comparada continha `libbrotli1=1.1.0-2ubuntu0.1`, correção publicada pelo Ubuntu para Noble. [Tracker Canonical](https://ubuntu.com/security/CVE-2026-111497), [USN-8956-1](https://ubuntu.com/security/notices/USN-8956-1).

O controle de cobertura usa exclusivamente o pacote oficial Noble vulnerável `libbrotli1=1.1.0-2build2` de `noble/main`; Trivy o reportou como HIGH, fix `1.1.0-2ubuntu0.1`. Esse controle testa a fonte Ubuntu correspondente, sem suppressions ou alteração de severidade.

O Keycloak upstream contém também o JAR Maven `com.aayushatharva.brotli4j:1.23.0`; ele foi examinado como dependência Java entre os 518 JARs. O relatório Trivy não o identificou com CVE HIGH/CRITICAL. A presença do JAR não equivale ao pacote Ubuntu `libbrotli1`; o possível conteúdo nativo empacotado não foi validado por uma prova funcional contra a vulnerabilidade Brotli.

## Trivy 0.75.0: scan e cobertura

Imagem do scanner: `aquasec/trivy:0.75.0`, local image ID `sha256:6d93f1ec925b37c5ce785e416b86cba056e10c8c2914a59a8ea7be18115d6764`.

Comando efetivamente executado no arquivo Docker exportado da imagem final:

```sh
trivy image --input candidate.tar --scanners vuln \
  --severity HIGH,CRITICAL --ignore-unfixed=false \
  --format json --output trivy-candidate.json
```

- Banco vulnerabilidades: `UpdatedAt=2026-10-29T13:09:46Z`, `DownloadedAt=2026-10-29T15:28:57Z`.
- Java DB: `UpdatedAt=2026-10-29T01:08:14Z`, `DownloadedAt=2026-10-29T15:29:57Z`.
- Scan da imagem final: Trivy detectou Ubuntu 24.04, analisou 92 pacotes `dpkg` e 518 dependências Java JAR; `0 HIGH`, `0 CRITICAL`, `0 CVEs` no JSON completo [`trivy-candidate.json`](trivy-candidate.json). Não havia arquivo de suppressions configurado ou passado ao comando.
- Cobertura: o [`Dockerfile.scan-coverage-control`](Dockerfile.scan-coverage-control) constrói somente um controle descartável com `libbrotli1=1.1.0-2build2` do repositório oficial `noble/main`. Trivy 0.75.0 identificou `CVE-2026-111497`, severidade HIGH, correção `1.1.0-2ubuntu0.1`. Relatório completo: [`trivy-ubuntu-coverage-control.json`](trivy-ubuntu-coverage-control.json).
- Os metadados completos dos DBs ficam em [`trivy-db-metadata.json`](trivy-db-metadata.json) e [`trivy-java-db-metadata.json`](trivy-java-db-metadata.json).
- Resultado do comando Trivy `sbom` sobre o SBOM Temurin: [`trivy-temurin-sbom.json`](trivy-temurin-sbom.json). Ele contém envelope CycloneDX sem pacote/finding; não é usado como cobertura do tarball do JRE.

## Inicialização, importação e login OIDC

Executado somente após o scan final e a prova de cobertura:

```sh
bash infra/keycloak/candidates/temurin-noble/run-oidc-smoke.sh
```

O script inicia um container standalone `--rm`, publica só uma porta efêmera em `127.0.0.1`, importa [`temurin-smoke-realm.json`](realm-import/temurin-smoke-realm.json), usa usuário/senhas sintéticos, emite certificado TLS autoassinado efêmero e o remove na limpeza. O cliente Python aceita esse certificado somente para a URL local de teste, porque a finalidade é validar o protocolo OIDC, não a confiança TLS do cliente. O teste passou: issuer validado, authorization code, `state`, `nonce`, token exchange e `/userinfo` confirmaram `synthetic-user`. Não foi usado Compose, banco compartilhado, serviço externo nem credencial real.

## Limitações e decisão

1. A aprovação do gate Trivy é limitada às fontes e inventários que Trivy identificou. Os CVEs `acl`/`util-linux` continuam com status Canonical sem resolução demonstrada; o relatório de scan zero não os converte em corrigidos.
2. O Trivy reconheceu 518 JARs de Keycloak, mas não cobre automaticamente o JRE distribuído como tarball. A checagem específica do JRE usa release oficial, hash, assinatura, SBOM e trust store; não substitui uma auditoria binária independente.
3. A imagem oficial Temurin `21.0.13+9-jre-noble` ainda não estava publicada durante a verificação; o candidato usa o binário oficial Temurin assinado sobre a base oficial Ubuntu Noble, conforme alternativa autorizada.
4. O teste OIDC é local e sintético, sem CI, E2E da aplicação consumidora, multi-arquitetura ou produção.
5. A referência do SBOM e dos verificadores é o artefato JRE x64 Linux usado nesta imagem amd64; outras arquiteturas não foram construídas nem escaneadas.

O candidato passa o critério automatizado Trivy com cobertura demonstrada e completou o smoke OIDC isolado. A decisão de runtime principal continua com o Terminal 1/revisão: os status pendentes de `acl` e `util-linux` devem permanecer visíveis.
