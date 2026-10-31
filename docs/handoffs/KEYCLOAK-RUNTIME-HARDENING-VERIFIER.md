# Handoff — verificador focal de hardening do runtime Keycloak

Data: 2026-10-30

## Base e escopo

- Base: `dba28301274b7e120e970727012d1460d18cc18b`.
- Worktree recuperada em caminho persistente: `/home/kronos/Documentos/IMPORTANTE/kronos/keycloak-runtime-hardening-verifier`.
- Branch: `review/keycloak-runtime-hardening-verifier`.
- Commit-base do verificador preservado: `339b3710a645443024961825b7697d7321dd82d1`.
- Commit corretivo: `8702cb7380663ba78590468818391eb2dcd68d39`.
- Worktree separada de validação: `/home/kronos/Documentos/IMPORTANTE/kronos/keycloak-hardening-cut-01e4dbb`, branch `review/keycloak-hardening-pr27-01e4dbb`, no corte `01e4dbb935362921f140c68e4ac01cde6e273016`.
- O relatório focal anterior `006b5f0dce9cb80fa4a80b6a28f39e9fec054fb4` foi preservado.
- Arquivos deste incremento: `scripts/dev/verify-runtime-hardening.sh` e este handoff.
- Não alterei Dockerfiles, Compose, workflow, backend nem os arquivos de validação TLS do Terminal 1.

## Uso

```sh
./scripts/dev/verify-runtime-hardening.sh CONTAINER_NAME_OR_ID EXPECTED_UID:EXPECTED_GID
```

Exemplo de sintaxe: `./scripts/dev/verify-runtime-hardening.sh keycloak 1000:0`. Use a identidade que o processo principal do perfil entregue realmente deve ter.

O script exige `docker`, `jq` e um container em execução explicitamente nomeado/identificado. Resolve o argumento inicial para o ID completo, reinspeciona pelo ID e usa somente esse ID nas chamadas posteriores de `docker exec`. Se o container parar, desaparecer ou falhar uma operação de leitura, aborta com falha explícita, sem fallback ao nome. Consulta somente campos selecionados de `docker inspect` (usuário configurado, estado, `Privileged`, `CapAdd` e `SecurityOpt`), resolve UID:GID da identidade de execução do container com `id`, e lê `/proc/1/status` para conferir os quatro UIDs/GIDs, `NoNewPrivs` e `CapInh`, `CapPrm`, `CapEff`, `CapBnd` e `CapAmb`. Não lê `Config.Env`, argumentos de processo, logs ou conteúdo da aplicação.

Para o filesystem, executa `stat -L` somente nos caminhos convencionais de `mount` e `umount` em `/bin`, `/sbin`, `/usr/bin`, `/usr/sbin`, `/usr/local/bin` e `/usr/local/sbin`, seguindo links simbólicos. Imprime essa lista exata. Falha quando não consegue inspecionar os metadados. Não percorre diretórios de dados da aplicação; um PASS não afirma ausência de SUID fora desses diretórios.

O script não executa `docker run`, `start`, `stop`, `restart`, `kill` ou `rm` para o container recebido. `docker exec` inicia apenas comandos curtos de leitura (`id`, `cat /proc/1/status`, `stat`); não altera configurações nem arquivos do container. Os valores verificados são amostras do processo principal durante a execução e não provam restrições para outros kernels ou futuras implantações.

## Comportamento coberto

O verificador retorna falha com diagnóstico se:

- a identidade resolvida do container ou os UIDs/GIDs real, efetivo, salvo ou filesystem do PID 1 divergirem do UID:GID esperado;
- `NoNewPrivs` do PID 1 não for `1`;
- qualquer campo de capability do PID 1, incluindo `CapBnd`, estiver diferente de zero ou ausente;
- o container estiver configurado como privileged ou tiver `CapAdd` não vazio;
- `mount` ou `umount` nos diretórios executáveis verificados tiver SUID.

O diagnóstico não imprime variáveis de ambiente, credenciais ou dados do aplicativo. `CapEff=0` isoladamente não é considerado suficiente.

## Validação executada

- `bash -n scripts/dev/verify-runtime-hardening.sh` e `git diff --check` — passaram na revisão e novamente após a correção.
- **Prova negativa anterior, preservada separadamente:** o perfil descartável inseguro foi rejeitado com exit `1`; havia `NoNewPrivs=0`, `CapEff=0`, `CapBnd=00000000a80425fb` e `mount`/`umount` SUID 4755. Essa prova precede a correção do vínculo por ID e não é usada como evidência da regressão de troca de instância.
- **Regressão determinística de troca de nome (mock, não prova Docker real):** um shim temporário de `docker` fez a inspeção inicial do nome retornar o ID completo A (`aaaa…aaaa`), depois remapeou o mesmo nome para o substituto B (`bbbb…bbbb`) e tornou A indisponível. A versão corrigida reinspecionou A, falhou com exit `1` antes de executar qualquer comando e não emitiu `Hardening verification PASSED`. O log confirmou somente `inspect` por nome e por A; não houve `exec` no nome nem no substituto. Como controle, a versão-base `339b371` foi executada contra o mesmo shim: fez três `docker exec` pelo nome agora associado a B e emitiu `PASS` usando as respostas sintéticas de B. Assim a regressão reproduz a mistura que a correção impede. O teste usa somente shim e arquivos temporários em `/tmp`, removidos ao final; não é prova de corrida real no daemon.
- **Validação positiva real do Docker/Compose**, projeto descartável `kronos-hardening-verify-v2-14670-66935`, criado no snapshot exato `01e4dbb935362921f140c68e4ac01cde6e273016`, usando `compose.yaml`, `compose.keycloak.yaml`, `compose.identity.yaml` e `--profile app`, sem `compose.e2e.yaml`, sem build e sem Compose compartilhado. Todos os quatro serviços ficaram healthy. O verificador corrigido passou para Keycloak e backend produção:

| Serviço | Container ID completo | Image ID completo | PID 1 UID/GID (real/effective/salvo/fs) | Resultado |
|---|---|---|---|---|
| Keycloak | `438960c76ebb59324126a14553e0aa8b23db55c1effdadc2b2c3f71647df3110` | `sha256:ea9bbfabf473a44e0eae167633b4d6fd4fc03c15d24558541ce0eabf57dade7e` | `1000:0` em todos os campos | PASS |
| Backend produção | `a48a7325bb2b0a82f2c68af5f9cb8ea17b14d7d4c5da86b2f175df9eb9aa9f3c` | `sha256:225d5e7a446d5fed8f3b70a40bce0c0a039e19212b6f71c5ec90e206f874b211` | `10001:10001` em todos os campos | PASS |

  Em ambos: `NoNewPrivs=1`; `CapInh`, `CapPrm`, `CapEff`, `CapBnd` e `CapAmb` iguais a zero; `Privileged=false`; `CapAdd=[]`; opção `no-new-privileges:true` configurada; `mount` e `umount` modo 755 nos caminhos listados pelo script. Os IDs são os artefatos já construídos do handoff, não foram reconstruídos nesta validação.
- **PostgreSQL não recebeu as restrições da aplicação:** nos serviços `postgres` e `keycloak-postgres`, `Privileged=false`, `CapDrop=[]`, `CapAdd=[]`, `SecurityOpt=[]`; PID 1 mostrou `NoNewPrivs=0` e `CapBnd=00000000a80425fb` em ambos. Isso confirma os controles Docker efetivos, não apenas o YAML.
- **Limpeza:** o projeto exclusivo foi baixado com volumes/orphans próprios; conferência final por label retornou zero containers, volumes e redes.
- **Inspeção dos dois Image IDs (standalone e somente leitura):** ambos continuam com `libacl1:amd64=2.3.2-1build1.1`, `util-linux=2.39.3-9ubuntu6.6`, `mount=2.39.3-9ubuntu6.6` e `libmount1:amd64=2.39.3-9ubuntu6.6`; os componentes não foram atualizados. `/usr/bin/nsenter --join-cgroup --help` foi rejeitado nos dois; `/etc/fstab` não tinha entradas não-comentadas. Kernel observado pelo container: `6.12.88+deb13-amd64` (kernel do host, não fixado pela imagem).
- `shellcheck` não está instalado; não foi executado. Maven, TLS, Trivy e browser E2E não foram repetidos: não houve mudança funcional nesses escopos.

## Avaliação focal atualizada — somente artefatos e perfil observados

Todos os cinco casos permanecem sem correção de pacote nestes Image IDs. `Ignored`/`Needs evaluation`, scan ou hardening runtime não significam pacote corrigido nem aceitação de risco. As condições de runtime abaixo estão limitadas ao projeto e kernel medidos; o relatório anterior `006b5f0dce9cb80fa4a80b6a28f39e9fec054fb4` permanece imutável e documenta as pré-condições upstream/fontes primárias.

| CVE | Pacote presente nos dois Image IDs | Evidência do perfil medido | Conclusão limitada ao perfil testado |
|---|---|---|---|
| CVE-2026-54369 (`acl`) | `libacl1` `2.3.2-1build1.1`; biblioteca e APIs pathname continuam distribuídas. Canonical: `Ignored`. | Processos Keycloak/backend não-root; todos os campos UID/GID do PID 1 são os esperados; `NoNewPrivs=1` e capabilities zeradas impedem que esses processos obtenham privilégio por SUID/capabilities no perfil medido. | **Pacote não corrigido. Vetor de elevação dos dois processos de aplicação mitigado pelos controles medidos; aplicabilidade para qualquer outro chamador privilegiado permanece inconclusiva.** Não prova inexistência de outro consumidor privilegiado no container/host nem cobre implantação futura. |
| CVE-2026-76642 (`util-linux`/`mount`/`libmount`) | `mount`, `libmount1` e `util-linux` `2.39.3-9ubuntu6.6`. Canonical: `Needs evaluation`. | `mount`/`umount` sem SUID (755); PID 1 tem NNP=1 e capabilities todas zero; fstab das imagens não tem entradas não-comentadas. | **Pacote não corrigido. Vetor que depende de `mount` SUID mitigado; a condição de fstab do advisory está ausente nas imagens.** Limitado aos serviços e configuração testados. |
| CVE-2026-78408 (`util-linux`/`nsenter`) | `util-linux` `2.39.3-9ubuntu6.6`. Canonical: `Needs evaluation`. | `nsenter` instalado em 2.39.3, abaixo da faixa upstream afetada `>=2.40`; o executável rejeita `--join-cgroup`; capabilities do serviço são zero. | **Componente/pacote não atualizado, mas a opção afetada não está neste executável e a faixa upstream não coincide.** Conclusão técnica do binário medido; status oficial permanece `Needs evaluation` e upgrades exigem rechecagem. |
| CVE-2026-78409 (`util-linux`/`libmount`) | `util-linux`/`libmount1` `2.39.3-9ubuntu6.6`. Canonical: `Needs evaluation`. | Upstream descreve faixa `>=2.42` e caminho Linux `>=6.15`; aqui userland é 2.39.3 e kernel observado 6.12.88. Fstab sem entradas, `mount` 755, NNP=1 e capabilities zero. | **Pacote não corrigido; pré-condições do caminho descrito não coincidem com este userland/kernel/perfil.** Kernel não é fixado pela imagem; não generalizar a outros hosts. |
| CVE-2026-78410 (`util-linux`/`mount`/`libmount`) | `mount`, `libmount1` e `util-linux` `2.39.3-9ubuntu6.6`. Canonical: `Needs evaluation`. | `mount`/`umount` 755, sem SUID; NNP=1/capabilities zero; nenhuma regra fstab não-comentada no snapshot. | **Pacote não corrigido. Vetor privilegiado do `mount` e pré-condição de fstab estão mitigados/ausentes neste perfil.** Não cobre override ou deployment diferente. |

## Limitações remanescentes

- A regressão de substituição é mock determinístico; não provoquei uma remoção/recriação concorrente real durante `docker exec`. A validação real usou IDs de containers imutáveis, mas sem injetar essa corrida no daemon.
- A busca de modos SUID é restrita aos nomes `/bin/{mount,umount}`, `/sbin/{mount,umount}`, `/usr/bin/{mount,umount}`, `/usr/sbin/{mount,umount}`, `/usr/local/bin/{mount,umount}` e `/usr/local/sbin/{mount,umount}`, seguindo links. Não percorre todo o filesystem, volumes ou dados da aplicação; um PASS não prova ausência em outros caminhos.
- Não houve exploração, scan de vulnerabilidades novo, atualização de pacotes, teste de configuração diferente, nem decisão de risco. Nenhum pacote foi removido/corrigido e nenhum risco foi aceito.
- O teste positivo usou o profile da aplicação com Keycloak e backend produção; o backend E2E `sha256:b5320e055636d34c5ec4bd93729f5991b8f90a01db3b1cb4a9d5a1c3d1cad54b` não foi executado nesta rodada.

Fontes primárias para condições/faixas upstream e status Canonical consultadas nesta avaliação: [CVE-2026-54369 Canonical](https://ubuntu.com/security/CVE-2026-54369) e [GHSA de ACL](https://github.com/advisories/GHSA-53ch-pxc8-6g72); [CVE-2026-76642 Canonical](https://ubuntu.com/security/CVE-2026-76642) e [GHSA-m25x-3hj9-m26f](https://github.com/util-linux/util-linux/security/advisories/GHSA-m25x-3hj9-m26f); [CVE-2026-78408 Canonical](https://ubuntu.com/security/CVE-2026-78408) e [GHSA-55fx-f4gg-cfhj](https://github.com/util-linux/util-linux/security/advisories/GHSA-55fx-f4gg-cfhj); [CVE-2026-78409 Canonical](https://ubuntu.com/security/CVE-2026-78409) e [GHSA-8f2p-47x3-43mv](https://github.com/util-linux/util-linux/security/advisories/GHSA-8f2p-47x3-43mv); [CVE-2026-78410 Canonical](https://ubuntu.com/security/CVE-2026-78410) e [GHSA-rh77-686x-2f2m](https://github.com/util-linux/util-linux/security/advisories/GHSA-rh77-686x-2f2m). Os quatro trackers util-linux consultados listavam Noble como `Needs evaluation`; o status ACL `Ignored` permanece o registrado no relatório focal anterior.
