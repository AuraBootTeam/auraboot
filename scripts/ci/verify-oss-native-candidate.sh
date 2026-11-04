#!/usr/bin/env bash
# Build/export/unpack an unpublished native candidate through existing contracts.
set -Eeuo pipefail
: "${AURA_CI_JOB_ID:?run through the admitted CI control plane}"
: "${AURA_REGRESSION_ARTIFACTS:?owned evidence directory required}"
: "${AURA_CI_WORKSPACE_ROOT:?frozen source workspace required}"
: "${AURA_CI_FROZEN_CONTEXT:?frozen source context required}"
: "${AURA_CI_SOURCE_SET_DIGEST:?frozen source digest required}"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || exit 2
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"
ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd -P)"
COMMIT="$(git rev-parse HEAD)"
FREEZE="$AURA_CI_WORKSPACE_ROOT/auraboot-workspace/tools/local-ci/server.mjs"
freeze() {
  node "$FREEZE" verify-freeze --context "$AURA_CI_FROZEN_CONTEXT" \
    --expected-source-set "$AURA_CI_SOURCE_SET_DIGEST" \
    --workspace-root "$AURA_CI_WORKSPACE_ROOT" --job "$AURA_CI_JOB_ID" \
    > "$ARTIFACTS/native-frozen-$1.json"
}
REUSE_COMMIT=""
if [[ "$#" -gt 0 ]]; then
  [[ "$#" == 2 && "$1" == --consume-candidate && "$2" =~ ^[a-f0-9]{40}$ ]] || exit 2
  REUSE_COMMIT="$2"
fi
freeze before
CI=1 pnpm install --frozen-lockfile --ignore-scripts
if [[ -n "${AURA_PRODUCT_PLATFORM_ARCHIVE:-}" ]]; then
  : "${AURA_CI_DATASET_INPUTS:?original previous-candidate dataset receipt required}"
  node scripts/application/platform-artifact-package.mjs consume \
    "$AURA_PRODUCT_PLATFORM_ARCHIVE" "$AURA_CI_DATASET_INPUTS" "$ARTIFACTS/previous" \
    640057f2a3ee08c7cc581202781a3be49bb409ae > "$ARTIFACTS/previous-consumption.json"
fi
if [[ -n "$REUSE_COMMIT" ]]; then
  CANDIDATE_ARCHIVE="$(node -e "const r=require(process.argv[1]);const f=r.files.filter(x=>x.id==='oss-install-candidate');if(f.length!==1)throw Error('One frozen selected candidate required');process.stdout.write(f[0].file)" "$AURA_CI_DATASET_INPUTS")"
  node scripts/application/platform-artifact-package.mjs consume \
    "$CANDIDATE_ARCHIVE" "$AURA_CI_DATASET_INPUTS" "$ARTIFACTS/unpacked" "$REUSE_COMMIT" \
    > "$ARTIFACTS/current-consumption.json"
  cmp LICENSE.txt "$ARTIFACTS/unpacked/LICENSE.txt"
  cmp NOTICE "$ARTIFACTS/unpacked/NOTICE"
else
AURA_OCI_BUILDER=docker node scripts/application/stage-core-only-artifacts.mjs \
  --repo-root "$ROOT" --output "$ARTIFACTS/platform"
cmp LICENSE.txt "$ARTIFACTS/platform/LICENSE.txt"
cmp NOTICE "$ARTIFACTS/platform/NOTICE"
node scripts/application/platform-artifact-package.mjs export \
  "$ARTIFACTS/platform" "$COMMIT" "$ARTIFACTS/platform-artifacts.tar" \
  > "$ARTIFACTS/platform-package.json"
sha256sum "$ARTIFACTS/platform-artifacts.tar" > "$ARTIFACTS/platform-artifacts.sha256"
ARCHIVE_SHA="$(cut -d' ' -f1 "$ARTIFACTS/platform-artifacts.sha256")"
node scripts/application/platform-artifact-package.mjs unpack \
  "$ARTIFACTS/platform-artifacts.tar" "$ARCHIVE_SHA" "$ARTIFACTS/unpacked" "$COMMIT" \
  > "$ARTIFACTS/platform-unpack.json"
cmp LICENSE.txt "$ARTIFACTS/unpacked/LICENSE.txt"
cmp NOTICE "$ARTIFACTS/unpacked/NOTICE"
  CANDIDATE_ARCHIVE="$ARTIFACTS/platform-artifacts.tar"
fi
security_status=0
installation_status=0
# Collect both real execution and artifact findings; either failure keeps the gate red.
python3 -B scripts/ci/oss-artifact-security.py "$ARTIFACTS/unpacked" "$ARTIFACTS/security" || security_status=$?
if [[ -n "${AURA_PRODUCT_PLATFORM_ARCHIVE:-}" ]]; then
  python3 -B scripts/ci/oss-native-install.py "$ARTIFACTS/unpacked" "$ARTIFACTS/previous" "$ARTIFACTS/installation" "$CANDIDATE_ARCHIVE" || installation_status=$?
fi
freeze after
cmp "$ARTIFACTS/native-frozen-before.json" "$ARTIFACTS/native-frozen-after.json"
[[ "$security_status" == 0 && "$installation_status" == 0 ]]
