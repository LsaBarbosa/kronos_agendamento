# AGD-003 edge runtime evaluation — candidate rejected

**Decision: CANDIDATO REPROVADO.** No change was made to the PR #33 runtime and no commit was pushed. The current policy remains HIGH/CRITICAL, `ignore-unfixed=false`, and no suppressions. The controlled diagnostic image fails that policy with one HIGH finding; therefore HTTPS/OIDC smoke and a proposed edge CI job were not run/added.

## Scope and provenance

- Review branch: `fix/pr33-caddy-edge-security`, based on PR #33 head `6ae8daca58bd40fcca84cc1567eb4281c37167dd`.
- PR #32 was confirmed merged into `main` at `618512d0b8c57554e7ec5cc54701f0eed3662ea4`. Since this evaluation failed the scan gate and was not published, `main` was not merged into this diagnostic branch.
- Official Caddy stable release inspected first: `v2.11.7`, upstream commit `72dd0fb067f6d7826c7f79907670ba4a713bfe37`. GitHub marks the release tag and commit as verified. Local `git verify-tag` could not establish trust because this host lacks `gpg.ssh.allowedSignersFile`; the local tag object includes SSH signature key ID `B5690EEEBB952194`. Official release source: <https://github.com/caddyserver/caddy/releases/tag/v2.11.7>.
- The official Docker image is published as `2.11.7-alpine`; resolved platform image: `linux/amd64`, Image ID `sha256:3067cc4e02c091399edbd5d65f85a3ff4d511574aecadb47f280c3761e68852e`, digest `caddy@sha256:e70b164da81ef698b3ad19af799ea6086ce6d9e48ed4079eb9fe26f6fbf14ee5`.
- The official image scan and its complete Trivy JSON are preserved under `official-image/`. The official JSON report was generated with policy-matching severity and `ignore-unfixed=false`, but `--exit-code 0` to collect all findings; the finding count fails the gate. The controlled-image scan used the required `--exit-code 1` and exited `1`.

## Results

| Artifact | Toolchain/runtime | Image identity | HIGH | CRITICAL | Gate |
|---|---|---|---:|---:|---|
| Official Caddy `2.11.7-alpine` | Caddy v2.11.7; embedded Go stdlib `1.26.8`; Alpine `3.23.7` | `sha256:3067cc4e…e68852e`; digest above | 9 | 1 | Rejected |
| One controlled diagnostic build | Caddy v2.11.7 source commit; Go `1.27.3`; Alpine `3.23.7` pinned base | `sha256:8666417e140aee2c8e0522022e77106d291cb092901f612836d2910d80c97ca7`, `linux/amd64`, user `1000:1000` | 1 | 0 | Rejected |

Official image findings (full details are in `official-image/trivy-report.json`):

- Alpine: `CVE-2026-111497` CRITICAL in `brotli-libs 1.2.0-r0`; `CVE-2026-92392` HIGH in `curl 8.22.0-r0` and `libcurl 8.22.0-r0`.
- Caddy Go binary: seven HIGH findings — `CVE-2026-116587` in `go-jose/v3 3.0.5` (Trivy reports no fixed version); the same CVE in `go-jose/v4 4.1.5` (fixed `4.1.6`); `CVE-2026-78669` in `golang.org/x/net 0.59.0` (fixed `0.60.0`); and four stdlib findings in Go `1.26.8` (`CVE-2026-111943`, `CVE-2026-78667`, `CVE-2026-78669`, `CVE-2026-97031`).

The diagnostic build used Go `1.27.3` (official Go release, from official image digest `golang@sha256:8a5046497cda39860b832ffdbe8dd66018a0f05bb01352f533a36e7e2ced66a0`), upgraded `x/net` to `0.60.0` and `go-jose/v4` to `4.1.6`, and used the minimal pinned Alpine base `alpine@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63`. Its retained package database/inventory has 16 packages and does not contain `brotli-libs`, `curl`, or `libcurl`. Trivy still reports `CVE-2026-116587` HIGH in `github.com/go-jose/go-jose/v3 v3.0.5`, with no fixed version listed. It exits `1` under the required policy. The exact binary, build metadata, image inventory, and full scan report are under `diagnostic-build/`.

## Why the investigation stopped

The stable official image does not pass. One controlled source build fixes the Go standard library and the two modules with published fixed versions, and the minimal official Alpine base removes the three vulnerable APKs without deleting package metadata. The remaining go-jose/v3 finding has no fixed version reported. The source dependency is part of Caddy's supported TLS/internal-CA module graph; treating the finding as irrelevant, pruning its metadata, patching out Caddy features solely to clear the scanner, or suppressing it would not meet the requested gate. No risk acceptance is made here.

The Go tool's `go version -m` output records the binary's module build information and checksums. The custom diagnostic entrypoint reported `caddy version` as `unknown`; source identity is bound to the upstream release commit instead. This binary is a scan-only diagnostic artifact, not a deployable candidate: it does not include the built frontend assets or staging certificate mounts. It was not smoke-tested. The existing Caddyfile and Compose were not edited.

## Scanner evidence

- Scanner: official `aquasec/trivy:0.75.0`, Image ID and inspect data in `scanner-image-inspect.json`.
- Both scans used Trivy `vuln`, severities `HIGH,CRITICAL`, `--ignore-unfixed=false`; no ignore file or suppression flags were provided. The official report collection used `--exit-code 0`; the controlled image used `--exit-code 1` and returned `1`.
- Diagnostic DB metadata is captured in `diagnostic-build/database-metadata.json`; downloaded at `2026-11-03T12:41:16Z`, DB `UpdatedAt=2026-11-03T07:14:02Z`, `NextUpdate=2026-11-04T07:14:02Z`.
- The diagnostic scan read the exact `docker save` tar whose SHA-256 is in `diagnostic-build/image-tar.sha256`; scanner exit status `1` is in `diagnostic-build/scan-exit-status.txt`.
- Official Go release history identifies Go `1.27.3` as a security release for `crypto/x509`: <https://go.dev/doc/devel/release>. Caddy's supported custom-build procedure is documented at <https://caddyserver.com/docs/build>.
- The go-jose advisory for `CVE-2026-116587` describes denial of service when parsing untrusted RSA JWKs: <https://github.com/go-jose/go-jose/security/advisories/GHSA-mgcc-cww5-fp2q>. This report does not claim reachability from the current Caddyfile, nor does that uncertainty turn the scanner finding into a pass.

## Checks and limits

- Executed: official Caddy image full Trivy scan; Go `1.27.3` controlled compile from the pinned Caddy source revision; diagnostic image full Trivy scan; package inventory, image identity, SBOM, binary checksum, and Go build metadata capture.
- Not executed: HTTPS/OIDC smoke, because the diagnostic scan failed before the smoke gate; no edge CI workflow was added; no GitHub checks for this local diagnostic report were run.
- No image was pushed to a registry. No PR head, base, Caddyfile, Compose, workflow, backend, DNS, certificate, or shared Compose resource was changed.

## Artifact index

- `official-image/trivy-report.json`: complete official-image report.
- `official-image/image-inspect.json`, `official-image/image-id.txt`, `official-image/image-tar.sha256`, `official-image/os-and-packages.txt`: official artifact identity and inventory.
- `diagnostic-build/trivy-report.json`: complete controlled-build report.
- `diagnostic-build/inventory.cdx.json`, `diagnostic-build/os-and-packages.txt`, `diagnostic-build/image-inspect.json`, `diagnostic-build/image-identity.txt`: diagnostic image inventory and identity.
- `diagnostic-build/caddy-edge-main.go`, `diagnostic-build/diagnostic-Dockerfile`, `diagnostic-build/caddy-binary.sha256`, `diagnostic-build/go-build-info.txt`: controlled-build inputs and binary provenance.
