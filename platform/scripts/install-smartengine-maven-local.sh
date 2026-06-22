#!/usr/bin/env bash
set -euo pipefail

SMARTENGINE_VERSION="${SMARTENGINE_VERSION:-4.0.2}"
SMARTENGINE_REF="${SMARTENGINE_REF:-v${SMARTENGINE_VERSION}}"
SMARTENGINE_REPO_URL="${SMARTENGINE_REPO_URL:-https://github.com/AuraBootTeam/SmartEngine.git}"
MAVEN_REPO_LOCAL="${MAVEN_REPO_LOCAL:-${HOME}/.m2/repository}"

tmp_dir=""
cleanup() {
  if [[ -n "${tmp_dir}" && -d "${tmp_dir}" ]]; then
    rm -rf "${tmp_dir}"
  fi
}
trap cleanup EXIT

prepare_from_local_source() {
  local source_root="$1"
  if [[ ! -d "${source_root}" ]]; then
    echo "[smartengine] SMARTENGINE_SOURCE_DIR does not exist: ${source_root}" >&2
    exit 1
  fi

  if git -C "${source_root}" rev-parse --verify "${SMARTENGINE_REF}^{commit}" >/dev/null 2>&1; then
    tmp_dir="$(mktemp -d)"
    mkdir -p "${tmp_dir}/SmartEngine"
    git -C "${source_root}" archive "${SMARTENGINE_REF}" | tar -x -C "${tmp_dir}/SmartEngine"
    echo "${tmp_dir}/SmartEngine"
    return
  fi

  echo "[smartengine] Using SMARTENGINE_SOURCE_DIR as-is; git ref ${SMARTENGINE_REF} was not found locally." >&2
  echo "${source_root}"
}

if [[ -n "${SMARTENGINE_SOURCE_DIR:-}" ]]; then
  smartengine_dir="$(prepare_from_local_source "${SMARTENGINE_SOURCE_DIR}")"
else
  tmp_dir="$(mktemp -d)"
  git clone --depth 1 --branch "${SMARTENGINE_REF}" "${SMARTENGINE_REPO_URL}" "${tmp_dir}/SmartEngine"
  smartengine_dir="${tmp_dir}/SmartEngine"
fi

if ! grep -q "<version>${SMARTENGINE_VERSION}</version>" "${smartengine_dir}/pom.xml"; then
  echo "[smartengine] ${smartengine_dir}/pom.xml does not declare version ${SMARTENGINE_VERSION}" >&2
  exit 1
fi

echo "[smartengine] Installing ${SMARTENGINE_REF} into ${MAVEN_REPO_LOCAL}"
mvn -B -ntp \
  -Dmaven.repo.local="${MAVEN_REPO_LOCAL}" \
  -DskipTests \
  -Dgpg.skip=true \
  -Dmaven.javadoc.skip=true \
  -f "${smartengine_dir}/pom.xml" \
  install

for artifact in smart-engine-extension-storage-mysql smart-engine-extension-storage-custom; do
  jar_path="${MAVEN_REPO_LOCAL}/com/auraboot/smart/framework/${artifact}/${SMARTENGINE_VERSION}/${artifact}-${SMARTENGINE_VERSION}.jar"
  if [[ ! -f "${jar_path}" ]]; then
    echo "[smartengine] Missing installed artifact: ${jar_path}" >&2
    exit 1
  fi
done

echo "[smartengine] SmartEngine ${SMARTENGINE_VERSION} artifacts are available in Maven local."
