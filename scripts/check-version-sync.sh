#!/usr/bin/env bash
# Gate: VERSION is the release version single source of truth for runtime config
# and Gradle-published artifacts.
# Bump both together via scripts/release/bump-version.sh. Run before push.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPYML="$ROOT/platform/src/main/resources/application.yml"

[[ -f "$ROOT/VERSION" ]] || { echo "[version-sync] VERSION missing at repo root" >&2; exit 1; }
v_file="$(tr -d ' \r\n' < "$ROOT/VERSION")"

if [[ ! -f "$APPYML" ]]; then
  echo "[version-sync] OK — no application.yml in this repo; VERSION=$v_file (nothing to sync)"
  exit 0
fi

v_yaml="$(python3 - "$APPYML" <<'PY'
import re, sys
in_p = False
for ln in open(sys.argv[1], encoding="utf-8"):
    if re.match(r'^  platform:\s*$', ln):
        in_p = True
    elif in_p and re.match(r'^    version:\s', ln):
        print(ln.split(':', 1)[1].strip())
        break
    elif re.match(r'^  \S', ln):
        in_p = False
PY
)"

if [[ -z "$v_yaml" ]]; then
  echo "[version-sync] could not read auraboot.platform.version from $APPYML" >&2
  exit 1
fi
if [[ "$v_file" != "$v_yaml" ]]; then
  echo "[version-sync] DRIFT: VERSION=$v_file != application.yml platform.version=$v_yaml" >&2
  echo "[version-sync] Fix: scripts/release/bump-version.sh $v_file   (re-syncs application.yml)" >&2
  exit 1
fi

if [[ "$v_file" == *SNAPSHOT* || "$v_file" == "latest" ]]; then
  echo "[version-sync] mutable release version is forbidden: $v_file" >&2
  exit 1
fi

if rg -n "^version\\s*=.*SNAPSHOT|^version=.*SNAPSHOT" "$ROOT/platform" \
  --glob 'build.gradle' --glob 'build.gradle.kts' --glob 'gradle.properties'; then
  echo "[version-sync] Gradle project versions must not be hard-coded to SNAPSHOT" >&2
  exit 1
fi

if ! rg -q "version = file\\('../VERSION'\\)\\.text\\.trim\\(\\)" "$ROOT/platform/build.gradle"; then
  echo "[version-sync] platform/build.gradle must derive its version from ../VERSION" >&2
  exit 1
fi

echo "[version-sync] OK — VERSION == platform.version == Gradle artifact version == $v_file"
