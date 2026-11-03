# AGD-003 edge image scan

- Image ID: `sha256:e2033e54c3cbd36468c73999e617f4e7615de5383b76bc22218bd2e8685cc4ce`
- Tag used for smoke and scan: `kronos-staging-agd003-https-1793707246-469496-84717e-edge:run`
- Docker image tar SHA-256 scanned by Trivy: `3416b6ee0f4592547a0e62cb9cff29ec02d1489bb5e334254c3e3985e83fcc49`.
- Platform: `linux/amd64`
- Configured user: `1000:1000`
- Scanner: Trivy 0.75.0 official container `aquasec/trivy:0.75.0`.
- Scanner image ID: `sha256:6d93f1ec925b37c5ce785e416b86cba056e10c8c2914a59a8ea7be18115d6764`; registry digest `aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`.
- Policy: `image --input edge-image.tar --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; no ignore file or suppression flags were supplied.
- Vulnerability database: version `2`, updated `2026-11-03T07:14:02.629452927Z`, next update `2026-11-04T07:14:02.629452405Z`, downloaded `2026-11-03T12:02:20.985520331Z`.
- CycloneDX component count: 180; full inventory: `inventory.tsv` and `inventory.cdx.json`.
- Image metadata identifies Caddy `v2.11.4`; APK inventory has 32 packages, Alpine `3.23.6`.
- Findings by scanner target: 3 Alpine OS-package entries and 26 Go binary dependency entries in `usr/bin/caddy` (Go stdlib inventory version `v1.26.3`).
- HIGH/CRITICAL findings: 29 total (1 CRITICAL, 28 HIGH).
- Scanner exit status: 1 (0 means no policy-level findings; 1 means findings were reported).
- Exact machine-readable report: `trivy-report.json`; database metadata: `database-metadata.json`; scanned image configuration: `image-inspect.json`; scanner artifact configuration: `scanner-image-inspect.json`; OS/package inventory: `os-and-packages.txt`.
- The HTTPS/OIDC smoke ran before scanning in the same disposable runner, and printed this same immutable Image ID. The scanner read a `docker save` archive from that tag after verifying the tag resolves to this ID. The runner retained uniquely tagged images only until scan/report generation; this script then removed only those unique tags.

## Findings

- `CVE-2026-111497` CRITICAL: `brotli-libs` installed `1.2.0-r0`, fixed `1.2.1-r0`.
- `CVE-2026-92392` HIGH: `curl` installed `8.22.0-r0`, fixed `8.23.0-r0`.
- `CVE-2026-92392` HIGH: `libcurl` installed `8.22.0-r0`, fixed `8.23.0-r0`.
- `CVE-2026-116587` HIGH: `github.com/go-jose/go-jose/v3` installed `v3.0.5`, fixed `not listed`.
- `CVE-2026-116587` HIGH: `github.com/go-jose/go-jose/v4` installed `v4.1.4`, fixed `4.1.6`.
- `CVE-2026-63209` HIGH: `github.com/klauspost/compress` installed `v1.18.6`, fixed `1.18.7`.
- `CVE-2026-56854` HIGH: `golang.org/x/crypto` installed `v0.52.0`, fixed `0.55.0`.
- `CVE-2026-46600` HIGH: `golang.org/x/net` installed `v0.55.0`, fixed `0.56.0`.
- `CVE-2026-78669` HIGH: `golang.org/x/net` installed `v0.55.0`, fixed `0.60.0`.
- `CVE-2026-56851` HIGH: `golang.org/x/text` installed `v0.37.0`, fixed `0.41.0`.
- `CVE-2026-56852` HIGH: `golang.org/x/text` installed `v0.37.0`, fixed `0.39.0`.
- `CVE-2026-84304` HIGH: `google.golang.org/grpc` installed `v1.81.0`, fixed `1.83.1`.
- `CVE-2026-84445` HIGH: `google.golang.org/grpc` installed `v1.81.0`, fixed `1.82.2, 1.83.2, 1.84.0-dev.0.20260825144003-d5a41119e0e3, 1.85.0-dev.0.20260825072537-93e31b48545e`.
- `GHSA-hrxh-6v49-42gf` HIGH: `google.golang.org/grpc` installed `v1.81.0`, fixed `1.82.1`.
- `CVE-2026-111943` HIGH: `stdlib` installed `v1.26.3`, fixed `1.26.10, 1.27.3`.
- `CVE-2026-27145` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.11, 1.26.4`.
- `CVE-2026-33818` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-39821` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-39822` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.12, 1.26.5, 1.27.0-rc.2`.
- `CVE-2026-42504` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.11, 1.26.4`.
- `CVE-2026-46600` HIGH: `stdlib` installed `v1.26.3`, fixed `1.26.6, 1.27.0-rc.3`.
- `CVE-2026-56853` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-56858` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-56859` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-56860` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-56862` HIGH: `stdlib` installed `v1.26.3`, fixed `1.25.13, 1.26.6, 1.27.0-rc.3`.
- `CVE-2026-78667` HIGH: `stdlib` installed `v1.26.3`, fixed `1.26.9, 1.27.2`.
- `CVE-2026-78669` HIGH: `stdlib` installed `v1.26.3`, fixed `1.26.9, 1.27.2`.
- `CVE-2026-97031` HIGH: `stdlib` installed `v1.26.3`, fixed `1.26.9, 1.27.2`.

## Interpretation boundary

The 3 OS entries are CVE-2026-111497 CRITICAL for Alpine `brotli-libs` 1.2.0-r0 and CVE-2026-92392 HIGH reported separately for `curl` and `libcurl` 8.22.0-r0. The other 26 entries are findings attached by Trivy to the Go binary `/usr/bin/caddy`, including Go standard library and bundled modules. This is a policy failure for the edge artifact; no applicability or remediation assessment was performed in this increment. No finding was ignored, suppressed or severity-adjusted.
