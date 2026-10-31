# Avaliação focal: `acl` e `util-linux` no runtime Keycloak Noble

**Resultado:** os pacotes das cinco advisories estão presentes no runtime. A opção vulnerável de CVE-2026-78408 não está no `nsenter` deste build; as condições upstream de 78409 também não coincidem com o binário/kernel observados. CVE-2026-54369 e os caminhos fstab/SUID de 76642 e 78410 permanecem condicionais e sem correção oficial demonstrada. Nenhum status `Ignored`/`Needs evaluation` é tratado como corrigido.

## Base, artefato e método

- Head fixado: `fdc5de13f46487c1b01b90208a2293e564b3767f`.
- Worktree de avaliação: `/tmp/kronos-keycloak-acl-util-review`, branch `review/keycloak-acl-util-applicability`.
- Image ID integrado conferido localmente: `sha256:31296d4266bf8f893bc1e6535d54b1e1fb01cd0eab3f371701a918640dd92775`, tag `kronos-keycloak:26.8.1-temurin-noble`, `linux/amd64`.
- A identidade e pacotes foram consultados dentro de um container descartável desse Image ID, sem Compose. A chamada de inspeção usou o `USER` configurado pela imagem; não trocou usuário, não concedeu capabilities e não montou o filesystem do host.
- Fonte de pacote e versões instaladas vêm de `dpkg-query`; caminhos e modos de `stat`; owners de `dpkg-query -S`; capabilities/status do processo de `/proc/self/status`; kernel de `uname -r`; fstab do próprio filesystem da imagem. Nenhum finding de scanner foi usado como evidência de não aplicabilidade.
- A configuração Compose examinada é a de desenvolvimento: `start-dev`, sem `cap_add`, `cap_drop`, `security_opt` ou `read_only`. Isso descreve o snapshot; não fixa a configuração do orquestrador futuro.

Consulta reproduzível dos pacotes e configuração dentro do artefato:

```sh
docker run --rm --entrypoint /bin/bash \
  sha256:31296d4266bf8f893bc1e6535d54b1e1fb01cd0eab3f371701a918640dd92775 \
  -c 'id; grep -E "^(Cap(Inh|Prm|Eff|Bnd|Amb)|NoNewPrivs|Seccomp):" /proc/self/status; uname -r; dpkg-query -W libacl1 util-linux mount libmount1 bsdutils login libblkid1 libsmartcols1 libuuid1; stat -c "%a %U:%G %n" /usr/bin/mount /usr/bin/nsenter /etc/fstab; sed -n "1,10p" /etc/fstab'
```

Saídas relevantes observadas:

```text
uid=1000(ubuntu) gid=0(root) groups=0(root)
CapEff: 0000000000000000
CapBnd: 00000000a80425fb     # CAP_SYS_ADMIN (bit 21) não está no bounding set observado
NoNewPrivs: 0
Seccomp: 2
host_kernel=6.12.88+deb13-amd64
libacl1:amd64=2.3.2-1build1.1
util-linux=2.39.3-9ubuntu6.6
mount=2.39.3-9ubuntu6.6
libmount1=2.39.3-9ubuntu6.6
stat: /usr/bin/mount 4755 root:root
stat: /usr/bin/nsenter 755 root:root
stat: /etc/fstab 644 root:root
# UNCONFIGURED FSTAB FOR BASE SYSTEM
```

Também consultei os componentes nos próprios binários: `/usr/bin/nsenter --join-cgroup --help` retorna `unrecognized option '--join-cgroup'`; `nsenter --help` oferece apenas `--cgroup` (entrar em namespace cgroup). O source upstream do CVE-78408 declara versões afetadas `>=2.40`; o pacote instalado é `2.39.3`. Já a biblioteca `libmount.so.1` contém strings `X-mount.subdir`, `X-mount.idmap`, `X-mount.owner`, `X-mount.group` e `X-mount.mode`; a presença da opção `X-mount.subdir` não comprova a presença do caminho vulnerável detached-tree, que upstream limita a versões `>=2.42` e kernel `>=6.15`.

`dpkg-query -S` associa `/usr/bin/mount` ao binário `mount` e `/usr/bin/nsenter` ao binário `util-linux`. O link `/lib/x86_64-linux-gnu/libacl.so.1` aponta para a biblioteca do binário `libacl1`. A biblioteca está ligada dinamicamente a `/usr/bin/cp` e `/usr/bin/tar`, ambos não SUID. A varredura de dependências dos executáveis SUID observados não encontrou `libacl`; isso não exclui carregamento dinâmico posterior, processos adicionais ou outra configuração.

## Matriz por CVE

| CVE / tracker Noble | Pacote fonte e binários presentes | Componente afetado e presença | Pré-condições de exploração | Usuário, permissões e configuração observados | Aplicabilidade no runtime avaliado |
|---|---|---|---|---|---|
| **CVE-2026-54369** — Canonical `Ignored` | Fonte `acl`; `libacl1:amd64=2.3.2-1build1.1` | `libacl.so.1` presente; funções pathname `acl_get_file()`, `acl_set_file()`, `acl_extended_file()` e `acl_delete_def_file()`. `cp` e `tar` linkam a biblioteca, sem SUID. Executáveis SUID observados não apresentaram link dinâmico direto a libacl. | Um chamador **privilegiado** precisa usar uma dessas funções em caminho cujo componente o atacante consegue substituir por symlink. O chamador vulnerável pode então ler/alterar ACL de objeto não autorizado. O risco está na combinação chamador+privilégio+caminho controlável, não na mera instalação da biblioteca. | Keycloak usa UID 1000, GID/grupo primário 0 e `CapEff=0`. `cp`/`tar` não são SUID; não foi observado consumidor SUID ligado a libacl. O grupo `root` não equivale a UID 0, mas pode permitir acesso a arquivos com permissão de grupo. `NoNewPrivs=0` deixa disponível a semântica normal de SUID de outros executáveis. | **Uso privilegiado pelo processo Keycloak não demonstrado; não aplicabilidade geral inconclusiva.** A biblioteca está presente e a ausência de chamadas diretas do Keycloak não descarta um consumidor adicional ou operação administrativa privilegiada. Canonical `Ignored` não significa corrigido. |
| **CVE-2026-76642** — Canonical `Needs evaluation` | Fonte `util-linux`; `mount=2.39.3-9ubuntu6.6`, `libmount1=2.39.3-9ubuntu6.6`, `util-linux=2.39.3-9ubuntu6.6` | `/usr/bin/mount` e libmount presentes; `mount` é SUID root (`4755`). | Uma entrada `fstab` autorizada a usuário precisa selecionar helper externo `mount.<tipo>` que falha, e habilitar hooks como `X-mount.idmap` ou `X-mount.owner/group/mode`. O bug executa hook privilegiado sobre filesystem/target preexistente mesmo com helper falho; `owner/group/mode` pode alterar target sem mount bem sucedido. | `/etc/fstab` da imagem só contém comentário padrão, modo `0644 root:root`; nenhum `mount.*` helper foi encontrado nos diretórios binários inspecionados. O processo Keycloak tem `CapEff=0`, mas `NoNewPrivs=0`; `CAP_CHOWN` existe no `CapBnd` medido. `CAP_SYS_ADMIN` não está no bounding set observado. | **Pré-condições ausentes na imagem/configuração local examinada, mas o vetor SUID está presente.** O caminho do hook de ownership após falha merece atenção porque não se pode inferir que a falta de `CAP_SYS_ADMIN` neutraliza todo efeito privilegiado. Fstab/helper podem mudar no deployment ou numa imagem derivada. Não classificar não aplicável em produção futura sem fixar essas restrições. |
| **CVE-2026-78408** — Canonical `Needs evaluation` | Fonte `util-linux`; `util-linux=2.39.3-9ubuntu6.6` | `/usr/bin/nsenter` presente, modo `0755 root:root`, não SUID; o executável rejeita diretamente `--join-cgroup` como opção desconhecida. Upstream declara faixa afetada `>=2.40`; o binário é 2.39.3. | O caminho upstream requer a opção `nsenter --join-cgroup` para abrir `cgroup.procs` privilegiado contra alvo controlado; essa opção não está no binário examinado. | Keycloak UID 1000, `CapEff=0`; `CAP_SYS_ADMIN` ausente do bounding set observado. O serviço Compose não adiciona capability. O container não fornece a opção vulnerável mesmo se alguém chamar `nsenter`. | **Não aplicável ao binário `nsenter` 2.39.3 desta imagem conforme a evidência da opção e da faixa upstream.** Canonical mantém `Needs evaluation`, portanto esta é uma conclusão técnica para este artefato, não um status oficial do pacote. Reavaliar se o pacote mudar ou se outra versão/host tool for executada no container. |
| **CVE-2026-78409** — Canonical `Needs evaluation` | Fonte `util-linux`; `mount=2.39.3-9ubuntu6.6`, `libmount1=2.39.3-9ubuntu6.6`, `util-linux=2.39.3-9ubuntu6.6` | `/usr/bin/mount` SUID presente; `libmount` contém strings de `X-mount.subdir`, mas upstream declara a faixa vulnerável `>=2.42`; o binário Noble é 2.39.3. | O caminho upstream exige kernel Linux **>=6.15**, fast path detached-tree, entrada `fstab` autorizada para usuário com `X-mount.subdir` e subárvore controlável com symlink intermediário/procfs que escape da nova montagem. | O host em que a imagem foi inspecionada usa Linux `6.12.88`; `/etc/fstab` não tem entradas. Processo sem capabilities efetivas; CapBnd não contém `SYS_ADMIN`; mount conserva SUID e NNP não está ativo. Kernel pertence ao host e não é fixado pela imagem. | **O caminho vulnerável descrito não se aplica ao conjunto observado (util-linux 2.39.3, kernel 6.12.88, fstab sem regras).** A presença da opção não é ausência da função, mas a faixa upstream do bug é posterior ao binário instalado. Canonical ainda não avaliou Noble; confirmar source patches do pacote e reavaliar em imagem/kernel/ambiente diferentes antes de generalizar. |
| **CVE-2026-78410** — Canonical `Needs evaluation` | Fonte `util-linux`; `mount=2.39.3-9ubuntu6.6`, `libmount1=2.39.3-9ubuntu6.6`, `util-linux=2.39.3-9ubuntu6.6` | `/usr/bin/mount` SUID presente; hook libmount `X-mount.owner/group/mode` está no componente afetado. | Requer entrada `fstab` de bind/rbind com permissão de usuário; source path ou ancestral gravável deve poder ser substituído antes do mount privilegiado; a entrada combina `X-mount.owner`, `X-mount.group` ou `X-mount.mode`. O hook então chown/chmod no inode redirecionado. | Fstab atual sem regras e root-owned; nenhuma entrada/source vulnerável observada. `mount` 4755 e NNP 0 mantêm a possibilidade de caminho SUID se um operador adicionar configuração apropriada. | **Pré-condições não encontradas no snapshot local; não aplicável a essa fstab vazia específica.** O executável e o modo SUID permanecem, e uma fstab futura/derivada pode satisfazer as pré-condições. Não é não aplicável ao artefato em todos os ambientes. |

As versões Noble instaladas estão abaixo das versões upstream `util-linux` 2.41.6/2.42.3, listadas como corrigidas para os advisories util-linux. O tracker Canonical ainda mostra `Needs evaluation` em Noble para os quatro; `acl` 2.3.2 ainda é anterior à versão upstream 2.4.0 e Canonical lista o caso como `Ignored`. [A semântica Canonical distingue `Needs evaluation` de `Ignored`](https://people.canonical.com/~ubuntu-security/priority.html): nenhum dos estados prova correção.

## Mitigações técnicas propostas (não aplicadas)

1. **Bloquear elevação por SUID na implantação e no artefato.** Remover o bit SUID de `/usr/bin/mount` na imagem (`chmod u-s /usr/bin/mount`) e, no serviço/container, usar `security_opt: [no-new-privileges:true]` e `cap_drop: [ALL]`. Isso bloqueia o caminho de mount privilegiado para 76642/78409/78410, impede elevação via SUID no conjunto do container e deixa `CAP_SYS_ADMIN` explicitamente fora para 78408. O Docker documenta a remoção de capabilities e `no-new-privileges`; Compose aceita ambos como configuração de serviço ([Docker run](https://docs.docker.com/engine/containers/run/), [Compose services](https://docs.docker.com/reference/compose-file/services/)).
   - **Impacto esperado:** o container deixa de usar `mount` SUID e todos os privilégios especiais/capabilities concedidos por padrão. Infraestrutura normal de volume/rede é aplicada pelo runtime do container e não depende de `mount` executado dentro do Keycloak; ainda assim, compatibilidade do processo precisa ser testada.
   - **Teste antes de adotar:** confirmar em processo Keycloak que `NoNewPrivs=1`, `CapEff=0`, `CapBnd=0`, `/usr/bin/mount` não tem modo SUID e um helper/probe não eleva EUID; depois executar importação, health/readiness e login OIDC. Em host Linux >=6.15, testar a configuração endurecida sem usar uma cadeia explorável: garantir que não há fstab user entries e que nenhuma operação de mount é autorizada ao UID do serviço.
2. **Tratar `libacl1` separadamente.** A restrição de privilégios acima reduz chamadores privilegiados disponíveis ao Keycloak, mas **não corrige a API vulnerável**. A correção upstream adiciona variantes `*_at()` que aceitam dirfd/flags; para eliminar o problema de forma funcional, consumidores privilegiados precisam migrar para essas variantes/uso seguro de file descriptors. Remover `libacl1` isoladamente pode quebrar programas do sistema que a linkam (`cp`/`tar` já a linkam neste artefato); não recomendo remoção cega. Um fork/backport precisa de prova de ABI e testes de cópia/restauração de ACL antes da integração.
3. **Não permitir injeção futura de `/etc/fstab` ou capabilities para o Keycloak sem nova avaliação.** A imagem não fixa kernel. O deployment precisa aplicar os limites ao workload e revisar `user`, `cap_add`, `security_opt`, mounts e imagens derivadas. A configuração Compose atual é dev e não serve como política de produção.

## Decisão de risco ainda pendente

O proprietário do PR/runtime precisa escolher, sem aceitação implícita nesta avaliação:

- **Manter Noble com controles explícitos** (SUID removido, NNP, `cap_drop: ALL`, sem fstab user mounts), executar os testes de compatibilidade e aceitar formalmente que os status dos pacotes permanecem abertos até orientação/fix Canonical. Isso reduz a alcançabilidade no workload, mas não transforma os CVEs em corrigidos.
- **Aguardar/usar pacotes cuja correção seja reconhecida pelo fornecedor da distribuição**, sem ignorar findings, e repetir scan e compatibilidade no artefato exato.
- **Migrar os chamadores privilegiados para APIs seguras ou eliminar os consumidores**, quando possível; para `acl`, apenas trocar a biblioteca sem mudar chamadas não prova que os antigos pathname APIs deixaram de seguir symlinks.

Sem definição do ambiente futuro e sem decisão sobre risco residual, o gate de segurança desses cinco advisories continua **pendente**. Esta avaliação não cria suppression e não declara prontidão para produção.

## Fontes primárias

- Canonical trackers: [CVE-2026-54369](https://ubuntu.com/security/CVE-2026-54369), [CVE-2026-76642](https://ubuntu.com/security/CVE-2026-76642), [CVE-2026-78408](https://ubuntu.com/security/CVE-2026-78408), [CVE-2026-78409](https://ubuntu.com/security/CVE-2026-78409), [CVE-2026-78410](https://ubuntu.com/security/CVE-2026-78410).
- Upstream `util-linux`: [76642 / GHSA-m25x-3hj9-m26f](https://github.com/util-linux/util-linux/security/advisories/GHSA-m25x-3hj9-m26f), [78408 / GHSA-55fx-f4gg-cfhj](https://github.com/util-linux/util-linux/security/advisories/GHSA-55fx-f4gg-cfhj), [78409 / GHSA-8f2p-47x3-43mv](https://github.com/util-linux/util-linux/security/advisories/GHSA-8f2p-47x3-43mv), [78410 / GHSA-rh77-686x-2f2m](https://github.com/util-linux/util-linux/security/advisories/GHSA-rh77-686x-2f2m).
- `acl`: [upstream disclosure and compatibility notes](https://openwall.com/lists/oss-security/2026/06/29/1), [GitHub Advisory Database GHSA-53ch-pxc8-6g72](https://github.com/advisories/GHSA-53ch-pxc8-6g72).
- Canonical status meanings: [Ubuntu priority/status guide](https://people.canonical.com/~ubuntu-security/priority.html), [Ubuntu OSV mapping](https://documentation.ubuntu.com/security/security-updates/osv/).

## Limites desta avaliação

Não houve prova de exploração, teste de alteração fstab, execução de mount/nsenter como root, build com mitigações, teste OIDC pós-mitigação nem CI. Não foram alterados Dockerfiles, Compose, workflow, backend ou arquivos de validação do Terminal 1. A medição de capabilities é do Docker Engine local usando container standalone; configurações diferentes de daemon/orquestrador podem alterar usuário, capabilities, mounts e NNP. O autor do handoff consolidou a avaliação; houve revisão independente em leitura para CVE-2026-54369 e para os quatro advisories util-linux. Os revisores não alteraram arquivos.
