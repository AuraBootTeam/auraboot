#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

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
  --profile=<name>  dev, e2e, showcase, demo, or enterprise-demo (default: dev)
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

export_docker_proxy_defaults() {
  local host_http="${http_proxy:-${HTTP_PROXY:-}}"
  local host_https="${https_proxy:-${HTTPS_PROXY:-}}"

  if [ -n "$host_http" ] && [ -z "${AURA_DOCKER_HTTP_PROXY:-}" ]; then
    export AURA_DOCKER_HTTP_PROXY="${host_http/127.0.0.1/host.docker.internal}"
  fi
  if [ -n "$host_https" ] && [ -z "${AURA_DOCKER_HTTPS_PROXY:-}" ]; then
    export AURA_DOCKER_HTTPS_PROXY="${host_https/127.0.0.1/host.docker.internal}"
  fi
}

case "$PRODUCT:$RUNTIME" in
  oss:host)
    exec "$PROJECT_ROOT/scripts/oss-reset-and-init.sh"
    ;;

  oss:docker)
    export_docker_proxy_defaults
    if [ "$PROFILE" != "e2e" ] && [ "$PROFILE" != "showcase" ]; then
      echo "ERROR: OSS docker reset currently supports --profile=e2e or --profile=showcase" >&2
      exit 2
    fi
    "$PROJECT_ROOT/scripts/docker-ga-e2e-down.sh" --purge || true
    GA_E2E_FRONTEND_IMAGE="${GA_E2E_FRONTEND_IMAGE:-node:22-bookworm-slim}" \
      "$PROJECT_ROOT/scripts/docker-ga-e2e-up.sh"
    "$PROJECT_ROOT/scripts/docker-ga-e2e-bootstrap.sh"
    ;;

  enterprise:host)
    enterprise_root="${AURA_ENTERPRISE_ROOT:-$PROJECT_ROOT/../auraboot-enterprise}"
    exec "$enterprise_root/scripts/reset-and-init.sh"
    ;;

  enterprise:docker)
    export_docker_proxy_defaults
    enterprise_root="${AURA_ENTERPRISE_ROOT:-$PROJECT_ROOT/../auraboot-enterprise}"
    if [ ! -d "$enterprise_root/plugins" ]; then
      echo "ERROR: enterprise plugin root not found: $enterprise_root/plugins" >&2
      exit 1
    fi
    "$PROJECT_ROOT/scripts/dev/stop-isolated.sh" --slug="$SLUG" --purge || true
    ENTERPRISE_PLUGINS_DIR="${ENTERPRISE_PLUGINS_DIR:-$enterprise_root/plugins}" \
    ENTERPRISE_PLUGIN_JARS_DIR="${ENTERPRISE_PLUGIN_JARS_DIR:-$enterprise_root/build/plugin-jars}" \
    ISOLATED_FRONTEND_IMAGE="${ISOLATED_FRONTEND_IMAGE:-node:22-bookworm-slim}" \
      "$PROJECT_ROOT/scripts/dev/start-isolated.sh" --slug="$SLUG" --rebuild --wait --skip-pull
    env_file="$PROJECT_ROOT/.aura-stack/$SLUG.env"
    # shellcheck disable=SC1090
    source "$env_file"
    curl -fsS --noproxy localhost -X POST "http://localhost:${BE_PORT}/api/bootstrap/setup" \
      -H 'Content-Type: application/json' \
      -d '{"companyName":"AuraBoot Dev","adminEmail":"admin@auraboot.com","adminPassword":"Test2026x","adminDisplayName":"Admin User","systemMode":"single","seedDemoData":true}' >/dev/null
    import_profile="$PROFILE"
    case "$import_profile" in
      dev|demo|enterprise-demo) import_profile="enterprise-demo" ;;
      *) echo "ERROR: enterprise docker reset currently supports --profile=dev, demo, or enterprise-demo" >&2; exit 2 ;;
    esac
    "$PROJECT_ROOT/scripts/dev/import-isolated-plugins.sh" \
      --slug="$SLUG" \
      --profile="$import_profile" \
      --edition=enterprise
    PG_HOST=localhost \
    PG_PORT="$PG_PORT" \
    PG_USER="${PG_USER:-auraboot}" \
    PG_DB="${PG_DB:-aura_boot}" \
    PGPASSWORD="${PGPASSWORD:-auraboot_dev}" \
      "$enterprise_root/scripts/sync-marketplace-catalog.sh"
    ;;
esac
