#!/usr/bin/env bash
# Bump the release version. Single source of truth = VERSION at repo root.
# Edits VERSION and keeps auraboot.platform.version (application.yml) in sync so
# they never drift (enforced by scripts/check-version-sync.sh).
#
# Does NOT commit or tag — do that via the normal branch/PR flow, then tag
# v<VERSION> at the merge commit. For a COORDINATED MULTI-REPO release, bump
# core AND enterprise to the SAME version and tag both v<VERSION>; the deploy
# ledger (ab_platform_release.metadata.repo_shas) records each repo's commit.
#
# Usage:
#   scripts/release/bump-version.sh patch|minor|major
#   scripts/release/bump-version.sh 1.2.0                # explicit version
#   ROOT=<enterprise-repo-root> scripts/release/bump-version.sh minor   # target another repo
set -euo pipefail
ROOT="${ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
LEVEL="${1:?usage: bump-version.sh <major|minor|patch|X.Y.Z>}"
VFILE="$ROOT/VERSION"
[[ -f "$VFILE" ]] || { echo "[bump] VERSION not found at $VFILE" >&2; exit 1; }
cur="$(tr -d ' \r\n' < "$VFILE")"

case "$LEVEL" in
  major|minor|patch)
    IFS=. read -r MA MI PA <<<"$cur"
    case "$LEVEL" in
      major) MA=$((MA + 1)); MI=0; PA=0 ;;
      minor) MI=$((MI + 1)); PA=0 ;;
      patch) PA=$((PA + 1)) ;;
    esac
    new="$MA.$MI.$PA" ;;
  [0-9]*.[0-9]*.[0-9]*) new="$LEVEL" ;;
  *) echo "[bump] invalid level/version: $LEVEL (use major|minor|patch|X.Y.Z)" >&2; exit 2 ;;
esac

printf '%s\n' "$new" > "$VFILE"

# Keep auraboot.platform.version in sync (only if this repo has application.yml).
APPYML="$ROOT/platform/src/main/resources/application.yml"
SYNCED=""
if [[ -f "$APPYML" ]]; then
  NEW="$new" python3 - "$APPYML" <<'PY'
import os, re, sys
path, new = sys.argv[1], os.environ["NEW"]
lines = open(path, encoding="utf-8").read().split("\n")
in_platform = done = False
for i, ln in enumerate(lines):
    if re.match(r'^  platform:\s*$', ln):
        in_platform = True
    elif in_platform and not done and re.match(r'^    version:\s', ln):
        lines[i] = re.sub(r'(^    version:\s*).*', r'\g<1>' + new, ln)
        done = True
        in_platform = False
    elif re.match(r'^  \S', ln):
        in_platform = False
open(path, "w", encoding="utf-8").write("\n".join(lines))
PY
  SYNCED=" (+ application.yml platform.version)"
fi

echo "[bump] VERSION $cur -> $new$SYNCED"
echo "[bump] next: commit on a branch -> PR -> merge, then:"
echo "       git tag -a v$new -m \"release v$new\" && git push origin v$new"
