#!/usr/bin/env bash
set -euo pipefail

SMARTENGINE_VERSION="${SMARTENGINE_VERSION:-v4.0.2}"
SMARTENGINE_REPO="${SMARTENGINE_REPO:-https://github.com/AuraBootTeam/SmartEngine.git}"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

echo "Installing SmartEngine ${SMARTENGINE_VERSION} from ${SMARTENGINE_REPO} into Maven local"
git clone --depth 1 --branch "$SMARTENGINE_VERSION" "$SMARTENGINE_REPO" "$tmp_dir/SmartEngine"

maven_repo_args=()
if [[ -n "${MAVEN_REPO_LOCAL:-}" ]]; then
  maven_repo_args+=("-Dmaven.repo.local=${MAVEN_REPO_LOCAL}")
fi

mvn -B -ntp -DskipTests \
  "${maven_repo_args[@]}" \
  -pl extension/storage/storage-custom,extension/storage/storage-mysql \
  -am install \
  -f "$tmp_dir/SmartEngine/pom.xml"
