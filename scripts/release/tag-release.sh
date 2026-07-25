#!/usr/bin/env bash
#
# Gated OSS release tag entrypoint.
#
# A release tag is the irreversible boundary that publishes release notes and
# triggers image builds. Run the real-model digital-employee capability gate on
# the exact clean main commit immediately before creating that tag.
#
# Usage:
#   scripts/release/tag-release.sh X.Y.Z          # validate + create local tag
#   scripts/release/tag-release.sh X.Y.Z --push   # validate + create + push tag
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
VERSION_ARG="${1:?usage: tag-release.sh <X.Y.Z> [--push]}"
PUSH=0
if [[ "${2:-}" == "--push" ]]; then
  PUSH=1
elif [[ -n "${2:-}" ]]; then
  echo "[release-tag] unknown arg: $2" >&2
  exit 2
fi

VERSION_FILE="$(tr -d ' \r\n' <"$ROOT/VERSION")"
[[ "$VERSION_ARG" == "$VERSION_FILE" ]] || {
  echo "[release-tag] requested $VERSION_ARG but VERSION contains $VERSION_FILE" >&2
  exit 1
}

branch="$(git -C "$ROOT" branch --show-current)"
[[ "$branch" == "main" ]] || {
  echo "[release-tag] release tags must be created from main, current branch is '$branch'" >&2
  exit 1
}

if [[ -n "$(git -C "$ROOT" status --porcelain --untracked-files=all)" ]]; then
  echo "[release-tag] worktree is dirty; the capability receipt would not identify an exact commit" >&2
  exit 1
fi

head_sha="$(git -C "$ROOT" rev-parse HEAD)"
origin_main="$(git -C "$ROOT" rev-parse origin/main)"
[[ "$head_sha" == "$origin_main" ]] || {
  echo "[release-tag] local main ($head_sha) is not origin/main ($origin_main); fetch/pull first" >&2
  exit 1
}

tag="v$VERSION_ARG"
if git -C "$ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "[release-tag] tag already exists locally: $tag" >&2
  exit 1
fi

echo "[release-tag] exact-commit capability gate ($head_sha)"
RUN_DIR="$ROOT/build/capability-eval/release-$VERSION_ARG" \
  "$ROOT/scripts/digital-employee-capability-eval-run.sh"

receipt="$ROOT/build/capability-eval/release-$VERSION_ARG/receipt.env"
grep -qx "git_sha=$head_sha" "$receipt" || {
  echo "[release-tag] capability receipt does not match release commit $head_sha" >&2
  exit 1
}

git -C "$ROOT" tag -a "$tag" -m "AuraBoot $tag"
echo "[release-tag] created $tag at $head_sha"

if [[ "$PUSH" == "1" ]]; then
  git -C "$ROOT" push origin "$tag"
  echo "[release-tag] pushed $tag; release-notes and image workflows are now triggered"
else
  echo "[release-tag] local tag only. Push after inspection with: git push origin $tag"
fi
