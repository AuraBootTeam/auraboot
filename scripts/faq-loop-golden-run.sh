#!/usr/bin/env bash
#
# faq-loop-golden-run.sh — one-click, self-contained golden for the conversation → FAQ loop.
#
# Brings up a host-first stack (zero docker), seeds the conversations, distils FAQ candidates
# with the real LLM, drives the review workbench in a real browser, and tears the stack down.
# Its exit code IS the result — no CI workflow needed.
#
#   ./scripts/faq-loop-golden-run.sh [--slot N] [--keep]
#
#     --keep   leave the stack up afterwards (for debugging a failure)
#
# Determinism is the whole point of the reset step below. The loop is a state machine, and a
# previous run leaves candidates approved and published — so a second run would find nothing
# in draft and fail on a stale queue rather than on a real defect. Reset first, always.
#
# Requires DEEPSEEK_API_KEY: the distillation step is a real LLM call. Without it the run stops
# rather than quietly proving nothing.
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
    -h|--help) sed -n '2,16p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
done
[ -n "$SLOT" ] || { echo "FATAL: --slot N is required (pick a free one: ./dev.sh runtime list)" >&2; exit 2; }
[ -n "${DEEPSEEK_API_KEY:-}" ] || { echo "FATAL: DEEPSEEK_API_KEY is not set — the distillation step is a real LLM call" >&2; exit 2; }

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
log "1/5 host-first stack up (slot $SLOT) + import core-faq-loop"
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

# ---- 2. reset to a known state -----------------------------------------------------------
# Candidates AND the documents a previous run published: re-publishing is idempotent, but a
# leftover published candidate means zero drafts to review, and the golden would be asserting
# against an empty queue.
log "2/5 reset faq_candidate + conversation-sourced KB documents"
psql -h "${PG_HOST:-127.0.0.1}" -p "${PG_PORT:-5432}" -U "${PG_USER:-auraboot}" -d "$PG_DB" -q <<'SQL'
DELETE FROM ab_kb_chunk WHERE doc_id IN (SELECT pid FROM ab_kb_document WHERE source_type = 'conversation');
DELETE FROM ab_kb_document WHERE source_type = 'conversation';
TRUNCATE mt_faq_candidate;
SQL

# ---- 3. seed conversations ---------------------------------------------------------------
log "3/5 seed conversations (one with real Q&A, one pure chit-chat)"
psql -h "${PG_HOST:-127.0.0.1}" -p "${PG_PORT:-5432}" -U "${PG_USER:-auraboot}" -d "$PG_DB" \
  -q -v tenant="$TENANT" -f "$SCRIPT_DIR/seed-faq-loop-conversations.sql" >/dev/null

KB="$(api -X POST "$BE/api/ai/knowledge" -H 'Content-Type: application/json' \
  -d '{"name":"客服 FAQ 知识库","description":"conversation-to-FAQ loop golden"}' \
  | _json "print(d['data']['pid'])")"
log "    knowledge base $KB"

# ---- 4. distil (real LLM) ----------------------------------------------------------------
log "4/5 distil FAQ candidates (live DeepSeek)"
SUPPORT_N="$(api -X POST "$BE/api/faq/conversations/faqseedsupport0000000001/extract" \
  -H 'Content-Type: application/json' -d "{\"targetKbPid\":\"$KB\"}" | _json "print(len(d['data']))")"
CHITCHAT_N="$(api -X POST "$BE/api/faq/conversations/faqseedchitchat0000000001/extract" \
  -H 'Content-Type: application/json' -d "{\"targetKbPid\":\"$KB\"}" | _json "print(len(d['data']))")"
log "    support thread → $SUPPORT_N candidate(s); chit-chat → $CHITCHAT_N"

[ "$SUPPORT_N" -ge 1 ] \
  || { echo "FATAL: the distiller found no FAQ in a conversation that plainly contains two" >&2; exit 1; }
# The anti-hallucination gate, asserted before the browser even opens: a model that manufactures
# a Q&A out of pleasantries would be feeding invented answers to customers.
[ "$CHITCHAT_N" -eq 0 ] \
  || { echo "FATAL: the distiller invented $CHITCHAT_N FAQ(s) from pure chit-chat — unsafe to publish" >&2; exit 1; }

# ---- 5. browser golden -------------------------------------------------------------------
log "5/5 real-browser golden (review → approve → publish → retrievable)"
cd "$REPO_ROOT/web-admin"
npx playwright test -c playwright.gt5.config.ts \
  tests/e2e/faq-loop-review-workbench.spec.ts --project=chromium --reporter=line
rc=$?

if [ "$rc" -eq 0 ]; then
  log "✅ conversation → FAQ loop golden PASSED"
else
  log "❌ conversation → FAQ loop golden FAILED (rc=$rc)"
fi
exit "$rc"
