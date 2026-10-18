#!/usr/bin/env bash
# Tripwire for known-copyleft coordinates re-entering the default distribution.
#
# This is a fast static gate, not a full SBOM/license audit: it matches the
# exact coordinates that were removed from the default distribution for
# licensing reasons (2026-09-27 OSS review) so they cannot silently come
# back. Dependency-resolution-level license auditing is tracked separately.
#
# Path exception: platform/platform-scheduler-xxl/** is the deliberate,
# documented opt-in home for the xxl integration (GPL-3.0) — the default
# bootJar never includes it. Anywhere else in the build files is a violation.
set -euo pipefail

# "group:artifact" prefixes banned outside the opt-in module. Each entry
# records why it was removed.
BLOCKLIST=(
  "com.xuxueli:xxl-job"         # GPL-3.0 — optional scheduler engine only (-PwithSchedulerXxl)
  "com.mysql:mysql-connector"   # GPLv2 + FOSS Exception — user-provided driver
  "mysql:mysql-connector-java"  # legacy coordinate of the above
)

mapfile -t build_files < <(git ls-files '*build.gradle' '*build.gradle.kts' | grep -v '^platform/platform-scheduler-xxl/' || true)
if [ "${#build_files[@]}" -eq 0 ]; then
  echo "ERROR: no build files found to scan."
  exit 1
fi

pattern="$(IFS='|'; echo "${BLOCKLIST[*]}")"
hits="$(git grep -InE "$pattern" -- "${build_files[@]}" 2>/dev/null || true)"
if [ -n "$hits" ]; then
  echo "ERROR: copyleft dependencies are banned from the default distribution build files:"
  echo "$hits" | sed 's/^/  /'
  echo ""
  echo "These coordinates were removed for license reasons. If the capability is"
  echo "needed, move it behind an explicitly opted-in module (see"
  echo "platform/platform-scheduler-xxl/README.md) or require the deployer to"
  echo "supply the artifact at runtime."
  exit 1
fi
echo "OK: no banned license coordinates in distribution build files."
