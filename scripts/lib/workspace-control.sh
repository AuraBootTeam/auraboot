#!/usr/bin/env bash
# Shared state uses the main Workspace controller; product source stays in REPO_ROOT.
aura_bind_workspace_control() {
  local candidate="$1" common control
  common="$(git -C "$candidate" rev-parse --path-format=absolute --git-common-dir 2>/dev/null)" || {
    echo "FATAL: Workspace controller must belong to a registered Git checkout: $candidate" >&2; return 2;
  }
  control="$(dirname "$common")"
  [ -f "$control/aura" ] && [ -f "$control/runtime.yaml" ] || {
    echo "FATAL: canonical Workspace controller is unavailable: $control" >&2; return 2;
  }
  export AURA_WORKSPACE_ROOT="$control"
  export AURA_WORKSPACE_STATE_DIR="${AURA_WORKSPACE_STATE_DIR:-$control/.workspace}"
  node "$control/aura" control require 1 >/dev/null || {
    echo "FATAL: upgrade canonical Workspace controller before launching; control protocol 1 is required" >&2; return 2;
  }
  WORKSPACE="$control"
  DEV="$control/aura"
}
