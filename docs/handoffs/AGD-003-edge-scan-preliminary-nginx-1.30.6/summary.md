# AGD-003 NGINX preliminary candidate scan

This is the preliminary scan of the unmodified official NGINX base image. It is not the final frontend edge artifact and is not, by itself, approval of the runtime.

- Official image reference: `nginx:1.30.6-alpine3.24-slim`.
- Multi-platform index digest: `sha256:dede8895648b461178c3bc604f7eed4423efe5220b2c4d839f5eb3d34ac3f8f7`.
- Platform inspected: `linux/amd64`; platform image ID: `sha256:bfdf0fe001a54d0f67ce359b7d7b57a0d141328dd94236cd1a4820421f5f811f`.
- Image reports NGINX `1.30.6`, Alpine `3.24.3`, APK `nginx-1.30.6-r1`; `os-and-packages.txt` records all 21 installed APK packages. No Perl/OTel variant was selected.
- The image was scanned as a saved image archive; tar SHA-256 is recorded in `image-tar.sha256`.
- Scanner: official Trivy `0.75.0` container. Policy: `image --input nginx.tar --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; no ignore file or suppressions.
- Trivy recognized `/work/nginx.tar` as Alpine `3.24.3`, target type `alpine`, and 21 OS packages including `nginx-1.30.6-r1`; no language-specific files were detected, as expected for this packaged C server.
- Vulnerability DB: `database-metadata.json`; exit status `0`; HIGH/CRITICAL findings `0`. Machine-readable report: `trivy-report.json`; CycloneDX inventory: `inventory.cdx.json`.
- The image inspect, installed package inventory, full scanner report, CycloneDX SBOM, database metadata, and archive hash are retained beside this file.

The separate final image scan is `../AGD-003-edge-scan-e093c06b00ba7ef0/summary.md`. Only that final image was built with the application bundle, exercised by the complete HTTPS/OIDC smoke, and scanned as the same immutable Image ID.

Official sources: [NGINX official image tag/digest](https://hub.docker.com/layers/library/nginx/stable-alpine-slim), [Docker Official Images tag metadata](https://github.com/docker-library/official-images/blob/master/library/nginx), and [NGINX 1.30.6 stable release notes](https://nginx.org/2026.html).
