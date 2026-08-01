#!/usr/bin/env bash
#
# faq-loop-golden-run.sh — one-click, self-contained golden for the conversation → FAQ loop.
#
# Brings up a host-first stack (zero docker), seeds the conversations, and then drives the whole
# loop from a real browser: pick a conversation out of the queue, read what it says, distil it
# with the real LLM, review what came back, approve it, publish it, and check it is retrievable.
# Tears the stack down afterwards. Its exit code IS the result — no CI workflow needed.
#
#   ./scripts/faq-loop-golden-run.sh --slot N [--keep] [--skip-its]
#
#     --keep       leave the stack up afterwards (for debugging a failure)
#     --skip-its   skip the backend ITs (tightens the edit→run loop; not a way to pass the gate)
#
# The browser goldens prove the loop works from the UI. Two things it cannot see run first, as
# backend ITs:
#
#   ConversationFaqExtractionLiveIT — the model must return NOTHING from a conversation whose
#     answer was never given. A browser watching candidates appear cannot tell a distilled answer
#     from an invented one; only a negative sample can, and only against the real LLM.
#   KbConversationSourceIT — publishing writes source_type='conversation'. Get it wrong and
#     KbTextIngestService silently rewrites it to 'internal_doc': the document still lands, still
#     embeds, still retrieves, and every browser assertion still passes.
#
# They run BEFORE the stack comes up, deliberately: the test task asks for -Xmx6g and would race a
# live backend for the same memory. A golden that intermittently kills its own stack gets called
# flaky and then gets ignored.
#
# Nothing is distilled outside the browser, deliberately. The manual trigger shipped as an API
# endpoint before it had a button, and an API-driven golden would have gone green while the entry
# point a human needs did not exist. The queue spec runs first because it is what creates the
# candidates the review spec then works through.
#
# Determinism: the loop is a state machine, and a previous run leaves candidates approved and
# published. Reset first, always — otherwise a second run reviews an empty queue and "passes".
#
# Requires the provider-neutral AURA_LIVE_LLM_* profile. Without a complete
# profile the run stops instead of quietly proving nothing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAME="faq-loop-golden"
SLOT=""
KEEP=0
SKIP_ITS=0
IT_DB=""
MANAGE_IT_DB=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="${2:?}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --skip-its) SKIP_ITS=1; shift ;;
    -h|--help) sed -n '2,38p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SLOT" ] || { echo "FATAL: --slot N is required (pick a free one: ./dev.sh runtime list)" >&2; exit 2; }
for required_var in AURA_LIVE_LLM_PROVIDER AURA_LIVE_LLM_MODEL AURA_LIVE_LLM_API_KEY_ENV; do
  [ -n "${!required_var:-}" ] \
    || { echo "FATAL: $required_var is UNSET — distillation requires a real LLM profile" >&2; exit 2; }
done
key_env="$AURA_LIVE_LLM_API_KEY_ENV"
[[ "$key_env" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
  || { echo "FATAL: AURA_LIVE_LLM_API_KEY_ENV is invalid" >&2; exit 2; }
[ -n "${!key_env:-}" ] \
  || { echo "FATAL: configured credential variable $key_env is UNSET" >&2; exit 2; }
LIVE_ENV=(env AGENT_LLM_STUB_MODE=false)
# The production seeder enables every provider whose credential exists. When the live profile asks
# for Qianwen, leaving an unrelated DeepSeek key visible lets its lower numeric priority silently
# win in the runtime stack, so the browser and the live ITs would not exercise the declared profile.
[ "$AURA_LIVE_LLM_PROVIDER" = "qianwen" ] \
  && LIVE_ENV=(env -u DEEPSEEK_API_KEY AGENT_LLM_STUB_MODE=false)

log() { printf '\033[36m[faq-golden]\033[0m %s\n' "$*"; }
STACK="$SCRIPT_DIR/oss-golden-stack.sh"

cleanup() {
  if [ "$MANAGE_IT_DB" -eq 1 ] && [ -n "$IT_DB" ]; then
    log "dropping isolated backend-IT database $IT_DB"
    psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -d postgres -q \
      -c "DROP DATABASE IF EXISTS \"$IT_DB\" WITH (FORCE);" >/dev/null 2>&1 || true
  fi
  if [ "$KEEP" -eq 1 ]; then
    log "--keep: leaving the stack up ($NAME)"
    return
  fi
  log "tearing down $NAME"
  bash "$STACK" destroy "$NAME" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---- 0. the two things the browser cannot see --------------------------------------------
# Verdict comes from the JUnit XML, not from the exit code of a pipeline: a gradle invocation whose
# output is piped reports the exit code of the LAST command in the pipe, and "no XML" is not the
# same as "passed" — it means the tests never ran.
if [ "$SKIP_ITS" -eq 1 ]; then
  log "0/6 backend ITs SKIPPED (--skip-its) — this run cannot be used to claim the gate is green"
else
  log "0/6 backend ITs: fabrication gate (live LLM) + source_type really is 'conversation'"

  # Never grade against the shared aura_boot integration database. A single constraint probe used
  # to declare it current while newer columns (for example knowledge-base visibility) were still
  # absent, producing product-looking SQL failures. A slot-scoped, freshly migrated database makes
  # the schema part of the evidence and is safe beside other worktrees.
  IT_DB="${IT_PG_DB:-auraboot_faq_it_${SLOT}}"
  if [ -z "${IT_PG_DB:-}" ]; then
    MANAGE_IT_DB=1
    IT_CONNECTIONS=$(psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -d postgres -tAc \
      "SELECT count(*) FROM pg_stat_activity WHERE datname='$IT_DB' AND pid <> pg_backend_pid();")
    [ "$IT_CONNECTIONS" = "0" ] \
      || { log "❌ $IT_CONNECTIONS connection(s) to $IT_DB — slot $SLOT belongs to another run"; exit 1; }
    psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -d postgres -q \
      -c "DROP DATABASE IF EXISTS \"$IT_DB\" WITH (FORCE);"
    psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -d postgres -q \
      -c "CREATE DATABASE \"$IT_DB\";"
    PG_DB="$IT_DB" "$REPO_ROOT/scripts/db/flyway-migrate.sh" --edition oss >/dev/null
  fi
  log "    backend IT database=$IT_DB (fresh core migrations)"

  IT_CLASSES=(
    "com.auraboot.framework.agent.ConversationFaqExtractionLiveIT"
    "com.auraboot.framework.rag.service.KbConversationSourceIT"
  )
  it_args=()
  for c in "${IT_CLASSES[@]}"; do
    it_args+=(--tests "$c")
    # Delete last run's report first. "The XML says passed" is only evidence if the XML could not
    # have been written by an earlier run — a build that fails to start leaves the old green file
    # sitting there, and the check below would happily read it.
    rm -f "$REPO_ROOT/platform/build/test-results/test/TEST-${c}.xml"
  done

  # Leading colon: without it a multi-module build resolves `test` in a subproject and reports
  # "No tests found" while exiting 0.
  "${LIVE_ENV[@]}" \
    SPRING_DATASOURCE_URL="jdbc:postgresql://${PG_HOST:-localhost}:${PG_PORT:-5432}/$IT_DB?charSet=UTF8" \
    SPRING_DATASOURCE_USERNAME="${IT_PG_USER:-ghj}" \
    SPRING_DATASOURCE_PASSWORD="${IT_PG_PASSWORD:-}" \
    "$REPO_ROOT/platform/gradlew" -p "$REPO_ROOT/platform" :test -PincludeLiveEvals \
      "${it_args[@]}" || true

  for c in "${IT_CLASSES[@]}"; do
    xml="$REPO_ROOT/platform/build/test-results/test/TEST-${c}.xml"
    [ -f "$xml" ] || { log "❌ $c did not run (no $xml) — that is not a pass"; exit 1; }
    if grep -qE '<(failure|error)\b' "$xml"; then
      log "❌ $c FAILED — see $xml"
      exit 1
    fi
    log "    ✓ $c"
  done
fi

# ---- 1. stack + plugin -------------------------------------------------------------------
log "1/6 host-first stack up (slot $SLOT) + import core-faq-loop"
"${LIVE_ENV[@]}" bash "$STACK" up "$NAME" --slot "$SLOT" --plugin core-faq-loop

eval "$(bash "$STACK" env "$NAME" | grep '^export')"
BE="${BACKEND_URL:?}"

_json() { python3 -c "import sys,json;d=json.load(sys.stdin);exec(sys.argv[1])" "$1"; }
api() { curl -sf --noproxy '*' -H "Authorization: Bearer $TOKEN" "$@"; }

TOKEN="$(curl -sf --noproxy '*' -X POST "$BE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"admin@auraboot.com","password":"Test2026x"}' \
  | _json "print(d['data']['jwt'])")"
TENANT="$(printf '%s' "$TOKEN" | cut -d. -f2 | python3 -c "
import sys,base64,json
s=sys.stdin.read().strip(); s+='='*(-len(s)%4)
print(json.loads(base64.urlsafe_b64decode(s))['tenantId'])")"
log "    tenant=$TENANT"

# ---- 2. reset + seed ---------------------------------------------------------------------
log "2/6 reset faq_candidate + conversation-sourced KB documents, seed conversations"
psql -h "${PG_HOST:-127.0.0.1}" -p "${PG_PORT:-5432}" -U "${PG_USER:-auraboot}" -d "$PG_DB" -q <<'SQL'
DELETE FROM ab_kb_chunk WHERE doc_id IN (SELECT pid FROM ab_kb_document WHERE source_type = 'conversation');
DELETE FROM ab_kb_document WHERE source_type = 'conversation';
TRUNCATE mt_faq_candidate;
SQL

psql -h "${PG_HOST:-127.0.0.1}" -p "${PG_PORT:-5432}" -U "${PG_USER:-auraboot}" -d "$PG_DB" \
  -q -v tenant="$TENANT" -f "$SCRIPT_DIR/seed-faq-loop-conversations.sql" >/dev/null

FAQ_TARGET_KB_PID="$(api -X POST "$BE/api/ai/knowledge" -H 'Content-Type: application/json' \
  -d '{"name":"客服 FAQ 知识库","description":"conversation-to-FAQ loop golden"}' \
  | _json "print(d['data']['pid'])")"
export FAQ_TARGET_KB_PID
log "    knowledge base $FAQ_TARGET_KB_PID"

cd "$REPO_ROOT/web-admin"
rc=0

# ---- 3. distil, from the browser ---------------------------------------------------------
# Creates the candidates step 4 reviews, and carries the fabrication gate: pointing the distiller
# at the chit-chat conversation must yield nothing.
log "3/6 browser: queue → transcript → distil (live LLM) → nothing from chit-chat"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-conversation-queue.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -ne 0 ]; then
  log "❌ conversation → FAQ loop golden FAILED at distillation (rc=$rc)"
  exit "$rc"
fi

# ---- 4. review, from the browser ---------------------------------------------------------
log "4/6 browser: review → edit → reject → approve → publish → retrievable"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-review-workbench.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -ne 0 ]; then
  log "❌ conversation → FAQ loop golden FAILED at review (rc=$rc)"
  exit "$rc"
fi

# ---- 5. the pages and the menu ------------------------------------------------------------
# The sidebar, the model's list/detail/form pages, and the detail toolbar — a second execution
# path for the same commands the row actions use, and one that has already diverged once. This
# segment distils its own conversation from the queue, because the review segment works its
# candidates down to nothing.
log "5/6 browser: sidebar reachability + list/detail/form + detail-toolbar command path"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-pages-and-menu.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -ne 0 ]; then
  log "❌ conversation → FAQ loop golden FAILED at pages/menu (rc=$rc)"
  exit "$rc"
fi

# ---- 6. retract, from the browser ---------------------------------------------------------
# faq:unpublish has a real UI entry (从知识库撤回 on the workbench). This distils its own
# conversation, publishes it, clicks 撤回, and then asks the retrieval API whether the answer is
# still recalled — the two static gates prove the DSL is legal, only this proves the button fires
# and that pulling actually takes the FAQ out of retrieval (not just flips a status). Uses the
# FAQ_TARGET_KB_PID this script already exported.
log "6/6 browser: publish → 从知识库撤回 → no longer retrievable"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-unpublish.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -eq 0 ]; then
  log "✅ conversation → FAQ loop golden PASSED (queue → distil → review → publish → retrievable → pages/menu → unpublish)"
else
  log "❌ conversation → FAQ loop golden FAILED at unpublish (rc=$rc)"
fi
exit "$rc"
