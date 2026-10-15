#!/usr/bin/env bash
# Nightly OSS backend coverage gate (no docker; host PostgreSQL required).
#
# Runs the full `test` suite against the local integration-test database,
# generates the JaCoCo report, and enforces the bundle/package ratchet floors.
# Designed for crontab scheduling; writes evidence under .workspace/nightly-coverage/.
#
# Usage: scripts/oss-backend-nightly-coverage.sh [repo-root]
# Connection defaults target the original local-dev database (ghj@localhost:5432/aura_boot)
# and stay fully overridable so a differently-provisioned executor (e.g. the aura-ci
# host, whose role/database/password differ) can run the same gate unmodified:
#   PGHOST / PGPORT / PGUSER / PGDATABASE / PGPASSWORD — standard libpq variables;
#   set PGHOST empty to use the unix socket (peer auth, no password needed).
# The Gradle suite receives the same coordinates through the TEST_DATABASE_* /
# SPRING_DATASOURCE_* / DATABASE_URL contracts it already reads.
# Crontab example (02:30 nightly, log under outputs/):
#   30 2 * * * /home/local-oss-dev/work/auraboot/auraboot/scripts/oss-backend-nightly-coverage.sh >> /home/local-oss-dev/work/auraboot/outputs/nightly-coverage.log 2>&1

set -uo pipefail

ROOT="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
PLATFORM="$ROOT/platform"
STAMP="$(date +%Y%m%d-%H%M%S)"
EVIDENCE="$ROOT/.workspace/nightly-coverage/$STAMP"
mkdir -p "$EVIDENCE"

PGHOST="${PGHOST-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-ghj}"
PGDATABASE="${PGDATABASE:-aura_boot}"

psql_args=(-U "$PGUSER" -d "$PGDATABASE" -p "$PGPORT")
[[ -n "$PGHOST" ]] && psql_args=(-h "$PGHOST" "${psql_args[@]}")

# The suite reads its JDBC coordinates from these; keep every contract the CI
# runner exports so host-PG runs behave exactly like compose-backed ones.
JDBC_URL="jdbc:postgresql://${PGHOST:-127.0.0.1}:${PGPORT}/${PGDATABASE}?charSet=UTF8"
export TEST_DATABASE_URL="$JDBC_URL"
export TEST_DATABASE_USERNAME="$PGUSER"
export TEST_DATABASE_PASSWORD="${PGPASSWORD:-}"
export TEST_EXPECTED_DATABASE="$PGDATABASE"
export DATABASE_URL="$JDBC_URL"
export DATABASE_USERNAME="$PGUSER"
export DATABASE_PASSWORD="${PGPASSWORD:-}"
export SPRING_DATASOURCE_URL="$JDBC_URL"
export SPRING_DATASOURCE_USERNAME="$PGUSER"
export SPRING_DATASOURCE_PASSWORD="${PGPASSWORD:-}"

fail() { echo "[nightly-coverage] FAIL: $*" | tee -a "$EVIDENCE/result.txt"; exit 2; }

# ── Preflight (cheap checks before the expensive run) ──
command -v java >/dev/null || fail "java not on PATH"
psql "${psql_args[@]}" -tAc "select 1" >/dev/null 2>&1 \
  || fail "host PostgreSQL ${PGDATABASE} is unreachable (user=${PGUSER} host=${PGHOST:-socket} port=${PGPORT})"

# Schema must match HEAD: compare latest applied Flyway version with the newest migration file.
DB_VERSION="$(psql "${psql_args[@]}" -tAc "select max(version) from ab_flyway_schema_history" | tr -d ' ')"
HEAD_VERSION="$(ls "$PLATFORM/src/main/resources/db/migration/core" | sort | tail -1 | sed 's/V\([0-9]*\)__.*/\1/')"
if [[ "$DB_VERSION" != "$HEAD_VERSION" ]]; then
  fail "schema drift: DB at $DB_VERSION, HEAD at $HEAD_VERSION — run pending migrations first"
fi

cd "$PLATFORM" || fail "cannot cd into $PLATFORM"

# ── Full suite (long; survives daemon restarts is NOT guaranteed — keep the box idle) ──
./gradlew test --continue > "$EVIDENCE/test.log" 2>&1
TEST_EXIT=$?
grep -E " FAILED$" "$EVIDENCE/test.log" | sort | uniq > "$EVIDENCE/failed-tests.txt" || true

# ── Report from the recorded exec regardless of test outcome (report dependsOn test
#    and would be skipped on failure). Do NOT re-run the suite here.
./gradlew jacocoTestReport -x test > "$EVIDENCE/report.log" 2>&1 \
  || fail "jacocoTestReport failed — see $EVIDENCE/report.log"

# ── Ratchet verification against the recorded exec ──
./gradlew jacocoTestCoverageVerification -x test > "$EVIDENCE/verify.log" 2>&1
VERIFY_EXIT=$?

# ── Headline numbers from the XML (LINE counters, bundle level) ──
XML="$PLATFORM/build/reports/jacoco/test/jacocoTestReport.xml"
node -e '
const fs = require("fs");
const xml = fs.readFileSync(process.argv[1], "utf8");
const all = [...xml.matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/g)];
const b = all[all.length - 1];
const missed = +b[1], covered = +b[2];
console.log(`bundle-line covered=${covered} missed=${missed} ratio=${(covered / (covered + missed) * 100).toFixed(2)}%`);
' "$XML" > "$EVIDENCE/result.txt" 2>&1

{
  echo "test-exit=$TEST_EXIT failed-tests=$(wc -l < "$EVIDENCE/failed-tests.txt" | tr -d ' ')"
  echo "verify-exit=$VERIFY_EXIT"
  echo "evidence=$EVIDENCE"
} >> "$EVIDENCE/result.txt"

cat "$EVIDENCE/result.txt"
[[ "$TEST_EXIT" -eq 0 ]] || exit 1
[[ "$VERIFY_EXIT" -eq 0 ]] || exit 1
exit 0
