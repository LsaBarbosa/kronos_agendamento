# Handoff — Temurin 21.0.13+9 em Keycloak e backend

## Escopo e proveniência

- Branch/PR: `fix/keycloak-image-security`, PR #27, draft. Head de entrada `fdc5de13f46487c1b01b90208a2293e564b3767f`.
- Integração TLS autorizada: somente os arquivos do handoff `fix/keycloak-temurin-noble-tls-validation` em `6d703a4893cc4da50f37c21bfcf5211abab9b780`; probe e runner foram movidos para a área compartilhada `infra/temurin/21.0.13+9/tls-validation/`. O protótipo Keycloak já integrado não foi reintegrado.
- Runtime comum: Temurin JRE x64 `21.0.13+9`, release Adoptium oficial `OpenJDK21U-jre_x64_linux_hotspot_21.0.13_9.tar.gz`, SHA-256 `a535a1df9c6284d9618872984e026ae0352d1f7e9e0605425c0e6114f47351dd`, assinatura detached válida e fingerprint primário `3B04D753C9050D9A5D343F39843C48A565F8F04B`. O aviso de GPG `[unknown]` indica ownertrust local não certificado; a assinatura verificou criptograficamente e o fingerprint foi conferido contra o publicado pelo Adoptium.
- `infra/temurin/21.0.13+9/release.env` é a fonte compartilhada da versão, URL e fingerprint. Ambos os Dockerfiles usam o mesmo verificador/checksum/assinatura; a versão Java e o checksum não são definidos separadamente para cada runtime.

## Artefatos efetivamente testados

| Uso | Image ID | Plataforma / usuário | Runtime e trust store |
|---|---|---|---|
| Keycloak original já integrado, sem rebuild | `sha256:31296d4266bf8f893bc1e6535d54b1e1fb01cd0eab3f371701a918640dd92775` | `linux/amd64`, `1000:0` | Temurin `21.0.13+9-LTS`, `JAVA_HOME=/opt/java/openjdk`; trust store JSSE padrão `/opt/java/openjdk/lib/security/cacerts` |
| Keycloak reconstruído após compartilhar o verificador | `sha256:81c5d8f739667bd681acd8f343fa43a80f01f5647b63da25e55b046f2e48f965` | `linux/amd64`, `1000:0` | Mesmos valores; Keycloak oficial 26.8.1 por digest `a78d6b…`, base Ubuntu 24.04 por digest `fe815609…` |
| Backend de produção, sem fixtures E2E | `sha256:bba1a5cdaace875bc374c660be6f1fc57578621a8d7c0327d8891ad242f17e84` | `linux/amd64`, `10001:10001` | Temurin `21.0.13+9-LTS`, `JAVA_HOME=/opt/java/openjdk`; trust store JSSE padrão no JRE |
| Backend E2E, fixtures sintéticas restritas ao build de teste | `sha256:04b744be25f5f3d77d0481c9336e0f87eff410b3245211ed397513880aef8889` | `linux/amd64`, `10001:10001` | Mesmo runtime/trust store; este ID exato foi escaneado e usado pelo backend no navegador |

Dentro do backend foram observados Java `21.0.13`, runtime `21.0.13+9-LTS`, fornecedor Eclipse Adoptium, `java.home=/opt/java/openjdk`, `JAVA_HOME=/opt/java/openjdk`, `ca-certificates=20260816ubuntu0.24.04.1` e `libbrotli1=1.1.0-2ubuntu0.1`. A versão Brotli agora é fixada e conferida no Dockerfile. O app continua `java -jar /app/app.jar`, com usuário 10001:10001. A imagem backend não copia conteúdo específico de Keycloak.

## Validação TLS

`TlsTrustStoreProbe` foi compilado localmente com `javac --release 21` e executado pelo Java do Image ID informado. O runner confere o digest, `Config.User`, `JAVA_HOME` e identidade efetiva, e não usa `--user` para elevar privilégios. O container de prova é descartável, somente leitura, sem capabilities, com o probe montado somente leitura. A configuração JSSE não define trust store alternativa e não há `jssecacerts`; foi inicializado um trust manager pela configuração padrão.

Executado com sucesso nos quatro IDs listados acima:

- Raiz `emSign Root CA - G1` presente; `emSign ECC Root CA - G3` e `AAA Certificate Services` ausentes.
- As 14 cadeias comprometidas do checker eMudhra foram rejeitadas em validação PKIX.
- Os controles HTTPS `probe-emudhra.we-have-a-problem.com` e `adoptium.net` retornaram HTTP 200.
- Na primeira tentativa contra o Keycloak reconstruído houve resolução DNS transitória. O probe agora faz resolução limitada/repetível, sem ignorar falha persistente; a execução final concluiu todos os casos. Nenhum endpoint foi removido e nenhuma asserção TLS foi enfraquecida.

Isso comprova comportamento TLS JSSE desses runtimes nos endpoints testados, não tratamento de artefatos assinados, trust stores próprias de bibliotecas, nem avaliação de todos os cinco advisories Canonical acl/util-linux. Não se declara esses advisories corrigidos ou não aplicáveis.

## Scan Trivy

Trivy `0.75.0`, base de vulnerabilidades `UpdatedAt=2026-10-29T13:09:46Z`, `NextUpdate=2026-10-30T13:09:46Z`; Java DB `UpdatedAt=2026-10-28T01:07:04Z`. Os scans locais ocorreram em 30/10 entre 12:16Z e 12:37Z, dentro da validade informada. Política mantida: `--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`.

- Keycloak reconstruído `81c5d8…`: alvo Ubuntu 24.04, 94 pacotes de SO e 518 entradas JAR; zero findings HIGH/CRITICAL. Relatório `/tmp/kronos-trivy-keycloak-final-report/sha256_81c5d8f739667bd681acd8f343fa43a80f01f5647b63da25e55b046f2e48f965.json`.
- Backend de produção `bba1a5…`: alvo Ubuntu 24.04, 109 pacotes de SO e 106 entradas JAR; zero findings HIGH/CRITICAL. Relatório `/tmp/kronos-backend-trivy-report/backend-prod-final.json`.
- Backend E2E `04b744…`: mesmos alvos/contagens, zero findings HIGH/CRITICAL. Relatório `/tmp/kronos-backend-trivy-report/backend-e2e-final.json`.

Trivy zero não resolve por si só os cinco advisories Canonical sob avaliação separada pelo Terminal 2. Nenhum ignore, suppression, redução de severidade ou alteração de política foi feita.

## E2E OIDC e readiness

Comando: `bash /tmp/kc-temurin-final-artifacts-e2e.sh`. O script usou projeto Compose exclusivo `agd-kc-noble-e2e-1793363872-2443714`, portas selecionadas e verificadas como livres, fixtures/segredos sintéticos descartáveis e `docker compose up --wait --wait-timeout 240 -d`. Para manter identidade dos artefatos, o E2E não reconstruiu imagens. Os containers reportaram exatamente Keycloak `sha256:81c5d8…` e backend `sha256:04b744…`, ambos correspondentes aos relatórios Trivy. Os dois serviços ficaram healthy; o backend executou como UID:GID 10001:10001.

`IntegratedBrowserE2E=passed`: importação/configuração do realm, provisionamento sintético, login OIDC, vínculo administrativo e negação de usuário não associado, sessão JDBC, expiração/revogação, CSRF/logout, respostas privadas atrasadas e os demais fluxos do roteiro browser. Nenhum checkout/cobrança ou envio real ocorreu. Recursos do projeto descartável foram removidos pelo nome do projeto; Compose compartilhado e dados reais não foram usados.

Uma tentativa anterior do roteiro encontrou porta frontend ocupada: Vite fez fallback para outra porta, portanto essa tentativa não foi contabilizada. A execução final adicionou `--strictPort` ao script temporário fora do repositório e usou portas livres; o roteiro versionado permaneceu intacto. Uma execução instrumentada anterior também passou; a causa do primeiro timeout no cenário de checkout não foi determinada, então não se atribui causa a runtime ou infraestrutura.

## Repetição e pendências

Na raiz do repositório, build do Keycloak: `scripts/dev/build-keycloak-image.sh kronos-keycloak:26.8.1-temurin-noble`; build backend com contexto compartilhado: `docker build --build-context temurin=../infra/temurin --iidfile <arquivo-id> -t <tag> .` a partir de `backend/`. Use os scripts versionados em `infra/temurin/21.0.13+9/tls-validation/` com o Image ID imutável e usuário esperado. Escaneie cada ID com a política acima e só execute E2E numa composição própria com as mesmas imagens já escaneadas.

Validações locais funcionais/TLS e os scans dos artefatos descritos passaram. A revisão foi sequencial; nenhum parecer de agente ou resultado de CI equivale a aprovação humana. Os cinco advisories Canonical permanecem sob avaliação do Terminal 2; sem aceitação de risco, merge, deploy, publicação em registry ou propagação para outros PRs. A validação não substitui os checks remotos que serão associados ao novo head.
