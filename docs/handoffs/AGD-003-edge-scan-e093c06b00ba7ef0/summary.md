# AGD-003 edge image scan

- Image ID: `sha256:e093c06b00ba7ef00925ce4c71a4fd32cea51a4af697ae3830aa216462c1e890`
- Tag used for smoke and scan: `kronos-staging-agd003-https-1793716099-548490-b079fa-edge:run`
- Docker image tar SHA-256 scanned by Trivy: `b9e3874fde61d8b0daf875ed70cddde97bc8500988161f15270081e342961ed2`.
- Platform: `linux/amd64`
- Configured user: `1000:1000`
- Scanner: Trivy 0.75.0 official container `aquasec/trivy:0.75.0`.
- Scanner image ID: `sha256:6d93f1ec925b37c5ce785e416b86cba056e10c8c2914a59a8ea7be18115d6764`; registry digest `aquasec/trivy@sha256:af6acf9a6b85dfe389a1941505c0ce9efef52a4719635e1a962f022a3d855daa`.
- Policy: `image --input edge-image.tar --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; no ignore file or suppression flags were supplied.
- Vulnerability database: version `2`, updated `2026-11-03T13:13:42.304243913Z`, next update `2026-11-04T13:13:42.304243562Z`, downloaded `2026-11-03T14:29:53.612961706Z`.
- CycloneDX component count: 22; full inventory: `inventory.tsv` and `inventory.cdx.json`.
- HIGH/CRITICAL findings: 0.
- Scanner exit status: 0 (0 means no policy-level findings; 1 means findings were reported).
- Exact machine-readable report: `trivy-report.json`; database metadata: `database-metadata.json`; image configuration: `image-inspect.json`; OS/package inventory: `os-and-packages.txt`.
- The HTTPS/OIDC smoke ran before scanning in the same disposable runner, and printed this same immutable Image ID. The scanner read a `docker save` archive from that tag after verifying the tag resolves to this ID. The runner retained uniquely tagged images only until scan/report generation; this script then removed only those unique tags.

## Findings

No HIGH/CRITICAL vulnerability findings were reported by this scan.
