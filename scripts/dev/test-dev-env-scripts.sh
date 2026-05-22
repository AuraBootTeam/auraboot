#!/bin/bash
#
# Smoke tests for scripts/dev environment helpers.
#
# This test is intentionally non-mutating:
#   - start scripts run with --dry-run
#   - no Docker containers are started
#   - temporary registry and full-stack env files are removed on exit

set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
STACK_DIR="$PROJECT_ROOT/.aura-stack"

PASS=0
FAIL=0
TMP_FILES=()
TMP_DIRS=()

cleanup() {
    for file in "${TMP_FILES[@]}"; do
        rm -f "$file"
    done
    for dir in "${TMP_DIRS[@]}"; do
        rm -rf "$dir"
    done
}
trap cleanup EXIT

pass() {
    echo "  PASS: $1"
    PASS=$((PASS + 1))
}

fail() {
    echo "  FAIL: $1"
    FAIL=$((FAIL + 1))
}

assert_exit() {
    local description="$1"
    local expected="$2"
    local actual="$3"
    if [ "$actual" -eq "$expected" ]; then
        pass "$description (exit=$actual)"
    else
        fail "$description (expected exit=$expected, got $actual)"
    fi
}

assert_contains() {
    local description="$1"
    local haystack="$2"
    local needle="$3"
    case "$haystack" in
        *"$needle"*) pass "$description" ;;
        *) fail "$description (missing: $needle)" ;;
    esac
}

assert_not_exists() {
    local description="$1"
    local path="$2"
    if [ ! -e "$path" ]; then
        pass "$description"
    else
        fail "$description (exists: $path)"
    fi
}

echo "Scenario 1: start-isolated dry-run is read-only"
DRY_FULL_ENV="$STACK_DIR/scriptcheck-full.env"
rm -f "$DRY_FULL_ENV"
(
    cd "$PROJECT_ROOT"
    scripts/dev/start-isolated.sh --slug=scriptcheck-full --dry-run >/tmp/dev-env-full-$$.out
)
assert_exit "start-isolated dry-run exit" 0 $?
assert_not_exists "start-isolated dry-run did not write env" "$DRY_FULL_ENV"
assert_contains "start-isolated dry-run printed marker" "$(cat /tmp/dev-env-full-$$.out)" "(dry-run mode: not starting docker)"
rm -f /tmp/dev-env-full-$$.out

echo "Scenario 2: start-dev-infra dry-run is read-only"
DRY_INFRA_ENV="$STACK_DIR/scriptcheck-infra.env"
DRY_INFRA_REGISTRY_ROOT="$(mktemp -d)"
TMP_DIRS+=("$DRY_INFRA_REGISTRY_ROOT")
rm -f "$DRY_INFRA_ENV"
(
    cd "$PROJECT_ROOT"
    AURA_ENV_REGISTRY_ROOT="$DRY_INFRA_REGISTRY_ROOT" scripts/dev/start-dev-infra.sh --slug=scriptcheck-infra --with-storage --dry-run >/tmp/dev-env-infra-$$.out
)
assert_exit "start-dev-infra dry-run exit" 0 $?
assert_not_exists "start-dev-infra dry-run did not write env" "$DRY_INFRA_ENV"
assert_not_exists "start-dev-infra dry-run did not write registry env" "$DRY_INFRA_REGISTRY_ROOT/envs/scriptcheck-infra"
assert_contains "start-dev-infra dry-run printed marker" "$(cat /tmp/dev-env-infra-$$.out)" "(dry-run mode: not starting docker)"
rm -f /tmp/dev-env-infra-$$.out

echo "Scenario 3: r2-env-export loads real slug"
mkdir -p "$STACK_DIR"
R2_REGISTRY_ROOT="$(mktemp -d)"
TMP_DIRS+=("$R2_REGISTRY_ROOT")
(
    cd "$PROJECT_ROOT" &&
    node scripts/dev/lib/env-registry.mjs upsert \
        --registry-root "$R2_REGISTRY_ROOT" \
        --slug scriptcheck-r2 \
        --mode bugfix \
        --product enterprise \
        --core-root "$PROJECT_ROOT" \
        --enterprise-root /tmp/enterprise-scriptcheck \
        --core-branch "$(git -C "$PROJECT_ROOT" branch --show-current)" \
        --enterprise-branch bugfix/enterprise-scriptcheck \
        --compose-project auraboot-scriptcheck-r2 \
        --status running \
        --pg-port 15432 \
        --redis-port 16379 \
        --be-port 16443 \
        --vite-port 15173 \
        --bff-port 13500 >/tmp/dev-env-registry-r2-$$.out
)
assert_exit "env registry fixture writes r2 env" 0 $?
rm -f /tmp/dev-env-registry-r2-$$.out
R2_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$R2_REGISTRY_ROOT" bash -lc 'source scripts/dev/r2-env-export.sh scriptcheck-r2 && printf " slug_env=%s backend=%s pg=%s artifacts=%s storage=%s admin_state=%s\n" "$AURA_ENV_SLUG" "$BACKEND_URL" "$PG_PORT" "$PW_ARTIFACT_DIR" "$PW_STORAGE_DIR" "$PW_ADMIN_STORAGE_STATE"'
)"
EXPECTED_AUTH_ROOT="$R2_REGISTRY_ROOT/envs/scriptcheck-r2/auth"
assert_contains "r2-env-export summary shows slug" "$R2_OUTPUT" "slug=scriptcheck-r2"
assert_contains "r2-env-export exports AURA_ENV_SLUG" "$R2_OUTPUT" "slug_env=scriptcheck-r2"
assert_contains "r2-env-export exports backend URL" "$R2_OUTPUT" "backend=http://localhost:16443"
assert_contains "r2-env-export exports PG port" "$R2_OUTPUT" "pg=15432"
assert_contains "r2-env-export exports slug-scoped artifacts" "$R2_OUTPUT" "artifacts=test-results/runs/scriptcheck-r2/"
assert_contains "r2-env-export exports private auth storage" "$R2_OUTPUT" "storage=$EXPECTED_AUTH_ROOT"
assert_contains "r2-env-export exports admin storage state" "$R2_OUTPUT" "admin_state=$EXPECTED_AUTH_ROOT/admin.json"

echo "Scenario 4: source-only helpers reject execution"
(
    cd "$PROJECT_ROOT"
    bash scripts/dev/r2-env-export.sh scriptcheck-r2 >/tmp/dev-env-r2-exec-$$.out 2>&1
)
assert_exit "r2-env-export executed directly exits 2" 2 $?
(
    cd "$PROJECT_ROOT"
    bash scripts/dev/maven-local-export.sh >/tmp/dev-env-maven-exec-$$.out 2>&1
)
assert_exit "maven-local-export executed directly exits 2" 2 $?
(
    cd "$PROJECT_ROOT"
    bash scripts/dev/lib/process-manager.sh >/tmp/dev-env-process-exec-$$.out 2>&1
)
assert_exit "process-manager executed directly exits 2" 2 $?
(
    cd "$PROJECT_ROOT"
    bash scripts/dev/lib/health.sh >/tmp/dev-env-health-exec-$$.out 2>&1
)
assert_exit "health helper executed directly exits 2" 2 $?
rm -f /tmp/dev-env-r2-exec-$$.out /tmp/dev-env-maven-exec-$$.out /tmp/dev-env-process-exec-$$.out /tmp/dev-env-health-exec-$$.out

echo "Scenario 5: maven-local-export sets per-worktree repo"
MAVEN_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    bash -lc 'source scripts/dev/maven-local-export.sh && printf " repo=%s opts=%s\n" "$AURA_MAVEN_REPO" "$GRADLE_OPTS"'
)"
assert_contains "maven-local-export sets repo under worktree" "$MAVEN_OUTPUT" "repo=$PROJECT_ROOT/.m2/repository"
assert_contains "maven-local-export sets Gradle option" "$MAVEN_OUTPUT" "-Dmaven.repo.local=$PROJECT_ROOT/.m2/repository"

echo "Scenario 6: start-isolated disk preflight fails before mutating stack"
if command -v docker >/dev/null 2>&1 && docker image inspect redis:7-alpine >/dev/null 2>&1; then
    DISK_ENV="$STACK_DIR/scriptcheck-disk.env"
    rm -f "$DISK_ENV"
    (
        cd "$PROJECT_ROOT"
        AURA_MIN_DOCKER_FREE_MB=999999 scripts/dev/start-isolated.sh --slug=scriptcheck-disk --skip-pull >/tmp/dev-env-disk-$$.out 2>&1
    )
    assert_exit "start-isolated disk preflight exit" 4 $?
    assert_not_exists "disk preflight did not write env" "$DISK_ENV"
    assert_contains "disk preflight suggested doctor-disk" "$(cat /tmp/dev-env-disk-$$.out)" "scripts/dev/doctor-disk.sh"
    rm -f /tmp/dev-env-disk-$$.out
else
    echo "  SKIP: docker or cached redis:7-alpine unavailable"
fi

echo "Scenario 7: cleanup includes optional runner profile"
CLEANUP_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    scripts/dev/cleanup-stack.sh --slug=scriptcheck-clean --volumes --images
)"
assert_contains "cleanup dry-run includes playwright-runner profile" "$CLEANUP_OUTPUT" "--profile playwright-runner"
assert_contains "cleanup dry-run includes production-like profile" "$CLEANUP_OUTPUT" "--profile production-like"
assert_contains "cleanup dry-run remains dry-run by default" "$CLEANUP_OUTPUT" "(dry-run mode: pass --apply to execute)"

echo "Scenario 8: production-like frontend dry-run uses existing full env"
PROD_ENV="$STACK_DIR/scriptcheck-prod.env"
TMP_FILES+=("$PROD_ENV")
cat > "$PROD_ENV" <<'ENV'
COMPOSE_PROJECT_NAME=auraboot-scriptcheck-prod
STACK_MODE=full
SLUG=scriptcheck-prod
OFFSET=11
PG_PORT=5444
BE_PORT=6455
VITE_PORT=5185
BFF_PORT=3512
PROD_FRONTEND_PORT=3012
REDIS_PORT=6490
ENTERPRISE_PLUGINS_DIR=/tmp
ENTERPRISE_PLUGIN_JARS_DIR=/tmp
ENV
(
    cd "$PROJECT_ROOT"
    scripts/dev/start-production-like.sh --slug=scriptcheck-prod --dry-run >/tmp/dev-env-prod-$$.out
)
assert_exit "start-production-like dry-run exit" 0 $?
assert_contains "start-production-like uses production profile" "$(cat /tmp/dev-env-prod-$$.out)" "--profile production-like"
assert_contains "start-production-like prints prod endpoint" "$(cat /tmp/dev-env-prod-$$.out)" "http://localhost:3012"
rm -f /tmp/dev-env-prod-$$.out

echo "Scenario 9: playwright runner refuses uncached image by default"
RUNNER_ENV="$STACK_DIR/scriptcheck-runner.env"
TMP_FILES+=("$RUNNER_ENV")
cat > "$RUNNER_ENV" <<'ENV'
COMPOSE_PROJECT_NAME=auraboot-scriptcheck-runner
STACK_MODE=full
SLUG=scriptcheck-runner
OFFSET=12
PG_PORT=5445
BE_PORT=6456
VITE_PORT=5186
BFF_PORT=3513
PROD_FRONTEND_PORT=3013
REDIS_PORT=6491
AURA_CONTAINER_CACHE_ROOT=/tmp/auraboot-container-cache-scriptcheck
PW_E2E_RUN_ID=scriptcheck
PW_E2E_RUN_ROOT=test-results/runs/scriptcheck-runner/scriptcheck
PW_ARTIFACT_DIR=test-results/runs/scriptcheck-runner/scriptcheck/artifacts
PW_REPORT_DIR=test-results/runs/scriptcheck-runner/scriptcheck/html-report
PW_RESULTS_JSON=test-results/runs/scriptcheck-runner/scriptcheck/results.json
PW_STORAGE_DIR=tests/storage/scriptcheck-runner/scriptcheck
ENV
if command -v docker >/dev/null 2>&1; then
    (
        cd "$PROJECT_ROOT"
        PLAYWRIGHT_RUNNER_IMAGE=auraboot/nonexistent-playwright-runner:scriptcheck scripts/dev/run-playwright-runner.sh --slug=scriptcheck-runner --dry-run >/tmp/dev-env-runner-$$.out 2>&1
    )
    assert_exit "run-playwright-runner rejects uncached image" 4 $?
    assert_contains "run-playwright-runner suggests doctor-disk" "$(cat /tmp/dev-env-runner-$$.out)" "scripts/dev/doctor-disk.sh"
    (
        cd "$PROJECT_ROOT"
        PLAYWRIGHT_RUNNER_IMAGE=auraboot/nonexistent-playwright-runner:scriptcheck scripts/dev/run-playwright-runner.sh --slug=scriptcheck-runner --allow-pull --dry-run >/tmp/dev-env-runner-allow-$$.out 2>&1
    )
    assert_exit "run-playwright-runner allow-pull dry-run exits" 0 $?
    assert_contains "run-playwright-runner dry-run prints profile" "$(cat /tmp/dev-env-runner-allow-$$.out)" "--profile playwright-runner"
    rm -f /tmp/dev-env-runner-$$.out /tmp/dev-env-runner-allow-$$.out
else
    echo "  SKIP: docker unavailable"
fi

echo "Scenario 10: artifact cleanup is scoped and dry-run by default"
ARTIFACT_RUN="$PROJECT_ROOT/web-admin/test-results/runs/scriptcheck-artifacts/20000101T000000Z"
ARTIFACT_STORAGE="$PROJECT_ROOT/web-admin/tests/storage/scriptcheck-artifacts/20000101T000000Z"
mkdir -p "$ARTIFACT_RUN" "$ARTIFACT_STORAGE"
touch -t 200001010000 "$ARTIFACT_RUN" "$ARTIFACT_STORAGE"
TMP_DIRS+=("$PROJECT_ROOT/web-admin/test-results/runs/scriptcheck-artifacts")
TMP_DIRS+=("$PROJECT_ROOT/web-admin/tests/storage/scriptcheck-artifacts")
ARTIFACT_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    scripts/dev/cleanup-artifacts.sh --slug=scriptcheck-artifacts --days=0
)"
assert_contains "artifact cleanup lists run dir" "$ARTIFACT_OUTPUT" "$ARTIFACT_RUN"
assert_contains "artifact cleanup lists storage dir" "$ARTIFACT_OUTPUT" "$ARTIFACT_STORAGE"
assert_contains "artifact cleanup remains dry-run by default" "$ARTIFACT_OUTPUT" "(dry-run mode: pass --apply to execute)"
if [ -d "$ARTIFACT_RUN" ] && [ -d "$ARTIFACT_STORAGE" ]; then
    pass "artifact cleanup dry-run preserved dirs"
else
    fail "artifact cleanup dry-run preserved dirs"
fi
(
    cd "$PROJECT_ROOT"
    scripts/dev/cleanup-artifacts.sh --slug=scriptcheck-artifacts --days=0 --apply >/tmp/dev-env-artifacts-$$.out
)
assert_exit "artifact cleanup apply exit" 0 $?
assert_not_exists "artifact cleanup apply removed run dir" "$ARTIFACT_RUN"
assert_not_exists "artifact cleanup apply removed storage dir" "$ARTIFACT_STORAGE"
rm -f /tmp/dev-env-artifacts-$$.out

echo "Scenario 11: unified env start dry-run plans bugfix host stack without mutating"
DRY_ENV_REGISTRY_ROOT="$(mktemp -d)"
TMP_DIRS+=("$DRY_ENV_REGISTRY_ROOT")
(
    cd "$PROJECT_ROOT"
    AURA_ENV_REGISTRY_ROOT="$DRY_ENV_REGISTRY_ROOT" scripts/dev/env.sh start --mode=bugfix --product=enterprise --slug=scriptcheck-env --dry-run >/tmp/dev-env-unified-start-$$.out
)
assert_exit "env start dry-run exit" 0 $?
assert_not_exists "env start dry-run did not write registry env" "$DRY_ENV_REGISTRY_ROOT/envs/scriptcheck-env"
UNIFIED_START_OUTPUT="$(cat /tmp/dev-env-unified-start-$$.out)"
assert_contains "env start dry-run delegates infra plan" "$UNIFIED_START_OUTPUT" "scripts/dev/start-dev-infra.sh --slug=scriptcheck-env --product=enterprise --dry-run"
assert_contains "env start dry-run names backend session" "$UNIFIED_START_OUTPUT" "auraboot-scriptcheck-env-backend"
assert_contains "env start dry-run names frontend session" "$UNIFIED_START_OUTPUT" "auraboot-scriptcheck-env-frontend"
assert_contains "env start dry-run disables startup bootstrap" "$UNIFIED_START_OUTPUT" "--auraboot.bootstrap.enabled=false"
rm -f /tmp/dev-env-unified-start-$$.out

echo "Scenario 12: unified env status reads slug env"
REGISTRY_ROOT="$(mktemp -d)"
TMP_DIRS+=("$REGISTRY_ROOT")
(
    cd "$PROJECT_ROOT" &&
    node scripts/dev/lib/env-registry.mjs upsert \
        --registry-root "$REGISTRY_ROOT" \
        --slug scriptcheck-r2 \
        --mode bugfix \
        --product enterprise \
        --core-root "$PROJECT_ROOT" \
        --enterprise-root /tmp/enterprise-scriptcheck \
        --core-branch "$(git -C "$PROJECT_ROOT" branch --show-current)" \
        --enterprise-branch bugfix/enterprise-scriptcheck \
        --compose-project auraboot-scriptcheck-r2 \
        --status running \
        --pg-port 15432 \
        --redis-port 16379 \
        --be-port 16443 \
        --vite-port 15173 \
        --bff-port 13500 >/tmp/dev-env-registry-status-$$.out
)
assert_exit "env registry fixture writes status env" 0 $?
assert_contains "env registry writes manifest" "$(cat /tmp/dev-env-registry-status-$$.out)" "$REGISTRY_ROOT/envs/scriptcheck-r2/manifest.json"
assert_contains "env registry writes private auth root" "$(cat /tmp/dev-env-registry-status-$$.out)" "$REGISTRY_ROOT/envs/scriptcheck-r2/auth"
assert_contains "env registry writes exports file" "$(cat /tmp/dev-env-registry-status-$$.out)" "$REGISTRY_ROOT/envs/scriptcheck-r2/exports.env"
rm -f /tmp/dev-env-registry-status-$$.out
assert_contains "env list shows registered slug" "$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh list
)" "scriptcheck-r2"
INSPECT_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh inspect --slug=scriptcheck-r2
)"
assert_contains "env inspect includes auth root" "$INSPECT_OUTPUT" "\"authRoot\":\"$REGISTRY_ROOT/envs/scriptcheck-r2/auth\""
assert_contains "env inspect includes branch" "$INSPECT_OUTPUT" "\"coreBranch\":\"bugfix/daily-core\""

REUSE_START_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh start --mode=bugfix --product=enterprise --slug=scriptcheck-r2 --dry-run
)"
assert_contains "env start dry-run reuses existing registry env" "$REUSE_START_OUTPUT" "existing registry exports: $REGISTRY_ROOT/envs/scriptcheck-r2/exports.env"
assert_contains "env start dry-run uses registered enterprise root" "$REUSE_START_OUTPUT" "backend root:     /tmp/enterprise-scriptcheck/platform"
assert_contains "env start dry-run plans compose up for existing infra" "$REUSE_START_OUTPUT" "docker compose -p auraboot-scriptcheck-r2"
case "$REUSE_START_OUTPUT" in
    *" minio"*) fail "env start dry-run does not start storage without --with-storage" ;;
    *) pass "env start dry-run does not start storage without --with-storage" ;;
esac

STATUS_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh status --slug=scriptcheck-r2
)"
assert_contains "env status includes slug" "$STATUS_OUTPUT" '"slug":"scriptcheck-r2"'
assert_contains "env status includes backend port" "$STATUS_OUTPUT" '"be":"16443"'
assert_contains "env status includes postgres port" "$STATUS_OUTPUT" '"pg":"15432"'
assert_contains "env status includes port listener summary" "$STATUS_OUTPUT" "ports be="
STATUS_OUTPUT_FROM_OTHER_CWD="$(
    cd /tmp &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" "$PROJECT_ROOT/scripts/dev/env.sh" status --slug=scriptcheck-r2
)"
assert_contains "env status anchors env file to script project root" "$STATUS_OUTPUT_FROM_OTHER_CWD" '"slug":"scriptcheck-r2"'

echo "Scenario 13: unified env reset dry-run is explicit and does not stop host processes"
RESET_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh reset --mode=bugfix --product=enterprise --slug=scriptcheck-r2 --dry-run
)"
assert_contains "env reset dry-run names enterprise reset script" "$RESET_OUTPUT" "scripts/reset-db.sh"
assert_contains "env reset dry-run uses registered enterprise root" "$RESET_OUTPUT" "/tmp/enterprise-scriptcheck/scripts/reset-db.sh"
assert_contains "env reset dry-run names isolated database" "$RESET_OUTPUT" "localhost:15432/aura_boot"
assert_contains "env reset dry-run documents no global process cleanup" "$RESET_OUTPUT" "no global pkill"
assert_contains "env reset dry-run documents bootstrap setup" "$RESET_OUTPUT" "/api/bootstrap/setup"

echo "Scenario 14: unified env stop dry-run uses exact tmux sessions and ports"
STOP_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh stop --slug=scriptcheck-r2 --dry-run
)"
assert_contains "env stop dry-run names backend session" "$STOP_OUTPUT" "auraboot-scriptcheck-r2-backend"
assert_contains "env stop dry-run names frontend session" "$STOP_OUTPUT" "auraboot-scriptcheck-r2-frontend"
assert_contains "env stop dry-run lists exact ports" "$STOP_OUTPUT" "16443 15173 13500"
assert_contains "env stop dry-run avoids global pkill" "$STOP_OUTPUT" "no global pkill"
assert_contains "env stop dry-run stops slug-scoped docker infra" "$STOP_OUTPUT" "scripts/dev/stop-isolated.sh --slug=scriptcheck-r2"
PURGE_STOP_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh stop --slug=scriptcheck-r2 --purge --dry-run
)"
assert_contains "env stop purge dry-run includes purge flag" "$PURGE_STOP_OUTPUT" "scripts/dev/stop-isolated.sh --slug=scriptcheck-r2 --purge"

echo "Scenario 15: unified env verify and logs dry-runs expose health targets and log paths"
VERIFY_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh verify --level=health --slug=scriptcheck-r2 --dry-run
)"
assert_contains "env verify dry-run includes backend health" "$VERIFY_OUTPUT" "http://localhost:16443/actuator/health"
assert_contains "env verify dry-run includes frontend URL" "$VERIFY_OUTPUT" "http://localhost:15173"
LOGS_OUTPUT="$(
    cd "$PROJECT_ROOT" &&
    AURA_ENV_REGISTRY_ROOT="$REGISTRY_ROOT" scripts/dev/env.sh logs --slug=scriptcheck-r2 --service=frontend
)"
assert_contains "env logs prints frontend log path" "$LOGS_OUTPUT" "/tmp/aura-scriptcheck-r2-frontend.log"

echo ""
echo "==========================================="
echo "  Results: $PASS passed, $FAIL failed"
echo "==========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
exit 0
