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
freeze before
CI=1 pnpm install --frozen-lockfile --ignore-scripts
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
freeze after
cmp "$ARTIFACTS/native-frozen-before.json" "$ARTIFACTS/native-frozen-after.json"
