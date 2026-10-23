#!/usr/bin/env bash
# Run the existing capability-eval catalog through its physical module owners.
# Full is the release qualification; focused is a representative live evaluation;
# deterministic proves import/persistence plumbing and never claims model quality.
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKSPACE_ROOT="${AURA_WORKSPACE_ROOT:-}"
if [ -z "$WORKSPACE_ROOT" ]; then
  candidate="$REPO_ROOT"
  for _ in 1 2 3 4; do
    candidate="$(dirname "$candidate")"
    if [ -x "$candidate/aura" ]; then WORKSPACE_ROOT="$candidate"; break; fi
  done
fi
[ -x "$WORKSPACE_ROOT/aura" ] || { echo '[capability-eval] Workspace aura CLI is required' >&2; exit 2; }
RUNTIME="${AURA_RUNTIME_NAME:-de-capability-eval}"
SLOT="${SLOT:-83}"
PROFILE=full
KEEP=0
source_specs=()
while [ $# -gt 0 ]; do
  case "$1" in
    --runtime) RUNTIME="$2"; shift 2 ;;
    --slot) SLOT="$2"; shift 2 ;;
    --profile) PROFILE="$2"; shift 2 ;;
    --source) source_specs+=(--source "$2"); shift 2 ;;
    --keep) KEEP=1; shift ;;
    --help|-h) echo 'Usage: digital-employee-capability-eval-run.sh [--runtime NAME] [--slot N] [--profile full|focused|deterministic] [--source key=path] [--keep]'; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done
# Validate the profile before provisioning or making any model request.
node --input-type=module - "$REPO_ROOT" "$PROFILE" <<'JS'
const { profileSuites } = await import(process.argv[2] + '/scripts/lib/capability-eval-junit.mjs');
profileSuites(process.argv[3]);
JS
if [ "$PROFILE" != deterministic ]; then
  for name in AURA_LIVE_LLM_PROVIDER AURA_LIVE_LLM_MODEL AURA_LIVE_LLM_API_KEY_ENV; do
    [ -n "${!name:-}" ] || { echo "[capability-eval] $name is UNSET; live profile refused" >&2; exit 2; }
  done
  key_env="$AURA_LIVE_LLM_API_KEY_ENV"
  [[ "$key_env" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || { echo 'Invalid credential environment variable name' >&2; exit 2; }
  [ -n "${!key_env:-}" ] || { echo "[capability-eval] $key_env is UNSET" >&2; exit 2; }
  echo "[capability-eval] provider=$AURA_LIVE_LLM_PROVIDER model=$AURA_LIVE_LLM_MODEL credential=SET profile=$PROFILE"
fi
# Reuse one stable runtime; no slot scanning, shared cache override or destructive trap.
"$WORKSPACE_ROOT/aura" runtime ensure auraboot "$RUNTIME" --slot "$SLOT" --source-root "$REPO_ROOT" --purpose 'Digital employee capability evaluation' --ttl 8h
if [ -n "${RUN_DIR:-}" ]; then
  mkdir -p "$RUN_DIR"
else
  RUN_DIR="$("$WORKSPACE_ROOT/aura" runtime evidence begin "$RUNTIME" --purpose "capability evaluation $PROFILE")"
fi
LOG="$RUN_DIR/capability-eval.log"
cleanup() {
  rc=$?
  if [ "$KEEP" != 1 ]; then
    "$WORKSPACE_ROOT/aura" runtime suspend "$RUNTIME" >>"$LOG" 2>&1 || { echo '[capability-eval] Owned suspend failed; inspect retained runtime' >&2; [ "$rc" != 0 ] || rc=1; }
  fi
  exit "$rc"
}
trap cleanup EXIT
AURA_WORKSPACE_ROOT="$WORKSPACE_ROOT" "$REPO_ROOT/scripts/oss-golden-stack.sh" up "$RUNTIME" --slot "$SLOT" \
  --isolated-redis-port "${AURA_EVAL_REDIS_PORT:-$((16400 + SLOT))}" --plugin-profile default --no-frontend --no-warm ${source_specs[@]+"${source_specs[@]}"} >>"$LOG" 2>&1
"$WORKSPACE_ROOT/aura" runtime verify "$RUNTIME" >>"$LOG" 2>&1
mkdir -p "$RUN_DIR/native"
node --input-type=module - "$RUN_DIR" "$PROFILE" "$RUNTIME" <<'JS'
import fs from 'node:fs';
fs.writeFileSync(process.argv[2] + '/run-context.json', JSON.stringify({
  startedAt: new Date().toISOString(), profile: process.argv[3], runtime: process.argv[4],
  provider: process.env.AURA_LIVE_LLM_PROVIDER ?? null, model: process.env.AURA_LIVE_LLM_MODEL ?? null,
  credential: process.env.AURA_LIVE_LLM_API_KEY_ENV && process.env[process.env.AURA_LIVE_LLM_API_KEY_ENV] ? 'SET' : 'UNSET',
}, null, 2) + '\n');
JS
export AURA_EVAL_REDIS_PORT="${AURA_EVAL_REDIS_PORT:-$((16400 + SLOT))}"
rc=0
for module in platform-app platform-agent; do
  selectors=()
  while IFS= read -r class; do [ -z "$class" ] || selectors+=(--tests "$class"); done < <(
    node --input-type=module - "$REPO_ROOT" "$PROFILE" "$module" "$RUN_DIR" <<'JS'
import fs from 'node:fs';
import path from 'node:path';
const [repo, profile, module, output] = process.argv.slice(2);
const { profileSuites } = await import(repo + '/scripts/lib/capability-eval-junit.mjs');
for (const [task, name] of profileSuites(profile).filter(([task]) => task === module + '/test')) {
  const xml = path.join(repo, 'platform', module, 'build/test-results/test', `TEST-${name}.xml`);
  // Remove only selected generated outputs so an early build failure cannot accept stale XML.
  if (fs.existsSync(xml)) fs.unlinkSync(xml);
  console.log(name);
}
JS
  )
  [ "${#selectors[@]}" -gt 0 ] || continue
  "$WORKSPACE_ROOT/aura" run "$RUNTIME" --workdir "$REPO_ROOT/platform" -- bash -c \
    'export TEST_DATABASE_URL="$DATABASE_URL" TEST_DATABASE_USERNAME="$DATABASE_USERNAME" TEST_DATABASE_PASSWORD="${DATABASE_PASSWORD-}" SPRING_DATA_REDIS_HOST=127.0.0.1 SPRING_DATA_REDIS_PORT="$AURA_EVAL_REDIS_PORT" SPRING_DATA_REDIS_DATABASE="$REDIS_DATABASE"; exec "$@"' \
    _ "$WORKSPACE_ROOT/aura" gradle "$RUNTIME" --project "$REPO_ROOT/platform" -- ":$module:test" --rerun -PincludeLiveEvals "${selectors[@]}" --no-daemon >>"$LOG" 2>&1 || rc=1
  node --input-type=module - "$REPO_ROOT" "$PROFILE" "$module" "$RUN_DIR" <<'JS'
import fs from 'node:fs';
import path from 'node:path';
const [repo, profile, module, output] = process.argv.slice(2);
const { profileSuites } = await import(repo + '/scripts/lib/capability-eval-junit.mjs');
for (const [task, name] of profileSuites(profile).filter(([task]) => task === module + '/test')) {
  const source = path.join(repo, 'platform', module, 'build/test-results/test', `TEST-${name}.xml`);
  const dest = path.join(output, 'native', task, `TEST-${name}.xml`);
  if (fs.existsSync(source)) { fs.mkdirSync(path.dirname(dest), { recursive: true }); fs.copyFileSync(source, dest); }
}
JS
done
node "$REPO_ROOT/scripts/lib/capability-eval-junit.mjs" --results-root "$RUN_DIR/native" --profile "$PROFILE" --json-out "$RUN_DIR/report.json" | tee "$RUN_DIR/summary.txt" || rc=1
# Preserve the release helper's exact-source receipt, using parsed native counts.
node --input-type=module - "$REPO_ROOT" "$PROFILE" "$RUN_DIR" "$rc" <<'JS'
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const [repo, profile, output, rc] = process.argv.slice(2);
const report = JSON.parse(fs.readFileSync(output + '/report.json', 'utf8'));
const git = (...args) => execFileSync('git', ['-C', repo, ...args], { encoding: 'utf8' }).trim();
report.nativePass = report.ok;
report.gradleSucceeded = rc === '0';
report.ok = report.ok && report.gradleSucceeded;
report.source = { commit: git('rev-parse', 'HEAD'), dirty: git('status', '--porcelain=v1', '--untracked-files=all') !== '' };
report.claim = profile === 'full' ? 'full catalog qualification' : profile === 'focused' ? 'representative live evaluation only' : 'deterministic import/persistence plumbing only';
fs.writeFileSync(output + '/report.json', JSON.stringify(report, null, 2) + '\n');
if (report.ok) fs.writeFileSync(output + '/receipt.env', [
  `git_sha=${report.source.commit}`, `tracked_source_dirty=${report.source.dirty}`, `profile=${profile}`,
  `required_suites=${report.requiredSuites}`, `tests=${report.totals.tests}`, `skipped=${report.totals.skipped}`,
  `failures=${report.totals.failures}`, `errors=${report.totals.errors}`, `completed_at=${new Date().toISOString()}`,
].join('\n') + '\n');
JS
# A native suite failure remains a failed run even if older/generated receipts appear green.
if [ "$rc" != 0 ]; then echo "[capability-eval] FAIL: profile=$PROFILE; evidence=$RUN_DIR"; exit 1; fi
echo "[capability-eval] PASS: profile=$PROFILE; evidence=$RUN_DIR (no wider quality claim)"
