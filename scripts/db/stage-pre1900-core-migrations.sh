#!/usr/bin/env bash
# Stage immutable pre-#1900 core migration bytes alongside newer core migrations.
# This is a one-time compatibility input for databases that already recorded
# checksums before application composition PR #1900 rewrote migration sources.
# It does not migrate a database or modify Flyway history.
set -euo pipefail

usage() {
  echo "Usage: $0 --out-dir <empty-directory> [--core-root <git-checkout>]" >&2
}

core_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out_dir=""
while (($#)); do
  case "$1" in
    --out-dir) out_dir="${2:-}"; shift 2 ;;
    --core-root) core_root="${2:-}"; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done
if [[ -z "$out_dir" || -z "$core_root" ]]; then
  usage
  exit 2
fi
command -v git >/dev/null || exit 127
command -v tar >/dev/null || exit 127

historical_commit=114f12a4c9b525f184f7b97d423ada9e3ab7de5f
historical_path=platform/src/main/resources/db/migration/core
current_dir="$core_root/$historical_path"
[[ -d "$current_dir" ]] || { echo "Missing current core migrations: $current_dir" >&2; exit 2; }
[[ "$(git -C "$core_root" rev-parse --verify "${historical_commit}^{commit}")" == "$historical_commit" ]] || {
  echo "Pinned pre-#1900 commit unavailable; a full Git history is required" >&2
  exit 2
}
if [[ -e "$out_dir" ]]; then
  echo "Refusing existing overlay path: $out_dir" >&2
  exit 2
fi

mkdir -p "$out_dir"
archive_dir="$(mktemp -d)"
trap 'rm -r "$archive_dir"' EXIT
git -C "$core_root" archive "$historical_commit" "$historical_path" | tar -x -C "$archive_dir"
current_count="$(find "$current_dir" -maxdepth 1 -name 'V*.sql' -type f | wc -l | tr -d ' ')"
historical_count="$(find "$archive_dir/$historical_path" -maxdepth 1 -name 'V*.sql' -type f | wc -l | tr -d ' ')"
if [[ "$historical_count" != 83 || "$current_count" -lt 83 ]]; then
  echo "Unexpected migration source count: current=$current_count historical=$historical_count" >&2
  exit 2
fi
cp "$current_dir"/V*.sql "$out_dir"/
cp "$archive_dir/$historical_path"/V*.sql "$out_dir"/
(cd "$out_dir" && shasum -a 256 V*.sql) > "$out_dir/sha256-manifest.txt"
echo "Staged $(find "$out_dir" -maxdepth 1 -name 'V*.sql' -type f | wc -l | tr -d ' ') core migrations in $out_dir"
echo "Pinned historical commit: $historical_commit ($historical_count exact source files)"
echo "Use this directory as the core Flyway location with -outOfOrder=true; run plain validate after migrate."
