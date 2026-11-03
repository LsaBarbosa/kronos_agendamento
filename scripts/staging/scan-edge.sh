#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/../.." && pwd)
runner_log=$(mktemp /tmp/agd003-edge-runner.XXXXXX)
cache_dir=$(mktemp -d /tmp/agd003-trivy-cache.XXXXXX)
image_tags=()
report_dir=

cleanup() {
  if [[ -f "$runner_log" ]]; then
    while IFS= read -r tag; do
      [[ -n "$tag" ]] && image_tags+=("$tag")
    done < <(sed -n 's/^Built image tag: [A-Z_]*=//p' "$runner_log")
  fi
  if ((${#image_tags[@]})); then
    docker image rm "${image_tags[@]}" >/dev/null 2>&1 || true
  fi
  if [[ -n "$report_dir" && -d "$report_dir" ]]; then rmdir --ignore-fail-on-non-empty "$report_dir" 2>/dev/null || true; fi
  rm -f -- "$runner_log"
  rm -rf -- "$cache_dir"
}
trap cleanup EXIT

command -v docker >/dev/null
docker pull aquasec/trivy:0.75.0 >/dev/null
docker run --rm aquasec/trivy:0.75.0 --version
scanner_image_id=$(docker image inspect --format '{{.Id}}' aquasec/trivy:0.75.0)
scanner_repo_digest=$(docker image inspect --format '{{index .RepoDigests 0}}' aquasec/trivy:0.75.0)

if ! "$repo_root/scripts/staging/run-disposable.sh" --keep-images >"$runner_log" 2>&1; then
  echo "HTTPS/OIDC smoke failed before edge scan; disposable run output follows (generated values are redacted by the runner)." >&2
  grep -E '^(ERROR:|PASS:|Built image identity:|Built image tag:|Disposable project:)' "$runner_log" >&2 || true
  exit 1
fi
grep '^PASS:' "$runner_log"

edge_id=$(sed -n 's/^Built image identity: STAGING_EDGE_IMAGE=//p' "$runner_log" | tail -1)
edge_tag=$(sed -n 's/^Built image tag: STAGING_EDGE_IMAGE=//p' "$runner_log" | tail -1)
[[ "$edge_id" =~ ^sha256:[0-9a-f]{64}$ ]]
[[ -n "$edge_tag" ]]
[[ "$(docker image inspect --format '{{.Id}}' "$edge_tag")" == "$edge_id" ]]

short_id=${edge_id#sha256:}
short_id=${short_id:0:16}
report_dir="$repo_root/docs/handoffs/AGD-003-edge-scan-$short_id"
mkdir "$report_dir"
chmod 0755 "$report_dir"
docker image inspect aquasec/trivy:0.75.0 >"$report_dir/scanner-image-inspect.json"

echo "Smoke-tested edge image: $edge_id"
echo "Scanner database refresh: Trivy 0.75.0, isolated cache, current vulnerability DB"
docker run --rm --user "$(id -u):$(id -g)" -v "$cache_dir:/work" -e TRIVY_CACHE_DIR=/work \
  aquasec/trivy:0.75.0 image --download-db-only
test -s "$cache_dir/db/metadata.json"
cp "$cache_dir/db/metadata.json" "$report_dir/database-metadata.json"

docker save "$edge_tag" -o "$cache_dir/edge-image.tar"
image_tar_sha256=$(sha256sum "$cache_dir/edge-image.tar" | awk '{print $1}')

set +e
docker run --rm --user "$(id -u):$(id -g)" \
  -v "$cache_dir:/work" -v "$report_dir:/reports" -e TRIVY_CACHE_DIR=/work \
  aquasec/trivy:0.75.0 image --input /work/edge-image.tar --scanners vuln \
  --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1 \
  --format json --output "/reports/trivy-report.json"
scan_status=$?
set -e
test -s "$report_dir/trivy-report.json"

docker run --rm --user "$(id -u):$(id -g)" \
  -v "$cache_dir:/work" -v "$report_dir:/reports" -e TRIVY_CACHE_DIR=/work \
  aquasec/trivy:0.75.0 image --input /work/edge-image.tar --scanners vuln \
  --format cyclonedx --output /reports/inventory.cdx.json

docker image inspect "$edge_id" >"$report_dir/image-inspect.json"
docker run --rm --entrypoint /bin/sh "$edge_id" -c \
  'cat /etc/os-release; printf "\\n-- package inventory --\\n"; apk info -vv | sort' \
  >"$report_dir/os-and-packages.txt"

python3 - "$report_dir" "$edge_id" "$edge_tag" "$scan_status" "$image_tar_sha256" \
  "$scanner_image_id" "$scanner_repo_digest" <<'PY'
import json, pathlib, sys

directory = pathlib.Path(sys.argv[1])
image_id, image_tag, scan_status, image_tar_sha256 = sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5]
scanner_image_id, scanner_repo_digest = sys.argv[6], sys.argv[7]
report = json.loads((directory / "trivy-report.json").read_text())
sbom = json.loads((directory / "inventory.cdx.json").read_text())
metadata = json.loads((directory / "database-metadata.json").read_text())
image = json.loads((directory / "image-inspect.json").read_text())[0]
vulnerabilities = [v for result in report.get("Results", []) for v in result.get("Vulnerabilities", [])]
components = sorted(sbom.get("components", []), key=lambda x: (x.get("name", ""), x.get("version", "")))
inventory_lines = [f"{item.get('name', '(unnamed)')}\t{item.get('version', '(no version)')}\t{item.get('purl') or '(no purl)'}"
                   for item in components]
(directory / "inventory.tsv").write_text("name\tversion\tpurl\n" + "\n".join(inventory_lines) + "\n")
lines = [
    "# AGD-003 edge image scan",
    "",
    f"- Image ID: `{image_id}`",
    f"- Tag used for smoke and scan: `{image_tag}`",
    f"- Docker image tar SHA-256 scanned by Trivy: `{image_tar_sha256}`.",
    f"- Platform: `{image.get('Os')}/{image.get('Architecture')}`",
    f"- Configured user: `{image.get('Config', {}).get('User', '')}`",
    "- Scanner: Trivy 0.75.0 official container `aquasec/trivy:0.75.0`.",
    f"- Scanner image ID: `{scanner_image_id}`; registry digest `{scanner_repo_digest}`.",
    "- Policy: `image --input edge-image.tar --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed=false --exit-code 1`; no ignore file or suppression flags were supplied.",
    f"- Vulnerability database: version `{metadata.get('Version')}`, updated `{metadata.get('UpdatedAt')}`, next update `{metadata.get('NextUpdate')}`, downloaded `{metadata.get('DownloadedAt')}`.",
    f"- CycloneDX component count: {len(components)}; full inventory: `inventory.tsv` and `inventory.cdx.json`.",
    f"- HIGH/CRITICAL findings: {len(vulnerabilities)}.",
    f"- Scanner exit status: {scan_status} (0 means no policy-level findings; 1 means findings were reported).",
    "- Exact machine-readable report: `trivy-report.json`; database metadata: `database-metadata.json`; image configuration: `image-inspect.json`; OS/package inventory: `os-and-packages.txt`.",
    "- The HTTPS/OIDC smoke ran before scanning in the same disposable runner, and printed this same immutable Image ID. The scanner read a `docker save` archive from that tag after verifying the tag resolves to this ID. The runner retained uniquely tagged images only until scan/report generation; this script then removed only those unique tags.",
    "",
    "## Findings",
    "",
]
if vulnerabilities:
    for item in vulnerabilities:
        lines.append(f"- `{item.get('VulnerabilityID')}` {item.get('Severity')}: `{item.get('PkgName')}` "
                     f"installed `{item.get('InstalledVersion')}`, fixed `{item.get('FixedVersion') or 'not listed'}`.")
else:
    lines.append("No HIGH/CRITICAL vulnerability findings were reported by this scan.")
(directory / "summary.md").write_text("\n".join(lines) + "\n")
print(f"Trivy report: {directory / 'trivy-report.json'}")
print(f"CycloneDX inventory: {directory / 'inventory.cdx.json'} ({len(components)} components)")
print(f"Database metadata: {directory / 'database-metadata.json'}")
print(f"HIGH/CRITICAL findings: {len(vulnerabilities)}; scan exit status: {scan_status}")
PY

echo "Full edge scan artifacts: $report_dir"
exit "$scan_status"
