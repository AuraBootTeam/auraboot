#!/usr/bin/env bash
# Workspace identity registration for an explicitly owned golden stack.

# Resolve the optional frozen Workspace dependency checkout for --workspace-source-root.
# The management entry stays the canonical control root; only the source-set identity is
# pinned here. Binding the control root itself would re-record whatever shared main
# currently is (including foreign dirty state) and drift on the next shared commit, so
# that alias is rejected together with missing, non-git, or dirty checkouts.
golden_workspace_dependency_root() {
  local candidate="$1" control="$2" resolved control_resolved
  resolved="$(cd "$candidate" 2>/dev/null && pwd -P)" || {
    echo "frozen workspace dependency checkout does not exist: $candidate" >&2; return 2;
  }
  control_resolved="$(cd "$control" 2>/dev/null && pwd -P)" || {
    echo "workspace control root does not exist: $control" >&2; return 2;
  }
  [ "$resolved" != "$control_resolved" ] || {
    echo "frozen workspace dependency must not be the control root: $resolved" >&2; return 2;
  }
  git -C "$resolved" rev-parse --show-toplevel >/dev/null 2>&1 || {
    echo "frozen workspace dependency is not a git checkout: $resolved" >&2; return 2;
  }
  [ -z "$(git -C "$resolved" status --porcelain)" ] || {
    echo "frozen workspace dependency must be a clean checkout: $resolved" >&2; return 2;
  }
  printf '%s\n' "$resolved"
}

# Bind all declared input roots before building or starting any process.
golden_runtime_bind_sources() {
  local name="$1" repo="$2" workspace="$3"; shift 3
  local args=(runtime migrate "$name" --source "auraboot=$repo" --source "workspace=$workspace")
  local index=0 root
  for root in "$@"; do
    root="$(git -C "$root" rev-parse --show-toplevel)" || return 1
    args+=(--source "input-$index=$root")
    index=$((index + 1))
  done
  "$DEV" "${args[@]}" >/dev/null
}

# Use the immutable Workspace copy rather than a mutable build output.
golden_runtime_stage_backend() {
  local name="$1" jar="$2" staged
  "$DEV" runtime artifact stage "$name" --key backend --source auraboot --file "$jar" >/dev/null || return 1
  staged="$("$DEV" runtime artifact path "$name" backend)" || return 1
  [ -f "$staged" ] || { echo 'golden identity: staged backend is missing' >&2; return 1; }
  printf '%s\n' "$staged"
}

# A listening child belongs to our launch only if its ancestry and cwd agree.
# Checking the port alone would allow a foreign healthy service to be registered.
golden_runtime_register_listener() {
  local name="$1" key="$2" port="$3" ancestor="$4" expected_cwd="$5" token="$6"
  local pid current parent cwd depth=0
  pid="$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null | sort -u)" || return 1
  case "$pid" in ''|*[!0-9]*) echo "golden identity: $key has no unique listener" >&2; return 1;; esac
  case "$ancestor" in ''|*[!0-9]*) echo 'golden identity: invalid launch PID' >&2; return 1;; esac
  current="$pid"
  while [ "$current" != "$ancestor" ]; do
    [ "$current" -gt 1 ] && [ "$depth" -lt 64 ] || { echo "golden identity: foreign $key listener" >&2; return 1; }
    parent="$(ps -p "$current" -o ppid= 2>/dev/null | tr -d '[:space:]')" || return 1
    case "$parent" in ''|*[!0-9]*) echo 'golden identity: cannot establish process ancestry' >&2; return 1;; esac
    [ "$parent" != "$current" ] || { echo 'golden identity: cyclic process ancestry' >&2; return 1; }
    current="$parent"
    depth=$((depth + 1))
  done
  cwd="$(lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')" || return 1
  cwd="$(cd "$cwd" && pwd -P)" || return 1
  expected_cwd="$(cd "$expected_cwd" && pwd -P)" || return 1
  [ "$cwd" = "$expected_cwd" ] || { echo "golden identity: $key listener cwd differs from its source" >&2; return 1; }
  "$DEV" runtime process register "$name" --key "$key" --pid "$pid" --token "$token" >/dev/null
}
