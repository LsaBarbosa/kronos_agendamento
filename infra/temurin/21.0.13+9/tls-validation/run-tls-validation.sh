#!/usr/bin/env bash
set -euo pipefail

image_id="${1:?usage: run-tls-validation.sh IMAGE_ID EXPECTED_USER [JAVA_HOME]}"
expected_user="${2:?usage: run-tls-validation.sh IMAGE_ID EXPECTED_USER [JAVA_HOME]}"
expected_java_home="${3:-/opt/java/openjdk}"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
tmp_dir="$(mktemp -d /tmp/temurin-tls-validation.XXXXXX)"
trap 'rm -rf "${tmp_dir}"' EXIT

observed_image_id="$(docker image inspect "${image_id}" --format '{{.Id}}')"
if [[ "${observed_image_id}" != "${image_id}" ]]; then
  echo "Image reference did not resolve to the requested immutable ID: ${observed_image_id}" >&2
  exit 2
fi
image_user="$(docker image inspect "${image_id}" --format '{{.Config.User}}')"
image_java_home="$(docker image inspect "${image_id}" --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | sed -n 's/^JAVA_HOME=//p')"
if [[ "${image_user}" != "${expected_user}" || "${image_java_home}" != "${expected_java_home}" ]]; then
  echo "Unexpected image runtime: user=${image_user}; JAVA_HOME=${image_java_home}" >&2
  exit 2
fi

runtime_user="$(docker run --rm --entrypoint /bin/sh "${image_id}" \
  -c 'printf "%s:%s\n" "$(id -u)" "$(id -g)"')"
if [[ "${runtime_user}" != "${expected_user}" ]]; then
  echo "Container did not run with its configured identity: ${runtime_user}" >&2
  exit 2
fi

mkdir -p "${tmp_dir}/classes"
javac --release 21 -d "${tmp_dir}/classes" "${script_dir}/TlsTrustStoreProbe.java"
chmod -R a+rX "${tmp_dir}"

printf 'ImageId=%s\nImageUser=%s\nJAVA_HOME=%s\n' "${observed_image_id}" "${image_user}" "${image_java_home}"
docker run --rm \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  --cap-drop=ALL \
  --security-opt=no-new-privileges \
  --mount "type=bind,src=${tmp_dir}/classes,dst=/validation,readonly" \
  --entrypoint "${image_java_home}/bin/java" \
  "${image_id}" \
  -cp /validation TlsTrustStoreProbe
