#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
java_destination="${1:?usage: verify-and-extract.sh JAVA_DESTINATION METADATA_DESTINATION}"
metadata_destination="${2:?usage: verify-and-extract.sh JAVA_DESTINATION METADATA_DESTINATION}"
# release.env is version-controlled with the detached signature and checksum.
# shellcheck source=release.env
source "${script_dir}/release.env"

work_dir="$(mktemp -d)"
trap 'rm -rf "${work_dir}"' EXIT
archive_path="${work_dir}/${TEMURIN_ARCHIVE}"
curl --fail --location --retry 3 --silent --show-error "${TEMURIN_RELEASE_URL}" --output "${archive_path}"
(cd "${work_dir}" && sha256sum --check "${script_dir}/${TEMURIN_ARCHIVE}.sha256.txt")

export GNUPGHOME="${work_dir}/gnupg"
mkdir -m 0700 "${GNUPGHOME}"
gpg --batch --import "${script_dir}/adoptium-release-key.asc"
observed_fingerprint="$(gpg --batch --with-colons --fingerprint "${TEMURIN_SIGNER_FINGERPRINT}" \
    | awk -F: '$1 == "fpr" { print $10; exit }')"
test "${observed_fingerprint}" = "${TEMURIN_SIGNER_FINGERPRINT}"
gpg --batch --verify "${script_dir}/${TEMURIN_ARCHIVE}.sig" "${archive_path}"

mkdir -p "${java_destination}" "$(dirname -- "${metadata_destination}")"
tar --extract --gzip --file "${archive_path}" --strip-components=1 --directory "${java_destination}"
java_version="$("${java_destination}/bin/java" -version 2>&1)"
grep -Fq "Temurin-${TEMURIN_VERSION} (build ${TEMURIN_VERSION}-LTS)" <<<"${java_version}"
archive_sha256="$(awk 'NR == 1 { print $1 }' "${script_dir}/${TEMURIN_ARCHIVE}.sha256.txt")"
{
  printf 'TemurinArtifact=org.eclipse.temurin:jre:%s\n' "${TEMURIN_VERSION}"
  printf 'TemurinReleaseURL=%s\n' "${TEMURIN_RELEASE_URL}"
  printf 'TemurinArchive=%s\n' "${TEMURIN_ARCHIVE}"
  printf 'TemurinSHA256=%s\n' "${archive_sha256}"
  printf 'TemurinSignerFingerprint=%s\n' "${TEMURIN_SIGNER_FINGERPRINT}"
  printf 'JavaVersion=%s\n' "$("${java_destination}/bin/java" -version 2>&1 | sed -n '1p')"
  printf 'JavaRuntimeVersion=%s\n' "${TEMURIN_VERSION}-LTS"
} >"${metadata_destination}"
chmod 0644 "${metadata_destination}"
cat "${metadata_destination}"
