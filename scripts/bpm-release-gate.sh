#!/usr/bin/env bash
#
# bpm-release-gate.sh — BPM 发布门禁编排器(v1, host-first)
#
# 分母 SOT: workspace docs/plans/2026-09-03-bpm-functional-acceptance-matrix.md
#
# 分层:
#   P1 环境   — oss-reset-and-init.sh 重建 + schema/health 校验(失败=environment-invalid)
#   P2 冒烟   — tests/e2e/bpm-smoke/                    (硬门)
#   P3 旅程   — tests/e2e/bpm-showcase/  @oss 20 用例    (硬门)
#   P4 设计器 — tests/e2e/bpm-designer/ 顶层 spec        (硬门)
#   P5 契约   — tests/e2e/bpm/ (PW_PROFILE=contract)    (advisory:如实上报,不拦发布;
#                                                          首轮实测 28 failed,收敛后升硬门)
#
# 判定:硬门(P1-P4)任一失败,或出现未在 pins 清单登记的 skip → exit 非零;
#       P5 结果原样写入结论行,供发布决策者直视。
#
# 用法:
#   ./scripts/bpm-release-gate.sh                    # 全量:重建环境 + 三层硬门
#   ./scripts/bpm-release-gate.sh --skip-reset       # 复用已验证 runtime(P1 仅校验)
#   ./scripts/bpm-release-gate.sh --suite=smoke,showcase,designer,bpm-api
#
# 环境契约(与 oss-reset-and-init.sh 相同,变量名错误会全量假阴性):
#   PG_HOST/PG_PORT/PG_USER/PG_DB/PGPASSWORD、BE_PORT/VITE_PORT/BFF_PORT、
#   AURA_RESET_ALLOW_TARGETS(必含 "<PG_DB>,<BE_PORT>")、AURA_RESET_RUNTIME_LABEL、
#   PLUGIN_IMPORT_PROFILE=demo、FORCE_HOST=1(多 worktree 宿主机需显式声明)。
#
# Pins 清单: scripts/bpm-release-gate.pins.json — 硬门中任何 skip 的测试标题必须
# 匹配至少一条 pin(pattern 为正则),否则门禁失败。S1.6 翻转程序:B(SmartEngine
# rollbackTask)落地的同一 PR 内改写 s1-approval-core S1.6 断言并删除对应 pin 条目。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
WEB_ADMIN_DIR="$PROJECT_ROOT/web-admin"
PLATFORM_DIR="$PROJECT_ROOT/platform"
COMMON_GIT_DIR="$(git -C "$PROJECT_ROOT" rev-parse --path-format=absolute --git-common-dir)"
WS_ROOT="$(cd "$(dirname "$COMMON_GIT_DIR")/.." && pwd)"
PINS_FILE="$SCRIPT_DIR/bpm-release-gate.pins.json"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'

SKIP_RESET=0
SUITES="smoke,showcase,designer,bpm-api"
for arg in "$@"; do
    case "$arg" in
        --skip-reset) SKIP_RESET=1 ;;
        --suite=*) SUITES="${arg#--suite=}" ;;
        --help|-h) sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "ERROR: unknown argument: $arg" >&2; exit 2 ;;
    esac
done

PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-auraboot}"
PG_DB="${PG_DB:-auraboot_63}"
export PG_HOST PG_PORT PG_USER PG_DB
export PGPASSWORD="${PGPASSWORD:-auraboot}"
BE_PORT="${BE_PORT:-6463}"
VITE_PORT="${VITE_PORT:-5163}"
BFF_PORT="${BFF_PORT:-6163}"
export BE_PORT VITE_PORT BFF_PORT
AURA_BE_BASE="http://localhost:${BE_PORT}"
AURA_BFF_BASE="http://localhost:${BFF_PORT}"
AURA_VITE_BASE="http://localhost:${VITE_PORT}"
export AURA_BE_BASE AURA_BFF_BASE AURA_VITE_BASE
export AURA_RESET_RUNTIME_LABEL="${AURA_RESET_RUNTIME_LABEL:-bpm-showcase-e2e2}"
export PLUGIN_IMPORT_PROFILE="${PLUGIN_IMPORT_PROFILE:-demo}"
export PLAYWRIGHT_BASE_URL="$AURA_VITE_BASE"
export BACKEND_URL="$AURA_BE_BASE"
export PW_SKIP_WEBSERVER=1
export NO_PROXY="127.0.0.1,localhost"
export PW_WORKERS="${PW_WORKERS:-1}"

GATE_LABEL="${BPM_GATE_LABEL:-$AURA_RESET_RUNTIME_LABEL}"
EVIDENCE_ROOT="${BPM_GATE_EVIDENCE_ROOT:-$WS_ROOT/.workspace/evidence/$GATE_LABEL}"
TS="$(date +%Y%m%dT%H%M%S%z)"
EVIDENCE_DIR="$EVIDENCE_ROOT/$TS"
mkdir -p "$EVIDENCE_DIR"

want_suite() { case ",$SUITES," in *",$1,"*) return 0 ;; *) return 1 ;; esac; }
phase() { echo -e "${BLUE}=== [BPM-GATE] $* ===${NC}"; }
die() { echo -e "${RED}=== [BPM-GATE] GATE FAIL: $* ===${NC}" >&2; exit 1; }

strip_ansi() { sed 's/\x1b\[[0-9;]*m//g'; }

# run_suite <name> <config> <project> <filter> <profile>
# 产物: $EVIDENCE_DIR/suite-<name>.log(已去 ANSI,末行 SUITE_EXIT=N)
#        $EVIDENCE_DIR/notrun-<name>.txt(未执行用例行,含 serial 级联)
# 解析按项目标记精确计数(list reporter:✓/✘/- 均带 [project] 前缀;
# 全局汇总行会被 auth/setup 项目污染,不可用)。SUITE_EXIT/S_PASSED/S_FAILED/
# S_NOTRUN 为输出约定。
run_suite() {
    local name="$1" config="$2" project="$3" filter="$4" profile="$5"
    local log="$EVIDENCE_DIR/suite-$name.log"
    phase "L:$name → $filter"
    (
        cd "$WEB_ADMIN_DIR"
        {
            PW_PROFILE="$profile" npx playwright test -c "$config" --project="$project" --reporter=list "$filter" 2>&1
            echo "SUITE_EXIT=$?"
        } | strip_ansi | tee "$log" | tail -4
    ) || true
    SUITE_EXIT="$(grep -E '^SUITE_EXIT=[0-9]+$' "$log" | tail -1 | cut -d= -f2)"
    S_PASSED="$(grep -cE "✓.*\\[$project\\]" "$log" || true)"
    S_FAILED="$(grep -cE "✘.*\\[$project\\]" "$log" || true)"
    grep -E "^[[:space:]]*-[[:space:]]+[0-9]+.*\\[$project\\]" "$log" > "$EVIDENCE_DIR/notrun-$name.txt" || true
    S_NOTRUN="$(wc -l < "$EVIDENCE_DIR/notrun-$name.txt" | tr -d ' ')"
    echo -e "   ${GREEN}$name: passed=$S_PASSED failed=$S_FAILED not_run=$S_NOTRUN exit=${SUITE_EXIT}${NC}"
}

# ============ P0 前置 ============
phase "P0 前置检查"
command -v psql >/dev/null 2>&1 || { echo "ERROR: psql 不在 PATH(需 /opt/homebrew/opt/postgresql@17/bin)" >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "ERROR: npx 不在 PATH" >&2; exit 2; }
[ -f "$WEB_ADMIN_DIR/playwright.oss.config.ts" ] || die "web-admin/playwright.oss.config.ts 不存在"
[ -f "$PINS_FILE" ] || die "pins 清单缺失: $PINS_FILE"
echo "   label=$GATE_LABEL suites=$SUITES skip_reset=$SKIP_RESET"
echo "   evidence=$EVIDENCE_DIR"

# ============ P1 环境(environment-invalid 即刻分类) ============
if [ "$SKIP_RESET" = "0" ]; then
    phase "P1 重建环境(oss-reset-and-init.sh)"
    (
        cd "$PROJECT_ROOT"
        set +e
        ./scripts/oss-reset-and-init.sh 2>&1 | tee "$EVIDENCE_DIR/reset.log" | tail -3
        RC=${PIPESTATUS[0]}
        echo "RESET_EXIT=$RC" >> "$EVIDENCE_DIR/reset.log"
        exit "$RC"
    ) || die "reset 失败 → environment-invalid(证据: $EVIDENCE_DIR/reset.log)"
else
    phase "P1 复用既有 runtime(--skip-reset)"
fi

phase "P1 环境 gate(health + schema + storage state)"
P1_LOG="$EVIDENCE_DIR/p1-env-gate.log"
BE_HEALTH="$(curl --noproxy '*' -s -m 10 "$AURA_BE_BASE/actuator/health" || echo ERR)"
VITE_CODE="$(curl --noproxy '*' -s -m 10 -o /dev/null -w '%{http_code}' "$AURA_VITE_BASE/dashboards" || echo ERR)"
BFF_HEALTH="$(curl --noproxy '*' -s -m 10 "$AURA_BFF_BASE/health" || echo ERR)"
SE_COUNT="$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_name='se_notification_instance'" 2>&1)"
NOTIFY_COLS="$(psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" -tAc \
    "SELECT string_agg(column_name,',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='ab_bpm_notify_record'" 2>&1)"
if [ -f "$WEB_ADMIN_DIR/tests/storage/admin.json" ]; then STORAGE_STATE=present; else STORAGE_STATE=MISSING; fi
{
    echo "=== P1 environment gate $(date '+%F %T %z') ==="
    echo "backend: $BE_HEALTH"
    echo "vite: $VITE_CODE"
    echo "bff: $BFF_HEALTH"
    echo "se_notification_instance count = $SE_COUNT"
    echo "ab_bpm_notify_record cols = $NOTIFY_COLS"
    echo "storage state: $STORAGE_STATE"
} 2>&1 | tee "$P1_LOG"

echo "$BE_HEALTH" | grep -q '"status":"UP"' \
    || die "P1: backend not UP → environment-invalid(见 $P1_LOG)"
echo "$BFF_HEALTH" | grep -q '"springBoot":{"status":"healthy"' \
    || die "P1: BFF→backend 链路不健康 → environment-invalid(见 $P1_LOG)"
[ "$SE_COUNT" = "0" ] || die "P1: se_notification_instance 应不存在 → schema gate 失败"
for col in title source_type source_ref dedup_key; do
    case "$NOTIFY_COLS" in *"$col"*) ;; *) die "P1: ab_bpm_notify_record 缺列 $col" ;; esac
done
[ "$STORAGE_STATE" = "present" ] || die "P1: storage state 缺失(需先跑一次含 Step 6 的 reset)"
echo -e "${GREEN}   P1 environment gate PASS${NC}"

# ============ P2-P4 硬门 / P5 advisory ============
HARD_FAILED_TIERS=0
HARD_PASSED=0
HARD_TOTAL=0
NOTRUN_ALL="$EVIDENCE_DIR/notrun-all.txt"
: > "$NOTRUN_ALL"

tier_result() { # $1 tier name, $2 log, $3 project-marker
    if [ "$SUITE_EXIT" != "0" ] || [ "$S_FAILED" != "0" ]; then
        HARD_FAILED_TIERS=$((HARD_FAILED_TIERS+1))
    fi
    HARD_PASSED=$((HARD_PASSED+S_PASSED))
    HARD_TOTAL=$((HARD_TOTAL+S_PASSED+S_FAILED+S_NOTRUN))
    grep -E "^[[:space:]]*-[[:space:]]+[0-9]+.*\\[$3\\]" "$2" >> "$NOTRUN_ALL" || true
}

if want_suite smoke; then
    run_suite "smoke" playwright.oss.config.ts oss "tests/e2e/bpm-smoke/" oss
    tier_result "smoke" "$EVIDENCE_DIR/suite-smoke.log" "oss"
fi

if want_suite showcase; then
    run_suite "showcase" playwright.oss.config.ts oss "tests/e2e/bpm-showcase/" oss
    tier_result "showcase" "$EVIDENCE_DIR/suite-showcase.log" "oss"
fi

if want_suite designer; then
    run_suite "designer" playwright.oss.config.ts oss "tests/e2e/bpm-designer/[^/]+\.spec\.ts" oss
    tier_result "designer" "$EVIDENCE_DIR/suite-designer.log" "oss"
fi

ADVISORY_LINE="l5=advisory:not-run"
if want_suite bpm-api; then
    # advisory 层:contract profile 的 bpm/ 契约套件。失败不拦门禁,但必须原样进结论
    #(首轮实测:214 用例 / 85 passed / 28 failed / 10 skipped / 91 did not run;
    # 失败聚类与收敛计划见验收矩阵 §5/§8,收敛完成后本层升硬门。)
    run_suite "bpm-api" playwright.config.ts contract "e2e/bpm/" contract
    ADVISORY_LINE="l5=advisory:passed=$S_PASSED failed=$S_FAILED not_run=$S_NOTRUN exit=$SUITE_EXIT"
fi

# ============ pins 对账:硬门 not-run 必须有主(或归因于同文件失败的 serial 级联) ============
UNPINNED=0
CASCADE=0
if [ -s "$NOTRUN_ALL" ]; then
    phase "Pins 对账(硬门 not-run 必须登记或为失败级联)"
    FAILED_FILES=""
    for f in "$EVIDENCE_DIR"/suite-smoke.log "$EVIDENCE_DIR"/suite-showcase.log "$EVIDENCE_DIR"/suite-designer.log; do
        [ -f "$f" ] || continue
        FAILED_FILES="$FAILED_FILES$(grep -E "✘.*\\[(oss|contract)\\]" "$f" 2>/dev/null | grep -oE "tests/e2e/[^ ]+\.spec\.ts" || true)"
    done
    FAILED_FILES="$(echo "$FAILED_FILES" | sort -u)"
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        file="$(echo "$line" | grep -oE "tests/e2e/[^ ]+\.spec\.ts" | head -1)"
        if [ -n "$file" ] && echo "$FAILED_FILES" | grep -qF "$file"; then
            CASCADE=$((CASCADE+1))
            continue
        fi
        title="$(echo "$line" | sed 's/^[[:space:]]*-[[:space:]]*[0-9]*[[:space:]]*//')"
        if python3 - "$PINS_FILE" "$title" <<'PY' 2>/dev/null
import json, re, sys
pins = json.load(open(sys.argv[1]))
title = sys.argv[2]
for pin in pins:
    if pin.get("kind", "skip-allow") == "skip-allow" and re.search(pin["pattern"], title):
        print(f"   NOT-RUN(允许): {title}")
        print(f"     pin={pin['id']} — {pin['reason']}")
        sys.exit(0)
sys.exit(1)
PY
        then :; else
            echo -e "${RED}   NOT-RUN(无主,pins 清单未登记且非级联!): $title${NC}"
            UNPINNED=$((UNPINNED+1))
        fi
    done < "$NOTRUN_ALL"
    if [ "$UNPINNED" != "0" ]; then
        HARD_FAILED_TIERS=$((HARD_FAILED_TIERS+1))
    fi
    echo "   not-run 归因: 级联=$CASCADE 无主=$UNPINNED"
fi

# ============ 结论 ============
OSS_MAIN="$(git -C "$PROJECT_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
GATE_LINE="bpm-release-gate | label=$GATE_LABEL | hard: passed=$HARD_PASSED/$HARD_TOTAL failed_tiers=$HARD_FAILED_TIERS unpinned_notrun=$UNPINNED cascade=$CASCADE | $ADVISORY_LINE | oss_main=$OSS_MAIN"
{
    echo "=== BPM RELEASE GATE VERDICT $(date '+%F %T %z') ==="
    echo "$GATE_LINE"
    echo "evidence=$EVIDENCE_DIR"
    if [ "$HARD_FAILED_TIERS" = "0" ]; then
        echo "VERDICT=PASS(hard tiers green; advisory tier 见上行)"
    else
        echo "VERDICT=FAIL($HARD_FAILED_TIERS 个硬门层失败/违规)"
    fi
} 2>&1 | tee "$EVIDENCE_DIR/GATE-REPORT.txt"
echo ""
echo "$GATE_LINE"

[ "$HARD_FAILED_TIERS" = "0" ] || die "门禁未通过(详见 $EVIDENCE_DIR/GATE-REPORT.txt)"
echo -e "${GREEN}=== [BPM-GATE] VERDICT=PASS ===${NC}"
