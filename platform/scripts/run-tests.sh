#!/usr/bin/env bash
# ─── Parallel Test Runner ───
# Runs unit tests with dynamic CPU-based parallelism and reports slow tests.
# Usage: ./scripts/run-tests.sh [extra-gradle-args...]

set -euo pipefail
cd "$(dirname "$0")/.."

CORES=$(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu 2>/dev/null || echo 4)
WORKERS=${WORKERS:-$CORES}

echo "╔═══════════════════════════════════════╗"
echo "║  CPU cores: ${CORES}  │  Gradle workers: ${WORKERS}"
echo "║  JUnit parallel: dynamic (factor=1)   "
echo "╚═══════════════════════════════════════╝"
echo ""

./gradlew test \
  --parallel \
  --console=plain \
  -Dorg.gradle.workers.max="${WORKERS}" \
  -x :platform-storage-s3:test \
  -x :platform-storage-minio:test \
  -x :platform-storage-oss:test \
  -x :platform-mq-kafka:test \
  -x :platform-mq-rabbitmq:test \
  -x :platform-plugin-api:test \
  "$@"

echo ""
echo "═══ Top 50 Slowest Tests ═══"
find build -path '*/test-results/*/TEST-*.xml' -print0 2>/dev/null \
  | xargs -0 grep -h '<testcase' 2>/dev/null \
  | sed -n 's/.*classname="\([^"]*\)".*name="\([^"]*\)".*time="\([^"]*\)".*/\3s  \1#\2/p' \
  | sort -t's' -k1 -nr \
  | head -50 \
  || echo "(no test results found)"
