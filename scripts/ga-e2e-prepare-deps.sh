#!/usr/bin/env bash
# Prepare pnpm dependencies for the GA Docker E2E frontend/runner containers.
#
# The GA stack uses named node_modules volumes. Re-running `pnpm install` on
# every phase still costs time, so this script records a dependency-input
# fingerprint and skips install when the mounted volumes already match it.

set -euo pipefail

REPO_ROOT="${1:-${GA_E2E_REPO_ROOT:-/repo}}"
PNPM_VERSION="${GA_E2E_PNPM_VERSION:-9}"
PNPM_STORE="${PNPM_STORE_DIR:-/pnpm-store}"
SENTINEL="${GA_E2E_PREP_SENTINEL:-$REPO_ROOT/node_modules/.ga-e2e-pnpm-ready}"
FAST_PREP="${GA_E2E_FAST_PREP:-1}"
FORCE_INSTALL="${GA_E2E_FORCE_PNPM_INSTALL:-0}"
SKIP_INSTALL="${GA_E2E_SKIP_PNPM_INSTALL:-0}"
DRY_RUN="${GA_E2E_PREP_DRY_RUN:-0}"

log() {
  echo "[ga-e2e-prep] $*"
}

die() {
  echo "[ga-e2e-prep] ERROR: $*" >&2
  exit 2
}

hash_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

hash_stream() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum
  else
    shasum -a 256
  fi
}

ensure_pnpm() {
  install_pnpm() {
    log "installing pnpm@$PNPM_VERSION with npm"
    npm install -g "pnpm@$PNPM_VERSION"
  }

  if command -v pnpm >/dev/null 2>&1; then
    local current
    current="$(pnpm --version 2>/dev/null || true)"
    if [[ "$current" == "$PNPM_VERSION".* ]]; then
      log "pnpm $current ready"
    else
      log "pnpm $current present; replacing with pnpm@$PNPM_VERSION"
      install_pnpm
    fi
  else
    install_pnpm
  fi

  pnpm config set store-dir "$PNPM_STORE"
}

dependency_fingerprint() {
  (
    cd "$REPO_ROOT"
    {
      printf 'pnpm-version=%s\n' "$PNPM_VERSION"
      for manifest in pnpm-lock.yaml pnpm-workspace.yaml package.json web-admin/package.json; do
        if [[ -f "$manifest" ]]; then
          hash_file "$manifest"
        fi
      done
      if [[ -d packages ]]; then
        while IFS= read -r -d '' manifest; do
          hash_file "$manifest"
        done < <(find packages -maxdepth 3 -name package.json -type f -print0 | sort -z)
      fi
    } | hash_stream | awk '{print $1}'
  )
}

has_node_modules() {
  [[ -d "$REPO_ROOT/node_modules" ]] || return 1
  [[ -d "$REPO_ROOT/web-admin/node_modules" ]] || return 1
  [[ -n "$(find "$REPO_ROOT/node_modules" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]] || return 1
  [[ -n "$(find "$REPO_ROOT/web-admin/node_modules" -mindepth 1 -maxdepth 1 -print -quit 2>/dev/null)" ]] || return 1
}

install_needed_action() {
  local fingerprint="$1"

  if [[ "$SKIP_INSTALL" = "1" ]]; then
    echo "skip-forced"
    return
  fi

  if [[ "$FORCE_INSTALL" = "1" || "$FAST_PREP" = "0" ]]; then
    echo "install-forced"
    return
  fi

  if [[ -f "$SENTINEL" ]] && [[ "$(cat "$SENTINEL")" = "$fingerprint" ]] && has_node_modules; then
    echo "skip-fingerprint"
    return
  fi

  echo "install-needed"
}

[[ -d "$REPO_ROOT" ]] || die "repo root not found: $REPO_ROOT"

fingerprint="$(dependency_fingerprint)"
action="$(install_needed_action "$fingerprint")"

if [[ "$DRY_RUN" = "1" ]]; then
  log "action=$action fingerprint=$fingerprint"
  exit 0
fi

ensure_pnpm

case "$action" in
  skip-forced)
    log "pnpm install skipped by GA_E2E_SKIP_PNPM_INSTALL=1"
    ;;
  skip-fingerprint)
    log "pnpm install skipped; dependency fingerprint unchanged ($fingerprint)"
    ;;
  install-forced|install-needed)
    start_seconds="$(date +%s)"
    log "pnpm install start ($action, fingerprint=$fingerprint)"
    (
      cd "$REPO_ROOT"
      HUSKY=0 pnpm install --no-frozen-lockfile --prefer-offline
    )
    mkdir -p "$(dirname "$SENTINEL")"
    printf '%s\n' "$fingerprint" > "$SENTINEL"
    elapsed="$(( $(date +%s) - start_seconds ))"
    log "pnpm install done (${elapsed}s)"
    ;;
  *)
    die "unknown action: $action"
    ;;
esac
