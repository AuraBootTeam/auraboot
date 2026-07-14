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
# Requires DEEPSEEK_API_KEY: distillation is a real LLM call. Without it the run stops rather than
# quietly proving nothing.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
NAME="faq-loop-golden"
SLOT=""
KEEP=0
SKIP_ITS=0

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
[ -n "${DEEPSEEK_API_KEY:-}" ] || { echo "FATAL: DEEPSEEK_API_KEY is not set — distillation is a real LLM call" >&2; exit 2; }

log() { printf '\033[36m[faq-golden]\033[0m %s\n' "$*"; }
STACK="$SCRIPT_DIR/oss-golden-stack.sh"

cleanup() {
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

  # The ITs run against the SHARED integration-test database (application-integration-test.yml
  # pins localhost:5432/aura_boot), not against the slot stack this script brings up. That database
  # is provisioned out-of-band, and when it falls behind the migrations it does not announce it:
  # KbConversationSourceIT fails with a CHECK-constraint violation on source_type='conversation',
  # which reads exactly like the product bug it is designed to catch. It is not. Say so here, before
  # anyone spends an afternoon on it.
  IT_DB="${IT_PG_DB:-aura_boot}"
  IT_CHECK=$(psql -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -d "$IT_DB" -tAc \
    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='chk_doc_source';" 2>/dev/null || true)
  case "$IT_CHECK" in
    *conversation*) : ;;
    "")
      log "❌ integration-test DB '$IT_DB' has no chk_doc_source (or is unreachable) — environment-invalid, not a product failure"
      exit 1 ;;
    *)
      log "❌ integration-test DB '$IT_DB' is BEHIND the migrations: chk_doc_source still forbids 'conversation'."
      log "   This is environment drift, not a product failure — the migration, schema.sql and"
      log "   KbTextIngestService.DB_SOURCE_TYPES all carry 'conversation' on main."
      log "   Fix: refresh that database (scripts/reset-db.sh, PG_DB=$IT_DB) when no other worktree"
      log "   is using it, then re-run. Do NOT 'fix' the product."
      exit 1 ;;
  esac

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
  "$REPO_ROOT/platform/gradlew" -p "$REPO_ROOT/platform" :test "${it_args[@]}" || true

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
bash "$STACK" up "$NAME" --slot "$SLOT" --plugin core-faq-loop

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
log "3/6 browser: queue → transcript → distil (live DeepSeek) → nothing from chit-chat"
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

if [ "$rc" -eq 0 ]; then
  log "✅ conversation → FAQ loop golden PASSED (queue → distil → review → publish → retrievable → pages/menu)"
else
  log "❌ conversation → FAQ loop golden FAILED at pages/menu (rc=$rc)"
fi
exit "$rc"
