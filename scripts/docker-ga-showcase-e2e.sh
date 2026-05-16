#!/usr/bin/env bash
# Run the GA showcase E2E gate from an isolated Docker Playwright runner.
#
# Usage:
#   ./scripts/docker-ga-showcase-e2e.sh              # up + bootstrap + seed + all
#   ./scripts/docker-ga-showcase-e2e.sh all
#   ./scripts/docker-ga-showcase-e2e.sh auth
#   ./scripts/docker-ga-showcase-e2e.sh chromium
#   ./scripts/docker-ga-showcase-e2e.sh deep
#   ./scripts/docker-ga-showcase-e2e.sh seed
#
# Environment toggles:
#   GA_E2E_SKIP_UP=1          Reuse the existing GA stack without calling up.sh.
#   GA_E2E_SKIP_BOOTSTRAP=1   Skip plugin/user bootstrap.
#   GA_E2E_SKIP_SEED=1        Skip showcase seed phases before all.
#   GA_E2E_AUTH_ONCE=0        Keep the old all-flow behavior and re-run auth after seed.
#   GA_E2E_CHROMIUM_WORKERS=N Default chromium workers for the Docker runner (default: 3).
#   GA_E2E_FORCE_PNPM_INSTALL=1 Re-run pnpm install even when dependency inputs are unchanged.

set -euo pipefail

if [[ "${1:-}" = "--" ]]; then
  shift
fi
PHASE="${1:-all}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

case "$PHASE" in
  auth|chromium|deep|seed|all) ;;
  *)
    echo "Usage: $0 [auth|chromium|deep|seed|all]" >&2
    exit 2
    ;;
esac

cd "$REPO_ROOT"

export COMPOSE_PROJECT_NAME=auraboot-ga-e2e

compose_args=(
  -f docker-compose.yml
  -f docker-compose.ga-e2e.override.yml
  --profile ga-e2e-stack
  --profile ga-e2e-runner
)

find_competing_host_runners() {
  ps -Ao pid=,command= \
    | grep -E 'playwright.*test|test:agent:crm' \
    | grep -v 'grep -E' \
    | grep -v '@playwright/mcp' \
    | grep -v "$$" \
    || true
}

ensure_no_host_runner() {
  local active
  active="$(find_competing_host_runners)"
  if [[ -n "$active" ]]; then
    echo "ERROR: another regular Playwright runner is active on the host." >&2
    echo "Stop it before running the Docker GA showcase gate:" >&2
    echo "$active" >&2
    exit 1
  fi
}

ensure_stack() {
  if [[ "${GA_E2E_SKIP_UP:-0}" = "1" ]]; then
    echo "[ga-showcase-docker] GA_E2E_SKIP_UP=1 - reusing existing stack"
  else
    "$SCRIPT_DIR/docker-ga-e2e-up.sh"
  fi

  if [[ "${GA_E2E_SKIP_BOOTSTRAP:-0}" = "1" ]]; then
    echo "[ga-showcase-docker] GA_E2E_SKIP_BOOTSTRAP=1 - skipping plugin/user bootstrap"
  else
    SKIP_SEED=1 "$SCRIPT_DIR/docker-ga-e2e-bootstrap.sh"
  fi
}

runner_command() {
  local phase="$1"
  local seed_block=""

  if [[ "$phase" = "seed" || ( "$phase" = "all" && "${GA_E2E_SKIP_SEED:-0}" != "1" ) ]]; then
    seed_block='
      echo "[ga-showcase-docker] refreshing docker-runner auth storage for seed";
      cd /repo/web-admin;
      rm -rf tests/storage/ga-docker;
      ../scripts/ga-showcase-e2e.sh auth;
      seeds=(data extended workflow ai arsenal supplement);
      case "${SHOWCASE_COMMERCIAL_SEED:-auto}" in
        skip)
          echo "[ga-showcase-docker] seed-showcase-commercial SKIP (SHOWCASE_COMMERCIAL_SEED=skip)";
          ;;
        required)
          seeds+=(commercial);
          ;;
        auto|"")
          echo "[ga-showcase-docker] seed-showcase-commercial SKIP (OSS crm-starter lacks full CRM quote/complaint commands)";
          ;;
        *)
          echo "[ga-showcase-docker] SHOWCASE_COMMERCIAL_SEED must be auto|required|skip" >&2;
          exit 1;
          ;;
      esac;
      seeds+=(dashboard-default invariants);
      SHOWCASE_DEFAULT_DASHBOARD_CODE="${SHOWCASE_DEFAULT_DASHBOARD_CODE:-crm_overview}" \
        NO_PROXY="${NO_PROXY:-localhost,127.0.0.1}" \
        node scripts/run-showcase-seed-sequence.mjs \
          --output-prefix=test-results/ga-docker-seed "${seeds[@]}";
    '
  elif [[ "$phase" = "all" ]]; then
    seed_block='echo "[ga-showcase-docker] GA_E2E_SKIP_SEED=1 - skipping showcase seeds";'
  fi

  local gate_block=""
  case "$phase" in
    seed)
      gate_block='echo "[ga-showcase-docker] seed phase complete";'
      ;;
    all)
      if [[ "${GA_E2E_SKIP_SEED:-0}" != "1" && "${GA_E2E_AUTH_ONCE:-1}" = "1" ]]; then
        gate_block='
          cd /repo/web-admin;
          echo "[ga-showcase-docker] reusing seed auth storage; running chromium + deep only";
          ../scripts/ga-showcase-e2e.sh chromium;
          ../scripts/ga-showcase-e2e.sh deep;
        '
      else
        gate_block='
          cd /repo/web-admin;
          ../scripts/ga-showcase-e2e.sh all;
        '
      fi
      ;;
    auth|chromium|deep)
      gate_block="
        cd /repo/web-admin;
        ../scripts/ga-showcase-e2e.sh $phase;
      "
      ;;
  esac

  cat <<EOF
set -euo pipefail
export GA_SHOWCASE_SKIP_RUNNER_GUARD=1
export GA_SHOWCASE_AUTH_WORKERS="\${GA_SHOWCASE_AUTH_WORKERS:-1}"
export GA_SHOWCASE_CHROMIUM_WORKERS="\${GA_SHOWCASE_CHROMIUM_WORKERS:-\${GA_E2E_CHROMIUM_WORKERS:-3}}"
export GA_SHOWCASE_DEEP_WORKERS="\${GA_SHOWCASE_DEEP_WORKERS:-1}"
bash /repo/scripts/ga-e2e-prepare-deps.sh /repo
cd /repo/web-admin
if [[ "\${GA_E2E_SKIP_BROWSER_INSTALL:-0}" = "1" ]]; then
  echo "[ga-showcase-docker] GA_E2E_SKIP_BROWSER_INSTALL=1 - using image-provided Playwright browsers"
else
  pnpm exec playwright install chromium
fi
cd /repo
$seed_block
$gate_block
EOF
}

run_in_runner() {
  local phase="$1"
  local log="/tmp/pw-ga-showcase-docker-${phase}-$(date +%Y%m%d-%H%M%S).log"
  echo "[ga-showcase-docker] phase=$phase"
  echo "[ga-showcase-docker] log=$log"

  local command
  command="$(runner_command "$phase")"

  set +e
  docker compose "${compose_args[@]}" run --rm --quiet-pull --entrypoint bash ga-e2e-runner -lc "$command" \
    2>&1 | tee "$log"
  local status="${PIPESTATUS[0]}"
  set -e

  if [[ "$status" -ne 0 ]]; then
    echo "[ga-showcase-docker] FAILED (exit=$status, log=$log)" >&2
    return "$status"
  fi

  echo "[ga-showcase-docker] OK (log=$log)"
}

ensure_no_host_runner
ensure_stack
run_in_runner "$PHASE"
