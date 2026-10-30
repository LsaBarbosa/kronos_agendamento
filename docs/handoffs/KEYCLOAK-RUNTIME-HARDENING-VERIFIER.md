# Handoff — verificador focal de hardening do runtime Keycloak

Data: 2026-10-30

## Base e escopo

- Base: `dba28301274b7e120e970727012d1460d18cc18b`.
- Worktree: `/tmp/kronos-keycloak-runtime-hardening`.
- Branch: `review/keycloak-runtime-hardening-verifier`.
- O relatório focal anterior `006b5f0dce9cb80fa4a80b6a28f39e9fec054fb4` foi preservado.
- Arquivos deste incremento: `scripts/dev/verify-runtime-hardening.sh` e este handoff.
- Não alterei Dockerfiles, Compose, workflow, backend nem os arquivos de validação TLS do Terminal 1.

## Uso

```sh
./scripts/dev/verify-runtime-hardening.sh CONTAINER_NAME_OR_ID EXPECTED_UID:EXPECTED_GID
```

Exemplo de sintaxe: `./scripts/dev/verify-runtime-hardening.sh keycloak 1000:0`. Use a identidade que o processo principal do perfil entregue realmente deve ter.

O script exige `docker`, `jq` e um container em execução explicitamente nomeado/identificado. Ele consulta somente os campos selecionados de `docker inspect` (usuário configurado, estado, `Privileged`, `CapAdd` e `SecurityOpt`), resolve UID:GID da identidade de execução do container com `id`, e lê `/proc/1/status` para conferir os quatro UIDs/GIDs, `NoNewPrivs` e `CapInh`, `CapPrm`, `CapEff`, `CapBnd` e `CapAmb`. Não lê `Config.Env`, argumentos de processo, logs ou conteúdo da aplicação.

Para o filesystem, executa `stat` somente nos caminhos convencionais de `mount` e `umount` em `/bin`, `/sbin`, `/usr/bin`, `/usr/sbin`, `/usr/local/bin` e `/usr/local/sbin`, seguindo links simbólicos. Falha quando não consegue inspecionar os metadados. Não percorre diretórios de dados da aplicação.

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

- `bash -n scripts/dev/verify-runtime-hardening.sh` — passou.
- `git diff --check` — passou.
- Perfil inseguro descartável com `kronos-keycloak:26.8.1-temurin-noble`, processo UID:GID `1000:0`, `Privileged=false` e `CapAdd=[]` — rejeitado pelo script, exit `1`.
  - O processo tinha `NoNewPrivs=0`, `CapEff=0`, mas `CapBnd=00000000a80425fb`, além de `/bin/mount`, `/bin/umount`, `/usr/bin/mount` e `/usr/bin/umount` modo `4755`. Isso comprova que a validação não depende apenas de `CapEff` nem do flag `Privileged`.
  - O container foi criado exclusivamente para este teste, por Docker standalone, e removido pelo trap de cleanup. Nenhum Compose foi operado.
- `shellcheck` não está instalado neste ambiente; não foi executado.

O Terminal 1 ainda não forneceu o novo SHA do hardening nem os Image IDs correspondentes. Portanto, **não executei uma validação positiva do perfil endurecido** e ainda não atualizei a avaliação dos cinco advisories para esse perfil.

Após receber os IDs, o comando a executar no container já inicializado do perfil integrado é:

```sh
./scripts/dev/verify-runtime-hardening.sh CONTAINER_NAME_OR_ID EXPECTED_UID:EXPECTED_GID
```

Substitua o último argumento pela identidade efetiva definida no Dockerfile/Compose integrado. O operador deve registrar o SHA do checkout e o Image ID do container antes de executar. A aprovação deste script apenas comprova as condições verificadas naquele container e instante; não corrige pacotes vulneráveis nem decide risco residual.

## Relação com a avaliação focal anterior

Quando SHA e Image IDs integrados forem disponibilizados, reavaliar no perfil concreto:

- CVE-2026-54369 (`acl`): hardening de privilégios pode limitar chamadores privilegiados, mas não corrige a biblioteca nem prova inexistência de chamadores.
- CVE-2026-76642, CVE-2026-78409 e CVE-2026-78410 (`mount`/`libmount`): ausência de SUID em `mount` e capabilities zeradas reduz os caminhos de exploração avaliados; registrar a evidência real e ainda revisar kernel/fstab do perfil.
- CVE-2026-78408 (`nsenter`): manter a conclusão baseada no binário/versão; hardening não substitui a verificação da opção afetada.

Não converter a validação de hardening em status de correção de advisory ou conclusão universal. O relatório anterior permanece imutável.
