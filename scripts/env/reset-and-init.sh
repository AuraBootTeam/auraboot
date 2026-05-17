#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=../lib/reset-init-common.sh
source "$PROJECT_ROOT/scripts/lib/reset-init-common.sh"

PRODUCT=""
RUNTIME=""
PROFILE="dev"
PROFILE_SET=0
SLUG=""
DRY_RUN=0

usage() {
  cat <<USAGE
Usage: scripts/env/reset-and-init.sh --product=oss|enterprise --runtime=host|docker [options]

Options:
  --profile=<name>  core, demo, e2e, showcase, enterprise-demo, or pcba-agent (default depends on product/runtime)
  --slug=<name>     Docker isolated stack slug (default depends on product/profile)
  --dry-run         Print the resolved plan without executing it

This is the normalized environment lifecycle entrypoint. Legacy scripts remain
available; this wrapper makes the product/runtime/profile boundary explicit.
USAGE
}

for arg in "$@"; do
  case "$arg" in
    --product=*) PRODUCT="${arg#--product=}" ;;
    --runtime=*) RUNTIME="${arg#--runtime=}" ;;
    --profile=*) PROFILE="${arg#--profile=}"; PROFILE_SET=1 ;;
    --slug=*) SLUG="${arg#--slug=}" ;;
    --dry-run) DRY_RUN=1 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "ERROR: unknown argument: $arg" >&2; usage; exit 2 ;;
  esac
done

case "$PRODUCT" in
  oss|enterprise) ;;
  "") echo "ERROR: --product is required" >&2; usage; exit 2 ;;
  *) echo "ERROR: unknown product: $PRODUCT" >&2; exit 2 ;;
esac

case "$RUNTIME" in
  host|docker) ;;
  "") echo "ERROR: --runtime is required" >&2; usage; exit 2 ;;
  *) echo "ERROR: unknown runtime: $RUNTIME" >&2; exit 2 ;;
esac

if [ "$PROFILE_SET" = "0" ]; then
  case "$PRODUCT:$RUNTIME" in
    oss:docker) PROFILE="e2e" ;;
    enterprise:docker) PROFILE="enterprise-demo" ;;
    *) PROFILE="dev" ;;
  esac
fi

default_slug() {
  case "$PRODUCT:$PROFILE" in
    oss:e2e|oss:showcase) printf 'ga-e2e\n' ;;
    enterprise:demo|enterprise:enterprise-demo) printf 'enterprise-demo\n' ;;
    enterprise:e2e) printf 'enterprise-e2e\n' ;;
    *) printf '%s-%s\n' "$PRODUCT" "$PROFILE" ;;
  esac
}

if [ -z "$SLUG" ]; then
  SLUG="$(default_slug)"
fi

print_plan() {
  cat <<PLAN
Environment reset/init plan
  product:  $PRODUCT
  runtime:  $RUNTIME
  profile:  $PROFILE
  slug:     $SLUG
PLAN
}

print_plan

if [ "$DRY_RUN" = "1" ]; then
  echo "(dry-run mode: not executing lifecycle steps)"
  exit 0
fi

resolve_enterprise_root() {
  if [ -n "${AURA_ENTERPRISE_ROOT:-}" ]; then
    if [ -f "$AURA_ENTERPRISE_ROOT/plugins/platform-admin-ee/plugin.json" ]; then
      (cd "$AURA_ENTERPRISE_ROOT" && pwd)
      return
    fi
    echo "ERROR: AURA_ENTERPRISE_ROOT does not look like a full enterprise repo: $AURA_ENTERPRISE_ROOT" >&2
    exit 1
  fi

  local candidate
  for candidate in \
    "$PROJECT_ROOT/../auraboot-enterprise" \
    "$PROJECT_ROOT/../../auraboot-enterprise"
  do
    if [ -f "$candidate/plugins/platform-admin-ee/plugin.json" ]; then
      (cd "$candidate" && pwd)
      return
    fi
  done

  echo "ERROR: could not locate full enterprise repo. Set AURA_ENTERPRISE_ROOT=/path/to/auraboot-enterprise." >&2
  exit 1
}

case "$PRODUCT:$RUNTIME" in
  oss:host)
    if [ "$PROFILE" != "dev" ]; then
      export PLUGIN_IMPORT_PROFILE="${PLUGIN_IMPORT_PROFILE:-$PROFILE}"
    fi
    exec "$PROJECT_ROOT/scripts/oss-reset-and-init.sh"
    ;;

  oss:docker)
    aura_export_docker_proxy_defaults
    if [ "$PROFILE" != "e2e" ] && [ "$PROFILE" != "showcase" ]; then
      echo "ERROR: OSS docker reset currently supports --profile=e2e or --profile=showcase" >&2
      exit 2
    fi
    "$PROJECT_ROOT/scripts/docker-ga-e2e-down.sh" --purge || true
    GA_E2E_FRONTEND_IMAGE="${GA_E2E_FRONTEND_IMAGE:-node:22-bookworm-slim}" \
      "$PROJECT_ROOT/scripts/docker-ga-e2e-up.sh"
    "$PROJECT_ROOT/scripts/docker-ga-e2e-bootstrap.sh"
    aura_sync_marketplace_catalog "$PROJECT_ROOT" 5433
    ;;

  enterprise:host)
    enterprise_root="$(resolve_enterprise_root)"
    exec "$enterprise_root/scripts/reset-and-init.sh"
    ;;

  enterprise:docker)
    aura_export_docker_proxy_defaults
    enterprise_root="$(resolve_enterprise_root)"
    "$PROJECT_ROOT/scripts/dev/stop-isolated.sh" --slug="$SLUG" --purge || true
    echo "[enterprise-docker] building backend jar on host with Gradle cache..."
    (cd "$PROJECT_ROOT/platform" && ./gradlew bootJar --no-daemon -x test)
    ENTERPRISE_PLUGINS_DIR="${ENTERPRISE_PLUGINS_DIR:-$enterprise_root/plugins}" \
    ENTERPRISE_PLUGIN_JARS_DIR="${ENTERPRISE_PLUGIN_JARS_DIR:-$enterprise_root/build/plugin-jars}" \
    ISOLATED_BACKEND_DOCKERFILE="${ISOLATED_BACKEND_DOCKERFILE:-Dockerfile.runtime}" \
    ISOLATED_FRONTEND_IMAGE="${ISOLATED_FRONTEND_IMAGE:-node:22-bookworm-slim}" \
      "$PROJECT_ROOT/scripts/dev/start-isolated.sh" --slug="$SLUG" --rebuild --wait --skip-pull
    env_file="$PROJECT_ROOT/.aura-stack/$SLUG.env"
    # shellcheck disable=SC1090
    source "$env_file"
    aura_bootstrap_setup_if_needed \
      "http://localhost:${BE_PORT}" \
      "AuraBoot Dev" \
      "admin@auraboot.com" \
      "Test2026x" \
      "Admin User" \
      "single" \
      "[enterprise-docker]"
    import_profile="$PROFILE"
    case "$import_profile" in
      dev|demo|enterprise-demo) import_profile="enterprise-demo" ;;
      core|pcba-agent) ;;
      *) echo "ERROR: enterprise docker reset currently supports --profile=dev, demo, core, enterprise-demo, or pcba-agent" >&2; exit 2 ;;
    esac
    "$PROJECT_ROOT/scripts/import-plugins.sh" \
      --slug="$SLUG" \
      --profile="$import_profile" \
      --edition=enterprise
    aura_sync_marketplace_catalog "$enterprise_root" "$PG_PORT"
    ;;
esac
