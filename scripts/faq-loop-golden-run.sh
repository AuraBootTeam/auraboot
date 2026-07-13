#!/usr/bin/env bash
#
# faq-loop-golden-run.sh — one-click, self-contained golden for the conversation → FAQ loop.
#
# Brings up a host-first stack (zero docker), seeds the conversations, and then drives the whole
# loop from a real browser: pick a conversation out of the queue, read what it says, distil it
# with the real LLM, review what came back, approve it, publish it, and check it is retrievable.
# Tears the stack down afterwards. Its exit code IS the result — no CI workflow needed.
#
#   ./scripts/faq-loop-golden-run.sh --slot N [--keep]
#
#     --keep   leave the stack up afterwards (for debugging a failure)
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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="${2:?}"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    -h|--help) sed -n '2,23p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# ---- 1. stack + plugin -------------------------------------------------------------------
log "1/4 host-first stack up (slot $SLOT) + import core-faq-loop"
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
log "2/4 reset faq_candidate + conversation-sourced KB documents, seed conversations"
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
log "3/4 browser: queue → transcript → distil (live DeepSeek) → nothing from chit-chat"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-conversation-queue.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -ne 0 ]; then
  log "❌ conversation → FAQ loop golden FAILED at distillation (rc=$rc)"
  exit "$rc"
fi

# ---- 4. review, from the browser ---------------------------------------------------------
log "4/4 browser: review → approve → publish → retrievable"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-review-workbench.spec.ts --project=chromium --reporter=line || rc=$?

if [ "$rc" -eq 0 ]; then
  log "✅ conversation → FAQ loop golden PASSED (queue → distil → review → publish → retrievable)"
else
  log "❌ conversation → FAQ loop golden FAILED at review (rc=$rc)"
fi
exit "$rc"
