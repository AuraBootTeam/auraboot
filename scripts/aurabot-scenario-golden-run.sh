#!/usr/bin/env bash
#
# aurabot-scenario-golden-run.sh — self-contained scenario golden for the
# AuraBot agent runtime (execution-architecture review campaign, 2026-07-19).
#
# WHAT IT GUARDS — the enterprise scenario matrix, each row a product claim:
#
#   | # | 企业场景                     | 驱动消息/输入                    | 断言(路由+seam,真栈 DB 反查)                          |
#   |---|------------------------------|----------------------------------|----------------------------------------------------------|
#   | S1| 员工问数(上下文只读问答)   | "查询本月订单统计"               | bucket=CONTEXTUAL_ANSWER + reason=SYNC_READ_ONLY_TURN    |
#   | S2| 对话建单(同步写操作)       | "创建一个客户"                   | bucket=SYNC_ACTION + reason=SYNC_ACTION_TURN,并写入      |
#   |   |                              |                                  | L1 记忆 importance=4                                     |
#   | S3| 批量/后台任务(durable)     | "批量删除过期线索"               | bucket=ACP_RUN + ab_agent_task 落任务行(引擎路由,      |
#   |   |                              |                                  | 不断言 stub-LLM 的任务质量)                              |
#   | S4| 咨询不误触发(G4)           | "为什么导出会失败"               | 走解释路径,绝不进 durable(全局 ACP_RUN 行数=1,即仅 S3)|
#   | S6| 具名 agent 路由+失败可观测   | agentCode=不存在的 agent         | mode=NAMED_AGENT_TURN 路由 + turn.failed 落观测行。      |
#   |   |                              |                                  | G8 冲突(named+durable flags→显式拒绝)不在此层:web    |
#   |   |                              |                                  | 入口有意不接受客户端 durable flags(单调信任,controller|
#   |   |                              |                                  | 对 TurnRequest.options 置 null),由 Tier A planner 矩阵 |
#   |   |                              |                                  | + ConversationTurnServiceImplDispatchTest(服务层 IT)守 |
#   | — | 全场景横切(G1 seam)        | 以上全部                         | 每个终态 turn 恰有一行 ab_agent_observation,detail 带    |
#   |   |                              |                                  | triageBucket/initialMode/decisionReason/latencyMs        |
#
#   台面下但同属黄金集的机制层(Tier A,无栈快跑):triage 规则矩阵(含
#   explain 前缀短路与遮蔽护栏)、planner 遮蔽矩阵与单调升级不变量、chat
#   工具循环五路径(deny/confirm/approval/escalate/allow)、envelope 只读
#   封顶(G10 verdict + D2 profile,只紧不松)、观测/记忆双 listener 语义、
#   fail-closed 分 channel。记忆晋升通道(D1)在 ChatMemoryPromotionScanner
#   IT 中另行覆盖(需共享 IT 库,不进本 runner)。
#
# HOW: Tier A = targeted unit suite, truth read from test-results XML (没
# XML = 没跑)。Tier B = isolated host-first stack (oss-golden-stack, fresh
# DB, zero docker) → drive the scenarios through the REAL /chat/stream API
# → psql the evidence tables. trap guarantees teardown; exit code is the
# verdict. Pass --keep to keep the stack for inspection.
#
# Usage:
#   ./scripts/aurabot-scenario-golden-run.sh [--slot N] [--name NAME] [--keep] [--skip-tier-a]
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SLOT=111
NAME="aurabot-scenario-golden"
KEEP=0
SKIP_TIER_A=0
LLM=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --slot) SLOT="$2"; shift 2;;
    --name) NAME="$2"; shift 2;;
    --keep) KEEP=1; shift;;
    --skip-tier-a) SKIP_TIER_A=1; shift;;
    --llm) LLM="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

# --llm qianwen|deepseek — deterministic live-provider choice. With both
# DASHSCOPE_API_KEY and DEEPSEEK_API_KEY in the environment, CloudConfigSeeder
# provisions both and chat resolution silently picks by seed priority
# (deepseek=30 beats qianwen=50) — which mislabeled a whole live run as
# "Qwen" on 2026-07-19. Choosing a provider simply withholds the other
# vendor's key from the backend env, so the seeder provisions exactly one.
# Implies live mode (stub off) unless AGENT_LLM_STUB_MODE is set explicitly.
case "$LLM" in
  "") ;;
  qianwen|qwen)
    [[ -n "${DASHSCOPE_API_KEY:-}" ]] || { echo "--llm qianwen requires DASHSCOPE_API_KEY" >&2; exit 2; }
    unset DEEPSEEK_API_KEY
    export AGENT_LLM_STUB_MODE="${AGENT_LLM_STUB_MODE:-false}"
    ;;
  deepseek)
    [[ -n "${DEEPSEEK_API_KEY:-}" ]] || { echo "--llm deepseek requires DEEPSEEK_API_KEY" >&2; exit 2; }
    unset DASHSCOPE_API_KEY
    export AGENT_LLM_STUB_MODE="${AGENT_LLM_STUB_MODE:-false}"
    ;;
  *) echo "unknown --llm '$LLM' (qianwen|deepseek)" >&2; exit 2;;
esac
RUNTIME="${NAME}-${SLOT}"
BE_PORT=$((6400 + SLOT))
DB="auraboot_${SLOT}"
ADMIN_EMAIL="${GOLDEN_ADMIN_EMAIL:-admin@auraboot.com}"
ADMIN_PASSWORD="${GOLDEN_ADMIN_PASSWORD:-Test2026x}"

PASS=()
FAIL=()
step() { echo "[scenario-golden] $*"; }
ok()   { PASS+=("$1"); step "PASS  $1"; }
bad()  { FAIL+=("$1"); step "FAIL  $1"; }

# --- Tier A: mechanism suite (pure unit, no stack, no shared DB) ----------
TIER_A_CLASSES=(
  "com.auraboot.framework.agent.triage.DefaultPreGroundingTriageTest"
  "com.auraboot.framework.agent.runtime.TurnExecutionPlannerTest"
  "com.auraboot.framework.agent.runtime.ChatTurnRuntimeTest"
  "com.auraboot.framework.aurabot.service.AuraBotChatToolRuntimeAdapterEnvelopeTest"
  "com.auraboot.framework.conversation.TurnCompletionObservationListenerTest"
  "com.auraboot.framework.conversation.TurnCompletionMemoryListenerTest"
  "com.auraboot.framework.conversation.ConversationTurnServiceImplTriageFallbackTest"
)
if [[ "$SKIP_TIER_A" -eq 0 ]]; then
  step "Tier A: mechanism suite (${#TIER_A_CLASSES[@]} classes)"
  TESTS_ARGS=()
  for c in "${TIER_A_CLASSES[@]}"; do TESTS_ARGS+=(--tests "$c"); done
  ( cd "$REPO_ROOT" && unset MAVEN_OPTS GRADLE_OPTS MAVEN_REPO_LOCAL && \
    ./platform/gradlew -p platform :test "${TESTS_ARGS[@]}" -q ) || true
  tier_a_ok=1
  for c in "${TIER_A_CLASSES[@]}"; do
    xml="$REPO_ROOT/platform/build/test-results/test/TEST-${c}.xml"
    if [[ ! -f "$xml" ]]; then
      bad "TierA ${c##*.}: no test-results XML (did not run)"
      tier_a_ok=0
      continue
    fi
    fails=$(grep -c "<failure\|<error" "$xml" || true)
    total=$(grep -c "<testcase" "$xml" || true)
    if [[ "$fails" != "0" ]]; then
      bad "TierA ${c##*.}: ${fails}/${total} failing"
      tier_a_ok=0
    fi
  done
  [[ "$tier_a_ok" -eq 1 ]] && ok "TierA mechanism suite green (XML-verified)"
fi

# --- Tier B: live scenario golden on an isolated stack --------------------
# Evidence-preservation policy (owner rule, 2026-07-19): auto-teardown is
# ONLY for the stub-mode all-green gate run. A red run keeps the stack (the
# failure forensics ARE the point); a live-LLM run keeps the stack (it is
# verification evidence the owner may want to inspect — destroying a
# verification environment destroys its evidence). Manual cleanup:
#   ./scripts/oss-golden-stack.sh destroy <runtime>
teardown() {
  local failed=${#FAIL[@]}
  local live=0
  [[ "${AGENT_LLM_STUB_MODE:-true}" != "true" ]] && live=1
  if [[ "$KEEP" -eq 1 || "$failed" -gt 0 || "$live" -eq 1 ]]; then
    step "KEEPING stack '$RUNTIME' (slot $SLOT): keep=$KEEP failed=$failed live=$live"
    step "  entry: backend http://localhost:$BE_PORT  db=$DB  logs=.workspace/golden/$RUNTIME/"
    step "  destroy manually when done: ./scripts/oss-golden-stack.sh destroy $RUNTIME"
  else
    step "teardown: destroying '$RUNTIME' (stub-mode all-green gate run)"
    "$REPO_ROOT/scripts/oss-golden-stack.sh" destroy "$RUNTIME" >/dev/null 2>&1 || true
  fi
}
trap teardown EXIT

step "Tier B: bring up isolated stack '$RUNTIME' (slot $SLOT, fresh DB, no frontend)"
"$REPO_ROOT/scripts/oss-golden-stack.sh" destroy "$RUNTIME" >/dev/null 2>&1 || true
if ! "$REPO_ROOT/scripts/oss-golden-stack.sh" up "$RUNTIME" --slot "$SLOT" --no-frontend --fresh-db --ttl 2h; then
  bad "stack bring-up failed"
  step "RESULT: ${#PASS[@]} pass / ${#FAIL[@]} fail"; exit 1
fi

health=$(curl -s --noproxy '*' "http://localhost:${BE_PORT}/actuator/health" | head -c 40 || true)
if [[ "$health" != *'"UP"'* ]]; then
  bad "backend health not UP on :${BE_PORT} ($health)"
  step "RESULT: ${#PASS[@]} pass / ${#FAIL[@]} fail"; exit 1
fi
ok "stack UP on :${BE_PORT}"

TOK=$(curl -s --noproxy '*' -X POST "http://localhost:${BE_PORT}/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}" \
      | python3 -c "import sys,json;d=json.load(sys.stdin);print(d.get('data',{}).get('jwt') or d.get('data',{}).get('token') or '')")
if [[ -z "$TOK" ]]; then
  bad "admin login returned no JWT"
  step "RESULT: ${#PASS[@]} pass / ${#FAIL[@]} fail"; exit 1
fi
ok "admin JWT obtained"

drive_turn() { # $1=label $2=message $3=agentCode-or-empty $4=options-json-or-empty
  local label="$1" msg="$2" agent="$3" opts="$4"
  local body="{\"message\":\"${msg}\",\"sessionId\":\"golden-${label}-$$\""
  [[ -n "$agent" ]] && body+=",\"agentCode\":\"${agent}\""
  body+="}"
  # options ride on the TurnRequest via the controller's request mapping;
  # ChatRequest carries them under "options".
  if [[ -n "$opts" ]]; then
    body="${body%\}},\"options\":${opts}}"
  fi
  local out
  out=$(curl -s --noproxy '*' -N --max-time 150 -X POST \
          "http://localhost:${BE_PORT}/api/ai/aurabot/chat/stream" \
          -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
          -H 'Accept: text/event-stream' -d "$body" || true)
  echo "$out" | grep -q "event:" || step "warn: turn '$label' produced no SSE events"
  step "turn '$label' driven"
}

step "driving scenario turns"
drive_turn S1 "查询本月订单统计" "" ""
drive_turn S2 "创建一个客户" "" ""
drive_turn S3 "批量删除过期线索" "" ""
drive_turn S4 "为什么导出会失败" "" ""
drive_turn S6 "帮我跑月度对账" "scenario_golden_missing_agent" ""
sleep 4  # async listeners drain

q() { PGPASSWORD="${PGPASSWORD:-auraboot}" psql -h localhost -U auraboot -d "$DB" -t -A -c "$1" 2>/dev/null | tr -d ' '; }

assert_eq() { # $1=label $2=actual $3=expected
  if [[ "$2" == "$3" ]]; then ok "$1"; else bad "$1 (got '$2', want '$3')"; fi
}
assert_ge() {
  if [[ -n "$2" && "$2" -ge "$3" ]] 2>/dev/null; then ok "$1"; else bad "$1 (got '$2', want >=$3)"; fi
}

step "asserting evidence tables"
assert_eq "G1 seam: one observation row per terminal turn (5)" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation")" "5"
assert_eq "G1 seam: every row carries route telemetry" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb ? 'triageBucket' AND detail::jsonb ? 'initialMode' AND detail::jsonb ? 'decisionReason' AND detail::jsonb ? 'latencyMs'")" "5"
assert_eq "S1 员工问数: CONTEXTUAL + read-only tier" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb->>'triageBucket'='CONTEXTUAL_ANSWER' AND detail::jsonb->>'decisionReason'='SYNC_READ_ONLY_TURN'")" "1"
assert_eq "S2 对话建单: SYNC_ACTION routed" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb->>'triageBucket'='SYNC_ACTION' AND detail::jsonb->>'decisionReason'='SYNC_ACTION_TURN'")" "1"
assert_eq "S2 记忆: L1 row importance=4 written" \
  "$(q "SELECT COUNT(*) FROM ab_agent_memory WHERE category='conversation_turn' AND importance=4")" "1"
assert_eq "S3 后台任务: ACP_RUN routed (exactly one durable turn)" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb->>'triageBucket'='ACP_RUN'")" "1"
assert_ge "S3 后台任务: ab_agent_task row created" \
  "$(q "SELECT COUNT(*) FROM ab_agent_task WHERE assignee_type='ai'")" 1
# S4's whole point IS the S3 assertion above: the explain-prefixed 导出
# message must NOT have produced a second ACP_RUN row. Make it explicit:
assert_eq "S4 咨询不误触发: explain-prefixed turn stayed non-durable" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb->>'triageBucket'='LIGHT_CHAT'")" "2"
assert_eq "S6 具名路由: NAMED_AGENT_TURN routed and failure observed" \
  "$(q "SELECT COUNT(*) FROM ab_agent_observation WHERE detail::jsonb->>'initialMode'='NAMED_AGENT_TURN' AND obs_title LIKE 'turn.failed%' AND detail::jsonb->>'error' LIKE '%not found%'")" "1"

echo
step "================ RESULT ================"
step "pass: ${#PASS[@]}  fail: ${#FAIL[@]}"
for f in "${FAIL[@]:-}"; do [[ -n "$f" ]] && step "  FAILED: $f"; done
if [[ ${#FAIL[@]} -gt 0 ]]; then exit 1; fi
step "AuraBot scenario golden: ALL GREEN"
exit 0
