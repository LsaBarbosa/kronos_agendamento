# Mitigação de privilégios e escopo das advisories `acl`/`util-linux`

## Escopo e referência histórica

O handoff autorizado em `KEYCLOAK-ACL-UTIL-LINUX-APPLICABILITY.md` foi incorporado sem alteração a partir do commit `006b5f0dce9cb80fa4a80b6a28f39e9fec054fb4`. Ele avalia somente o artefato anterior, Keycloak Image ID `sha256:31296d4266bf8f893bc1e6535d54b1e1fb01cd0eab3f371701a918640dd92775`, com `mount` SUID e sem NNP/capability drop no Compose. Nenhuma observação daquele artefato é apresentada como teste automático das imagens seguintes.

Esta rodada reconstrói e inspeciona separadamente os novos artefatos. A tabela distingue estado do pacote, presença da condição de imagem e mitigação de execução. O zero no scan Trivy não altera o status da Canonical e não constitui decisão de risco.

## Artefatos e controles medidos

| Imagem final | Image ID local | Identidade | `mount`/`umount` | Trivy 0.75.0 |
|---|---|---|---|---|
| Keycloak 26.8.1 hardened | `sha256:ea9bbfabf473a44e0eae167633b4d6fd4fc03c15d24558541ce0eabf57dade7e` | `1000:0`, Temurin `21.0.13+9-LTS`, `/opt/java/openjdk` | executáveis preservados, ambos modo `755` | 94 pacotes Ubuntu + 518 JAR; zero HIGH/CRITICAL |
| Backend produção hardened | `sha256:225d5e7a446d5fed8f3b70a40bce0c0a039e19212b6f71c5ec90e206f874b211` | `10001:10001`, Temurin `21.0.13+9-LTS`, `/opt/java/openjdk` | executáveis preservados, ambos modo `755` | 109 pacotes Ubuntu + 106 JAR; zero HIGH/CRITICAL |
| Backend E2E hardened | `sha256:b5320e055636d34c5ec4bd93729f5991b8f90a01db3b1cb4a9d5a1c3d1cad54b` | `10001:10001`, Temurin `21.0.13+9-LTS`, `/opt/java/openjdk` | executáveis preservados, ambos modo `755` | 109 pacotes Ubuntu + 106 JAR; zero HIGH/CRITICAL |

Os três artefatos mantêm `libacl1=2.3.2-1build1.1`, `util-linux=2.39.3-9ubuntu6.6`, `mount=2.39.3-9ubuntu6.6` e `libmount1=2.39.3-9ubuntu6.6`; os pacotes não foram removidos nem atualizados nesta mitigação. A fonte Keycloak segue sendo a imagem oficial 26.8.1 por digest `a78d6b…`; base Ubuntu Noble por digest `fe815609…`; Temurin e Guava preservam checksums/procedência previamente verificados. Java e trust store não mudaram, portanto não foi repetida a bateria TLS.

O build remove somente o bit SUID de `/usr/bin/mount` e `/usr/bin/umount` nos estágios finais e falha se persistir SUID. Não remove binários, bibliotecas ou metadados. Os estágios de build permanecem sem as restrições do runtime.

Compose efetivo foi validado para desenvolvimento (`compose.yaml` + `compose.keycloak.yaml`) e para E2E (mais `compose.identity.yaml` + `compose.e2e.yaml`, profile `app`). Keycloak e backend têm `security_opt: [no-new-privileges:true]` e `cap_drop: [ALL]`; ambos não têm `cap_add` nem `privileged`. PostgreSQL da aplicação e do Keycloak não recebem essas restrições. No PID 1 dos containers E2E, `/proc/1/status` mostrou `NoNewPrivs=1`, `CapEff=0000000000000000`, `CapBnd=0000000000000000`; os usuários foram os esperados. Essas garantias dependem do deployment manter configuração equivalente; não decorrem apenas do artefato.

Trivy foi executado como `aquasec/trivy:0.75.0`, `--scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`, com DB OS `UpdatedAt=2026-10-30T07:16:25Z` e Java DB `UpdatedAt=2026-10-30T00:58:38Z` (download local em 13:32–13:33Z). As execuções retornaram zero para os artefatos listados. Resultado funcional do scanner não fecha advisory Canonical.

## Matriz atualizada, sem inferir correção do pacote

| CVE | Pacote/estado oficial registrado no handoff | Condição no novo artefato | Efeito/limite da mitigação | Conclusão desta rodada |
|---|---|---|---|---|
| CVE-2026-54369 | `libacl1` continua presente na versão anterior; Canonical `Ignored` | APIs pathname e biblioteca continuam presentes; não foi demonstrada ausência de consumidor privilegiado em todos os usos | `no-new-privileges` e capabilities vazias reduzem obtenção de privilégio pelo processo do serviço, mas não corrigem as APIs nem provam ausência de qualquer caminho privilegiado futuro | **Pacote não corrigido; aplicabilidade residual inconclusiva.** Não afirmar que controles runtime corrigem a biblioteca. |
| CVE-2026-76642 | `mount`/`libmount1` continuam `2.39.3-9ubuntu6.6`; Canonical `Needs evaluation` | `/usr/bin/mount` existe sem SUID; configuração fstab continua sem entradas autorizadas no snapshot | A remoção do SUID elimina a elevação local via esse executável; NNP e `cap_drop: ALL` bloqueiam elevação e capabilities no container testado. Não cobre deployment que reintroduza permissões ou capacidades | **Pacote não corrigido; vetor SUID mitigado nos artefatos/Compose testados.** |
| CVE-2026-78408 | `util-linux` continua `2.39.3-9ubuntu6.6`; Canonical `Needs evaluation` | `/usr/bin/nsenter` foi conferido nas três imagens novas em modo `755`; serviços executam com `CapBnd=0` e sem capabilities | Ausência de capabilities limita o vetor privilegiado observado. Não altera o status do pacote nem cobre ferramentas/configurações host externas ao container | **Pacote não corrigido; autoridade de execução do serviço reduzida; aplicabilidade geral ainda pendente.** |
| CVE-2026-78409 | `mount`/`libmount1` continuam na mesma versão; Canonical `Needs evaluation` | `mount` e `umount` presentes, modo `755`; NNP=1 e `CapBnd=0`; fstab não configurado | Remove caminho de elevação SUID pelo binário e capability boundary do workload. Kernel/fstab/overrides futuros precisam permanecer sujeitos ao mesmo controle | **Pacote não corrigido; vetor de privilégio mitigado no Compose testado, pré-condições ambientais não generalizadas.** |
| CVE-2026-78410 | `mount`/`libmount1` continuam na mesma versão; Canonical `Needs evaluation` | `mount`/`umount` sem SUID; nenhuma regra autorizada no fstab observado | Mitiga o caminho de mount privilegiado na execução testada. Não prova ausência do bug no código nem protege contra configuração diferente | **Pacote não corrigido; vetor de elevação mitigado; aplicabilidade fora da configuração medida permanece inconclusiva.** |

O status `Ignored`/`Needs evaluation`, ausência de finding Trivy, ausência de uma pré-condição no snapshot e mitigação do vetor são fatos distintos. Os cinco advisory records continuam abertos para decisão do Terminal 2; não houve aceitação de risco.

## E2E e limites

O smoke de importação inicial com banco vazio também foi executado sob os controles no projeto `kronos-kc-import-1793382752-57728`: Keycloak hardened desde o primeiro boot ficou healthy, importou realm, autenticou admin local, e um client OIDC e usuário sintéticos foram criados e consultados; o processo mostrou NNP=1 e CapEff/CapBnd=0. `HardenedRealmImport=passed`. O E2E integrado usou os mesmos Image IDs escaneados, no projeto descartável `kronos-hardening-1793373735-40537`. Antes da troca, realm `agendamento`, client `agendamento-backend` e usuário `hardening-preserved-user` foram consultados como `1:1:1`; após recriar somente Keycloak no mesmo volume PostgreSQL, as contagens permaneceram `1:1:1`. Keycloak importou/configurou realm, login OIDC, sessão e associação administrativa; backend e os dois PostgreSQL ficaram healthy. Dentro dos processos foram verificados os usuários, `NoNewPrivs=1`, `CapEff=0`, `CapBnd=0`, `mount=755` e `umount=755`. PostgreSQL não recebeu as restrições. O Chrome executou `scripts/dev/test-keycloak-browser.mjs` até `Browser E2E passed`, cobrindo OIDC, autorização/negação, sessão JDBC, revogação, CSRF/logout, respostas atrasadas e histórias sintéticas integradas. `IntegratedBrowserE2E=passed`. Houve tentativas locais não conclusivas por helpers não executáveis, dependências frontend ausentes, nome do projeto não propagado ao Node e segredo sintético do webhook não passado ao processo Node; após corrigir somente a invocação temporária, a execução completa passou. Não foi observado defeito de produto nesses erros do harness.

Comandos de validação dos artefatos: `scripts/dev/build-keycloak-image.sh kronos-keycloak:26.8.1-hardened`; no diretório `backend`, `docker build --build-context temurin=../infra/temurin -t agendamento-backend:hardening-prod .` e o mesmo comando com `--build-arg INCLUDE_E2E_FIXTURES=true -t agendamento-backend:hardening-e2e`. Keycloak foi escaneado com `TRIVY_CACHE_DIR=/tmp/kronos-trivy-keycloak-hardening-cache TRIVY_REPORT_DIR=/tmp/kronos-trivy-keycloak-hardening-report scripts/dev/scan-keycloak-image.sh kronos-keycloak:26.8.1-hardened`. Os dois backends usaram o container `aquasec/trivy:0.75.0` com `--image-src docker --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; relatórios JSON ficaram sob `/tmp/kronos-trivy-runtime-hardening-report/`. O comando do E2E foi `PATH=/home/kronos/.nvm/versions/node/v24.14.1/bin:$PATH bash /tmp/kronos-keycloak-runtime-hardening-e2e.sh`, que construiu projeto, portas, volumes e credenciais descartáveis próprios. Ao final, containers/volumes/redes com o label do projeto e arquivos sintéticos foram verificados em zero; `.env`, `.env.keycloak`, `.env.e2e` e `.demo-admin.credentials` não ficaram no checkout.

Esta mitigação não corrige pacotes. Restrições em produção precisam ser aplicadas e verificadas pelo deployment; `compose` de desenvolvimento/E2E não é prova de configuração de produção. A compatibilidade TLS continua vinculada ao mesmo JRE/checksum/trust store previamente testados, mas o teste TLS não foi repetido nesta rodada. A aprovação humana e avaliação integral dos cinco advisories seguem pendentes.


## Evidência positiva do verificador focal — limitada ao perfil (30/10/2026)

Correção do verificador `8702cb7380663ba78590468818391eb2dcd68d39`; relatório `7701ffb370f6320b5fe9ade6fe80659220d47f08`. No projeto descartável `kronos-hardening-verify-v2-14670-66935`, snapshot `01e4dbb935362921f140c68e4ac01cde6e273016`, sem rebuild, passaram:

| Serviço | Container ID | Image ID | UID:GID esperado | Resultado focal |
|---|---|---|---|---|
| Keycloak | `438960c76ebb59324126a14553e0aa8b23db55c1effdadc2b2c3f71647df3110` | `sha256:ea9bbfabf473a44e0eae167633b4d6fd4fc03c15d24558541ce0eabf57dade7e` | `1000:0` | PASS; NoNewPrivs=1, CapInh/Prm/Eff/Bnd/Amb=0, Privileged=false, CapAdd=[], mount/umount=755 nos caminhos listados |
| Backend produção | `a48a7325bb2b0a82f2c68af5f9cb8ea17b14d7d4c5da86b2f175df9eb9aa9f3c` | `sha256:225d5e7a446d5fed8f3b70a40bce0c0a039e19212b6f71c5ec90e206f874b211` | `10001:10001` | PASS; mesmas condições observadas |

O backend E2E `sha256:b5320e055636d34c5ec4bd93729f5991b8f90a01db3b1cb4a9d5a1c3d1cad54b` **não participou** dessa prova positiva. `postgres` e `keycloak-postgres` ficaram sem as restrições de aplicação; seus processos mantiveram NNP=0 e CapBnd padrão. O projeto descartável foi limpo.

A regressão de identidade usa shim determinístico, não concorrência real do daemon. A inspeção de SUID segue links, mas cobre somente `/bin`, `/sbin`, `/usr/bin`, `/usr/sbin`, `/usr/local/bin`, `/usr/local/sbin`; nenhum PASS significa varredura integral de outros caminhos, volumes ou mounts.

Cinco advisories permanecem sem correção de pacote. Esta leitura de runtime é mitigação do perfil medido, não reclassificação de finding nem aceitação de risco.
