#!/usr/bin/env bash
set -Eeuo pipefail

# CI-only release archive stage for the generic product release-image gate.
#
# This runner never builds or smokes anything. It seals the artifacts of a
# completed `run-product-release-image-gate.sh` attempt into the deployment
# inputs that a release publisher requires: the offline image archive, the
# deploy control bundle and a schemaVersion-3 release-archive receipt.
# Daily development stays host-first; Linux CI Docker remains the only
# packaging authority, exactly as for the gate itself.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CORE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
RECEIPT_TOOL="$CORE_ROOT/scripts/application/product_release_archive_receipt.py"

fatal() { printf 'product-release-archive-stage: %s\n' "$*" >&2; exit 2; }
info() { printf '==> %s\n' "$*"; }
need() { command -v "$1" >/dev/null 2>&1 || fatal "missing dependency: $1"; }

: "${AURA_PRODUCT_ID:?AURA_PRODUCT_ID is required}"
: "${AURA_PRODUCT_SOURCE_KEY:?AURA_PRODUCT_SOURCE_KEY is required}"
: "${AURA_PRODUCT_PROJECT_ROOT:?AURA_PRODUCT_PROJECT_ROOT is required}"
: "${AURA_CI_JOB_ID:?AURA_CI_JOB_ID is required; run through the local CI control plane}"
: "${AURA_REGRESSION_ARTIFACTS:?AURA_REGRESSION_ARTIFACTS is required}"
: "${AURA_CI_BUILDER_ID:?AURA_CI_BUILDER_ID is required}"
: "${AURA_CI_FROZEN_CONTEXT:?AURA_CI_FROZEN_CONTEXT is required}"
: "${AURA_CI_SOURCE_SET_DIGEST:?AURA_CI_SOURCE_SET_DIGEST is required}"

# Keep this list in lockstep with ADMITTED_PRODUCTS in
# product_release_archive_receipt.py; a product without an admitted receipt
# contract must never reach the archive stage.
case "$AURA_PRODUCT_ID" in
  quote-bom) fatal "the quote-bom product line owns a dedicated archive contract" ;;
  aura-bpm) ;;
  *) fatal "unsupported product archive contract: $AURA_PRODUCT_ID" ;;
esac

MUTATION="${AURA_RELEASE_MUTATION:-}"
case "$MUTATION" in
  ''|archive-checksum-byte) ;;
  *) fatal "unsupported archive mutation: $MUTATION" ;;
esac

FREEZE_TOOL="$AURA_CI_WORKSPACE_ROOT/tools/local-ci/server.mjs"
: "${AURA_CI_RELEASE_STORE:=/opt/aura-ci/state/releases/$AURA_PRODUCT_ID}"
WORK_PARENT="${AURA_CI_RELEASE_WORK_ROOT:-/opt/aura-ci/state/release-image-work}"
LOCK_PARENT="${AURA_CI_RELEASE_LOCK_ROOT:-/opt/aura-ci/state/locks}"

for command_name in docker git gzip node openssl python3 sha256sum tar; do need "$command_name"; done
[[ "$(uname -s)" == "Linux" ]] || fatal "release archives must be sealed on the admitted Linux CI host"
[[ "$(uname -m)" == "x86_64" ]] || fatal "release archive builder must be x86_64"
docker version >/dev/null 2>&1 || fatal "docker daemon is required"

[[ -d "$AURA_PRODUCT_PROJECT_ROOT/.git" || -f "$AURA_PRODUCT_PROJECT_ROOT/.git" ]] \
  || fatal "product source checkout missing: $AURA_PRODUCT_PROJECT_ROOT"
[[ -z "$(git -C "$AURA_PRODUCT_PROJECT_ROOT" status --porcelain --untracked-files=all)" ]] \
  || fatal "product source checkout is dirty: $AURA_PRODUCT_PROJECT_ROOT"

ARTIFACTS="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd)"
mkdir -p "$ARTIFACTS/logs"
mkdir -p "$WORK_PARENT" "$LOCK_PARENT"
LOCK_DIR="$LOCK_PARENT/$AURA_PRODUCT_ID-release-archive.lock"
mkdir "$LOCK_DIR" 2>/dev/null || fatal "another release-archive stage owns $LOCK_DIR"
LOCK_TOKEN="$(openssl rand -hex 16)"
printf '%s\n' "$LOCK_TOKEN" > "$LOCK_DIR/owner"

cleanup() {
  local status=$?
  if [[ -d "$LOCK_DIR" && "$(cat "$LOCK_DIR/owner" 2>/dev/null || true)" == "$LOCK_TOKEN" ]]; then
    rm -f "$LOCK_DIR/owner"
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# The archive stage must observe the same frozen source set as the gate attempt
# whose artifacts it seals, before and after it touches the daemon or bytes.
node "$FREEZE_TOOL" verify-freeze --context "$AURA_CI_FROZEN_CONTEXT" \
  --expected-source-set "$AURA_CI_SOURCE_SET_DIGEST" \
  --workspace-root "$AURA_CI_WORKSPACE_ROOT" --job "$AURA_CI_JOB_ID" \
  > "$ARTIFACTS/archive-frozen-inputs-before.json" || fatal "frozen inputs verification failed before archive stage"

GATE_RECEIPT="$ARTIFACTS/release-image-receipt.json"
[[ -f "$GATE_RECEIPT" && ! -L "$GATE_RECEIPT" ]] || fatal "gate receipt missing; run the product release-image gate first"
node -e '
const receipt = require(process.argv[1]);
if (receipt.schemaVersion !== 1 || receipt.status !== "PASS") process.exit(1);
if (receipt.product !== process.argv[2]) process.exit(2);
if (receipt.job !== process.argv[3]) process.exit(5);
if (!/^sha256:[0-9a-f]{64}$/.test(receipt.loadedImageId || "")) process.exit(3);
if (!/^[0-9a-f]{40}$/.test(receipt.coreCommit || "") || !/^[0-9a-f]{40}$/.test(receipt.productCommit || "")) process.exit(4);
' "$GATE_RECEIPT" "$AURA_PRODUCT_ID" "$AURA_CI_JOB_ID" \
  || fatal "gate receipt is not a PASS receipt of this product attempt"

CORE_SHA="$(node -p 'require(process.argv[1]).coreCommit' "$GATE_RECEIPT")"
PRODUCT_SHA="$(node -p 'require(process.argv[1]).productCommit' "$GATE_RECEIPT")"
IMAGE_ID="$(node -p 'require(process.argv[1]).loadedImageId' "$GATE_RECEIPT")"
RELEASE_VERSION="${RELEASE_VERSION:-$AURA_PRODUCT_ID-c${CORE_SHA:0:7}-p${PRODUCT_SHA:0:7}}"
IMAGE_ARCHIVE="$ARTIFACTS/images-$AURA_PRODUCT_ID-$RELEASE_VERSION-linux-amd64.tar.gz"
CONTROL_BUNDLE="$ARTIFACTS/control-$AURA_PRODUCT_ID-$RELEASE_VERSION.tar.gz"
REFS_FILE="$ARTIFACTS/image-refs.env"

release_store_final="$AURA_CI_RELEASE_STORE/$RELEASE_VERSION"
release_store_tmp="$AURA_CI_RELEASE_STORE/.$RELEASE_VERSION.$AURA_CI_JOB_ID.tmp"
if [[ -z "$MUTATION" ]]; then
  [[ ! -e "$release_store_final" && ! -L "$release_store_final" ]] \
    || fatal "release store entry already exists and is immutable: $release_store_final"
fi

docker image inspect "$IMAGE_ID" >/dev/null 2>&1 \
  || fatal "verified release image is no longer present in the daemon: $IMAGE_ID"

info "saving the exact verified image as $IMAGE_ARCHIVE"
docker save "$IMAGE_ID" | gzip > "$IMAGE_ARCHIVE" \
  || fatal "offline image archive could not be created"

if [[ "$MUTATION" == "archive-checksum-byte" ]]; then
  python3 - "$IMAGE_ARCHIVE" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
data = bytearray(p.read_bytes())
data[-1] ^= 0x01
p.write_bytes(data)
PY
  if gzip -t "$IMAGE_ARCHIVE" 2>"$ARTIFACTS/logs/archive-mutation-rejection.log"; then
    fatal "controlled archive mutation was not rejected"
  fi
  docker rmi "$IMAGE_ID" >/dev/null 2>&1 || true
  cat > "$ARTIFACTS/mutation-receipt.json" <<JSON
{"schemaVersion":1,"verdict":"EXPECTED_RED","mutation":"archive-checksum-byte","guard":"gzip-integrity","product":"$AURA_PRODUCT_ID","releaseId":"$RELEASE_VERSION","sourceSetDigest":"$AURA_CI_SOURCE_SET_DIGEST"}
JSON
  printf 'product-release-archive-stage: EXPECTED_RED archive checksum mutation rejected\n' >&2
  exit 42
fi

gzip -t "$IMAGE_ARCHIVE"

# Prove that the deployable archive, rather than the residual builder image, can
# restore the exact verified image ID. The image is owned by this serialized
# stage; removing it cannot address unrelated images.
docker rmi "$IMAGE_ID" >/dev/null
gzip -dc "$IMAGE_ARCHIVE" | docker load >/dev/null \
  || fatal "archive reload failed"
RELOADED_ID="$(docker image inspect "$IMAGE_ID" --format '{{.Id}}' 2>/dev/null || true)"
[[ "$RELOADED_ID" == "$IMAGE_ID" ]] || fatal "archive reload did not restore the verified image ID"

LAYOUT_DIGEST="$(node -e '
const fs = require("node:fs");
const receipt = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
process.stdout.write((receipt.image && receipt.image.digest) || "");
' "$ARTIFACTS/product-build-receipt.json")"
[[ "$LAYOUT_DIGEST" =~ ^sha256:[0-9a-f]{64}$ ]] \
  || fatal "product build receipt lacks an immutable OCI layout digest"
{
  printf 'IMAGE_ID=%s\n' "$IMAGE_ID"
  printf 'OCI_LAYOUT_DIGEST=%s\n' "$LAYOUT_DIGEST"
} > "$REFS_FILE"

for bundled in application.lock artifact-catalog.json sbom.cdx.json \
  core-build-receipt.json product-build-receipt.json; do
  [[ -f "$ARTIFACTS/$bundled" && ! -L "$ARTIFACTS/$bundled" ]] \
    || fatal "control bundle input missing from gate artifacts: $bundled"
done
tar -C "$ARTIFACTS" -czf "$CONTROL_BUNDLE" \
  application.lock artifact-catalog.json sbom.cdx.json \
  core-build-receipt.json product-build-receipt.json image-refs.env release-image-receipt.json

node "$FREEZE_TOOL" verify-freeze --context "$AURA_CI_FROZEN_CONTEXT" \
  --expected-source-set "$AURA_CI_SOURCE_SET_DIGEST" \
  --workspace-root "$AURA_CI_WORKSPACE_ROOT" --job "$AURA_CI_JOB_ID" \
  > "$ARTIFACTS/archive-frozen-inputs-after.json" || fatal "frozen inputs verification failed after archive stage"

docker_version="$(docker version --format '{{.Server.Version}}')"
python3 "$RECEIPT_TOOL" create \
  --product "$AURA_PRODUCT_ID" \
  --frozen-inputs "$ARTIFACTS/archive-frozen-inputs-after.json" \
  --output "$ARTIFACTS/release-archive-receipt.json" \
  --release-id "$RELEASE_VERSION" \
  --ci-job-id "$AURA_CI_JOB_ID" \
  --builder-id "$AURA_CI_BUILDER_ID" \
  --platform linux/amd64 \
  --docker-version "$docker_version" \
  --gate-receipt "$GATE_RECEIPT" \
  --image-refs "$REFS_FILE" \
  --control-bundle "$CONTROL_BUNDLE" \
  --image-archive "$IMAGE_ARCHIVE" \
  --source "core=$CORE_ROOT" \
  --source "$AURA_PRODUCT_SOURCE_KEY=$AURA_PRODUCT_PROJECT_ROOT"

sha256sum "$ARTIFACTS/release-archive-receipt.json" \
  > "$ARTIFACTS/release-archive-receipt.json.sha256"

# Seal the package into the create-once release store. General CI run artifacts
# may be garbage-collected; this directory is the deployment and rollback
# authority until its explicit retention window closes.
mkdir -p "$AURA_CI_RELEASE_STORE"
[[ ! -e "$release_store_tmp" && ! -L "$release_store_tmp" ]] \
  || fatal "stale release store temp exists: $release_store_tmp"
mkdir "$release_store_tmp"
cp "$ARTIFACTS/release-archive-receipt.json" \
  "$ARTIFACTS/release-archive-receipt.json.sha256" \
  "$CONTROL_BUNDLE" "$IMAGE_ARCHIVE" "$REFS_FILE" "$GATE_RECEIPT" "$release_store_tmp/"
(
  cd "$release_store_tmp"
  sha256sum release-archive-receipt.json release-archive-receipt.json.sha256 \
    "$(basename "$CONTROL_BUNDLE")" "$(basename "$IMAGE_ARCHIVE")" image-refs.env release-image-receipt.json \
    > SHA256SUMS
  chmod 0444 ./*
)
mv -Tn "$release_store_tmp" "$release_store_final"
[[ ! -e "$release_store_tmp" ]] \
  || fatal "release store publish raced with another writer: $release_store_final"
info "$AURA_PRODUCT_ID release archive SEALED on Linux CI Docker (image=$IMAGE_ID)"
info "immutable release package: $release_store_final"
