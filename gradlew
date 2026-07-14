#!/usr/bin/env bash
#
# Repo-root shim → platform/gradlew.
#
# The Gradle build lives in platform/, so `gradlew` only ever existed there. Running
# `./gradlew` from the repo root therefore failed with "no such file or directory" — and
# when that is piped (`./gradlew test | tail`), the pipeline's exit status comes from
# `tail`, so the whole thing reports **exit 0**. A command that never ran is indis-
# tinguishable from one that succeeded, and you can spend several rounds "analysing test
# results" for a Gradle that never started (2026-07-14, dict-cache IT).
#
# Rather than asking everyone to remember where gradlew lives, forward to it.
# CI is unaffected: it already uses `working-directory: platform`.
#
# Note the task path still resolves against the platform project, so:
#   ./gradlew :test --tests "<FQCN>"     ✅   (`:test`, not `:platform:test`)
#
# And the only admissible evidence that tests ran is
# platform/build/test-results/test/TEST-<FQCN>.xml — not this script's exit code.
set -euo pipefail
exec "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/platform/gradlew" "$@"
