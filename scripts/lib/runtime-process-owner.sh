#!/usr/bin/env bash

# Runtime-scoped process ownership for reset-and-init entrypoints.
#
# This helper deliberately never discovers processes by a global command-line
# pattern.  A reset may stop only a process that it registered itself and whose
# runtime identity, PID, process start time, process group, cwd/source root and
# listener port still match the durable owner record.  Any ambiguity is an
# error, not an invitation to kill a possibly foreign process.

aura_reset_owner_error() {
    echo "ERROR: runtime process ownership: $*" >&2
}

aura_reset_canonical_dir() {
    [ -d "$1" ] || {
        aura_reset_owner_error "directory does not exist: $1"
        return 1
    }
    (cd "$1" && pwd -P)
}

aura_reset_hash() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{print $1}'
    elif command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{print $1}'
    else
        aura_reset_owner_error "shasum or sha256sum is required"
        return 1
    fi
}

aura_reset_sanitize_label() {
    printf '%s' "$1" \
        | tr '[:upper:]' '[:lower:]' \
        | sed -E 's/[^a-z0-9_.-]+/-/g; s/[-.]+$//; s/^[-.]+//'
}

aura_reset_normalize_host() {
    local normalized
    normalized="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
    case "$normalized" in
        localhost|localhost.|127.0.0.1|::1|'[::1]') printf '%s\n' local-loopback ;;
        *) printf '%s\n' "$normalized" ;;
    esac
}

aura_reset_normalize_tmux_session() {
    local normalized
    normalized="$(printf '%s' "$1" | tr '.' '_')"
    [ -n "$normalized" ] && [[ "$normalized" =~ ^[A-Za-z0-9_-]+$ ]] || {
        aura_reset_owner_error "invalid tmux session name: $1"
        return 1
    }
    printf '%s\n' "$normalized"
}

aura_reset_process_start_time() {
    LC_ALL=C TZ=UTC ps -p "$1" -o lstart= 2>/dev/null \
        | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

aura_reset_process_pgid() {
    ps -p "$1" -o pgid= 2>/dev/null | tr -d '[:space:]'
}

aura_reset_process_ppid() {
    ps -p "$1" -o ppid= 2>/dev/null | tr -d '[:space:]'
}

aura_reset_process_command() {
    ps -p "$1" -o command= 2>/dev/null \
        | sed -E 's/^[[:space:]]+//; s/[[:space:]]+$//'
}

aura_reset_process_cwd() {
    local pid="$1"
    if [ -L "/proc/$pid/cwd" ]; then
        readlink "/proc/$pid/cwd" 2>/dev/null || true
        return
    fi
    if command -v lsof >/dev/null 2>&1; then
        lsof -a -p "$pid" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p' | head -1
    fi
}

aura_reset_pid_alive() {
    kill -0 "$1" 2>/dev/null
}

aura_reset_pgid_alive() {
    local pgid="$1"
    ps -axo pgid=,pid= 2>/dev/null \
        | awk -v expected="$pgid" '$1 == expected { found=1 } END { exit found ? 0 : 1 }'
}

aura_reset_listener_snapshot() {
    local port="$1"
    [ -n "$port" ] || return 0
    if ! command -v lsof >/dev/null 2>&1; then
        aura_reset_owner_error "lsof is required to verify listener ownership for port $port"
        return 2
    fi
    lsof -nP -iTCP:"$port" -sTCP:LISTEN -Fpc 2>/dev/null \
        | awk '
            /^p/ { pid=substr($0, 2) }
            /^c/ { print pid "\t" substr($0, 2) }
        ' \
        | sort -u || true
}

aura_reset_listener_pids() {
    local port="$1"
    aura_reset_listener_snapshot "$port" | cut -f1
}

aura_reset_port_is_bindable() {
    local port="$1"
    python3 - "$port" <<'PY'
import socket
import sys

port = int(sys.argv[1])
with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", port))
    except OSError:
        raise SystemExit(1)
PY
}

aura_reset_pid_belongs_to_owner() {
    local candidate="$1"
    local owner_pid="$2"
    local owner_pgid="$3"
    local candidate_pgid
    local parent
    local depth=0

    [ "$candidate" = "$owner_pid" ] && return 0
    candidate_pgid="$(aura_reset_process_pgid "$candidate")"
    [ -n "$candidate_pgid" ] && [ "$candidate_pgid" = "$owner_pgid" ] && return 0

    while [ "$candidate" -gt 1 ] 2>/dev/null && [ "$depth" -lt 64 ]; do
        parent="$(aura_reset_process_ppid "$candidate")"
        [ -n "$parent" ] || break
        [ "$parent" = "$owner_pid" ] && return 0
        [ "$parent" = "$candidate" ] && break
        candidate="$parent"
        depth=$((depth + 1))
    done
    return 1
}

aura_reset_listener_is_owned() {
    local candidate_pid="$1"
    local candidate_command="$2"
    local owner_pid="$3"
    local owner_pgid="$4"
    local owner_start="$5"
    local owner_root="$6"
    local current_start
    local candidate_cwd

    aura_reset_pid_belongs_to_owner "$candidate_pid" "$owner_pid" "$owner_pgid" || return 1
    current_start="$(aura_reset_process_start_time "$candidate_pid")"
    [ -n "$current_start" ] || return 1
    candidate_cwd="$(aura_reset_process_cwd "$candidate_pid")"
    case "$candidate_cwd" in
        "$owner_root"|"$owner_root"/*) ;;
        *) return 1 ;;
    esac

    if [ "$candidate_pid" = "$owner_pid" ]; then
        [ "$current_start" = "$owner_start" ]
        return
    fi
    [ -n "$candidate_command" ] || return 1
    case "$candidate_command" in
        java|node|python|python3|react-router|vite|tsx|pnpm|npm|yarn|gradle) return 0 ;;
        *) return 1 ;;
    esac
}

aura_reset_record_value() {
    local file="$1"
    local key="$2"
    sed -n "s/^${key}=//p" "$file" | head -1
}

aura_reset_validate_record_value() {
    local label="$1"
    local value="$2"
    case "$value" in
        *$'\n'*|*$'\r'*|*$'\t'*)
            aura_reset_owner_error "$label contains a forbidden control character"
            return 1
            ;;
    esac
}

aura_reset_resolve_owner_state_dir() {
    local source_root="$1"
    local cursor
    local common_dir

    if [ -n "${AURA_RESET_OWNER_STATE_DIR:-}" ]; then
        case "$AURA_RESET_OWNER_STATE_DIR" in
            /*) printf '%s\n' "$AURA_RESET_OWNER_STATE_DIR" ;;
            *) printf '%s\n' "$PWD/$AURA_RESET_OWNER_STATE_DIR" ;;
        esac
        return
    fi
    if [ -n "${AURA_WORKSPACE_STATE_DIR:-}" ]; then
        printf '%s/reset-process-owners\n' "$AURA_WORKSPACE_STATE_DIR"
        return
    fi
    if [ -n "${AURA_MONO_ROOT:-}" ] && [ -d "$AURA_MONO_ROOT" ]; then
        printf '%s/.workspace/reset-process-owners\n' "$AURA_MONO_ROOT"
        return
    fi
    if [ -n "${MONO_ROOT:-}" ] && [ -d "$MONO_ROOT" ]; then
        printf '%s/.workspace/reset-process-owners\n' "$MONO_ROOT"
        return
    fi

    cursor="$source_root"
    while [ "$cursor" != "/" ]; do
        if [ -f "$cursor/dev.sh" ] && [ -f "$cursor/runtime.yaml" ]; then
            printf '%s/.workspace/reset-process-owners\n' "$cursor"
            return
        fi
        cursor="$(dirname "$cursor")"
    done

    common_dir="$(git -C "$source_root" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
    if [ -z "$common_dir" ]; then
        common_dir="$(cd "$source_root" && cd "$(git rev-parse --git-common-dir 2>/dev/null)" 2>/dev/null && pwd -P || true)"
    fi
    if [ -n "$common_dir" ]; then
        printf '%s/.workspace/reset-process-owners\n' "$(dirname "$common_dir")"
        return
    fi

    aura_reset_owner_error "cannot resolve a shared owner state directory"
    return 1
}

aura_reset_owner_state_root_is_safe() {
    local state_root="$1"
    [ -n "$state_root" ] || return 1
    case "$state_root" in
        /|/tmp|/private/tmp|/var/tmp|"${TMPDIR:-/__aura_no_tmpdir__}"|"${HOME:-/__aura_no_home__}"|"${AURA_MONO_ROOT:-/__aura_no_mono__}")
            return 1
            ;;
    esac
    return 0
}

aura_reset_owner_init() {
    local product="$1"
    local source_root="$2"
    local runtime_label_raw="$3"
    local pg_host="$4"
    local pg_port="$5"
    local pg_db="$6"
    local backend_port="$7"
    local web_port="$8"
    local bff_port="$9"
    local identity
    local db_identity
    local normalized_pg_host

    AURA_RESET_OWNER_PRODUCT="$(aura_reset_sanitize_label "$product")"
    AURA_RESET_OWNER_SOURCE_ROOT="$(aura_reset_canonical_dir "$source_root")" || return 1
    AURA_RESET_OWNER_RUNTIME_LABEL="$(aura_reset_sanitize_label "$runtime_label_raw")"
    [ -n "$AURA_RESET_OWNER_PRODUCT" ] || {
        aura_reset_owner_error "product identity is empty"
        return 1
    }
    [ -n "$AURA_RESET_OWNER_RUNTIME_LABEL" ] || AURA_RESET_OWNER_RUNTIME_LABEL="default"

    for value in "$pg_port" "$backend_port" "$web_port" "$bff_port"; do
        [[ "$value" =~ ^[1-9][0-9]*$ ]] || {
            aura_reset_owner_error "ports must be positive integers (got '$value')"
            return 1
        }
    done
    [ -n "$pg_host" ] && [ -n "$pg_db" ] || {
        aura_reset_owner_error "database host and name are required"
        return 1
    }
    [ "$backend_port" != "$web_port" ] \
        && [ "$backend_port" != "$bff_port" ] \
        && [ "$web_port" != "$bff_port" ] || {
            aura_reset_owner_error "backend, web and BFF ports must be distinct"
            return 1
        }

    normalized_pg_host="$(aura_reset_normalize_host "$pg_host")"
    identity="product=$AURA_RESET_OWNER_PRODUCT|runtime=$AURA_RESET_OWNER_RUNTIME_LABEL|source=$AURA_RESET_OWNER_SOURCE_ROOT|pg=$normalized_pg_host:$pg_port/$pg_db|ports=$backend_port,$web_port,$bff_port"
    db_identity="pg=$normalized_pg_host:$pg_port/$pg_db"
    AURA_RESET_OWNER_IDENTITY_HASH="$(printf '%s' "$identity" | aura_reset_hash)" || return 1
    AURA_RESET_OWNER_DB_HASH="$(printf '%s' "$db_identity" | aura_reset_hash)" || return 1
    AURA_RESET_OWNER_RUNTIME_ID="${AURA_RESET_OWNER_PRODUCT}-${AURA_RESET_OWNER_RUNTIME_LABEL}-${AURA_RESET_OWNER_IDENTITY_HASH:0:12}"
    AURA_RESET_OWNER_STATE_DIR="$(aura_reset_resolve_owner_state_dir "$AURA_RESET_OWNER_SOURCE_ROOT")" || return 1
    aura_reset_owner_state_root_is_safe "$AURA_RESET_OWNER_STATE_DIR" || {
        aura_reset_owner_error "refusing unsafe owner state directory: $AURA_RESET_OWNER_STATE_DIR"
        return 1
    }
    AURA_RESET_OWNER_PROCESS_DIR="$AURA_RESET_OWNER_STATE_DIR/processes/$AURA_RESET_OWNER_IDENTITY_HASH"
    AURA_RESET_OWNER_LOCK_ROOT="$AURA_RESET_OWNER_STATE_DIR/locks"
    AURA_RESET_OWNER_HOSTNAME="$(hostname 2>/dev/null || uname -n)"
    AURA_RESET_OWNER_HOST_HASH="$(printf '%s' \
        "$(aura_reset_normalize_host "$AURA_RESET_OWNER_HOSTNAME")" | aura_reset_hash)" || return 1
    AURA_RESET_OWNER_NONCE="${AURA_RESET_OWNER_HOSTNAME}-$$-$(date +%s)-${RANDOM:-0}"
    AURA_RESET_OWNER_DB_IDENTITY="$db_identity"
    AURA_RESET_OWNER_PORTS=("$backend_port" "$web_port" "$bff_port")
    AURA_RESET_OWNER_HELD_LOCKS=()

    export AURA_RESET_OWNER_RUNTIME_ID
}

aura_reset_lock_owner_file_is_only_entry() {
    local lock_dir="$1"
    local entry
    local count=0
    while IFS= read -r entry; do
        [ -n "$entry" ] || continue
        count=$((count + 1))
        [ "$entry" = "$lock_dir/owner" ] || return 1
    done < <(find "$lock_dir" -mindepth 1 -maxdepth 1 -print 2>/dev/null)
    [ "$count" -eq 1 ]
}

aura_reset_reclaim_stale_lock() {
    local lock_dir="$1"
    local owner_file="$lock_dir/owner"
    local owner_host
    local owner_pid
    local owner_start
    local current_start
    local reclaim_dir

    [ -f "$owner_file" ] || return 1
    aura_reset_lock_owner_file_is_only_entry "$lock_dir" || return 1
    owner_host="$(aura_reset_record_value "$owner_file" hostname)"
    owner_pid="$(aura_reset_record_value "$owner_file" pid)"
    owner_start="$(aura_reset_record_value "$owner_file" start_time)"
    [ "$owner_host" = "$AURA_RESET_OWNER_HOSTNAME" ] || return 1
    [[ "$owner_pid" =~ ^[1-9][0-9]*$ ]] || return 1
    [ -n "$owner_start" ] || return 1

    if aura_reset_pid_alive "$owner_pid"; then
        current_start="$(aura_reset_process_start_time "$owner_pid")"
        [ "$current_start" != "$owner_start" ] || return 1
    fi

    reclaim_dir="${lock_dir}.reclaim.${AURA_RESET_OWNER_NONCE}"
    mv "$lock_dir" "$reclaim_dir" 2>/dev/null || return 1
    rm -f "$reclaim_dir/owner"
    rmdir "$reclaim_dir"
}

aura_reset_acquire_one_lock() {
    local lock_name="$1"
    local lock_dir="$AURA_RESET_OWNER_LOCK_ROOT/$lock_name.lock"
    local owner_file="$lock_dir/owner"
    local owner_pid
    local owner_runtime
    local attempt=0

    mkdir -p "$AURA_RESET_OWNER_LOCK_ROOT"
    while [ "$attempt" -lt 2 ]; do
        if mkdir "$lock_dir" 2>/dev/null; then
            if ! (umask 077; {
                echo "schema=1"
                echo "hostname=$AURA_RESET_OWNER_HOSTNAME"
                echo "pid=$$"
                echo "start_time=$(aura_reset_process_start_time $$)"
                echo "nonce=$AURA_RESET_OWNER_NONCE"
                echo "runtime_id=$AURA_RESET_OWNER_RUNTIME_ID"
                echo "source_root=$AURA_RESET_OWNER_SOURCE_ROOT"
                echo "db_identity=$AURA_RESET_OWNER_DB_IDENTITY"
                echo "created_epoch=$(date +%s)"
            } > "$owner_file"); then
                rm -f "$owner_file"
                rmdir "$lock_dir" 2>/dev/null || true
                aura_reset_owner_error "could not write lock owner record: $lock_name"
                return 1
            fi
            AURA_RESET_OWNER_HELD_LOCKS+=("$lock_dir")
            return 0
        fi

        if aura_reset_reclaim_stale_lock "$lock_dir"; then
            attempt=$((attempt + 1))
            continue
        fi
        owner_pid="$(aura_reset_record_value "$owner_file" pid 2>/dev/null || echo unknown)"
        owner_runtime="$(aura_reset_record_value "$owner_file" runtime_id 2>/dev/null || echo unknown)"
        aura_reset_owner_error "lock is held or cannot be proved stale: $lock_name owner_pid=$owner_pid owner_runtime=$owner_runtime"
        return 1
    done
    aura_reset_owner_error "could not acquire lock after stale-owner recovery: $lock_name"
    return 1
}

aura_reset_release_locks() {
    local index
    local lock_dir
    local owner_file
    local nonce
    local release_status=0
    # Bash 3.2 with `set -u` treats an empty array expansion as unbound.
    # Keep a sentinel so release remains portable on the macOS system Bash.
    local failed_locks=(__aura_failed_lock_sentinel__)
    declare -p AURA_RESET_OWNER_HELD_LOCKS >/dev/null 2>&1 || return 0
    for ((index=${#AURA_RESET_OWNER_HELD_LOCKS[@]} - 1; index >= 0; index--)); do
        lock_dir="${AURA_RESET_OWNER_HELD_LOCKS[$index]}"
        owner_file="$lock_dir/owner"
        nonce="$(aura_reset_record_value "$owner_file" nonce 2>/dev/null || true)"
        if [ "$nonce" = "$AURA_RESET_OWNER_NONCE" ] \
            && aura_reset_lock_owner_file_is_only_entry "$lock_dir"; then
            if ! rm -f "$owner_file" || ! rmdir "$lock_dir"; then
                aura_reset_owner_error "failed to remove owned lock: $lock_dir"
                failed_locks+=("$lock_dir")
                release_status=1
            fi
        else
            aura_reset_owner_error "refusing to release lock after owner token or directory contents changed: $lock_dir"
            failed_locks+=("$lock_dir")
            release_status=1
        fi
    done
    AURA_RESET_OWNER_HELD_LOCKS=()
    for ((index=1; index<${#failed_locks[@]}; index++)); do
        AURA_RESET_OWNER_HELD_LOCKS+=("${failed_locks[$index]}")
    done
    return "$release_status"
}

aura_reset_lock_exit_handler() {
    local status=$?
    local hook_status=0
    local exit_hook="${AURA_RESET_OWNER_EXIT_HOOK:-}"
    trap - EXIT
    if ! aura_reset_release_locks && [ "$status" -eq 0 ]; then
        status=74
    fi
    if [ -n "$exit_hook" ]; then
        if [[ "$exit_hook" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] \
            && declare -F "$exit_hook" >/dev/null 2>&1; then
            "$exit_hook" "$status" || hook_status=$?
        else
            aura_reset_owner_error "invalid owner exit hook: $exit_hook"
            hook_status=74
        fi
        if [ "$status" -eq 0 ] && [ "$hook_status" -ne 0 ]; then
            status="$hook_status"
        fi
    fi
    exit "$status"
}

aura_reset_install_lock_trap() {
    if [ "${AURA_RESET_OWNER_LOCK_TRAP_INSTALLED:-0}" = "1" ]; then
        return 0
    fi
    if [ -n "$(trap -p EXIT)" ] \
        || [ -n "$(trap -p INT)" ] \
        || [ -n "$(trap -p TERM)" ]; then
        aura_reset_owner_error \
            "refusing to replace an existing EXIT/INT/TERM trap; use AURA_RESET_OWNER_EXIT_HOOK"
        return 1
    fi
    trap aura_reset_lock_exit_handler EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM
    AURA_RESET_OWNER_LOCK_TRAP_INSTALLED=1
}

aura_reset_acquire_locks() {
    local port
    aura_reset_install_lock_trap || return 1
    aura_reset_acquire_one_lock "runtime-$AURA_RESET_OWNER_IDENTITY_HASH" || return 1
    if ! aura_reset_acquire_one_lock "db-$AURA_RESET_OWNER_DB_HASH"; then
        aura_reset_release_locks
        return 1
    fi
    for port in "${AURA_RESET_OWNER_PORTS[@]}"; do
        if ! aura_reset_acquire_one_lock \
            "tcp-port-${AURA_RESET_OWNER_HOST_HASH:0:16}-$port"; then
            aura_reset_release_locks
            return 1
        fi
    done
}

aura_reset_service_record_file() {
    printf '%s/%s.owner\n' "$AURA_RESET_OWNER_PROCESS_DIR" "$1"
}

aura_reset_assert_tmux_available() {
    local session_name="$1"
    [ -n "$session_name" ] || return 0
    if command -v tmux >/dev/null 2>&1 \
        && tmux has-session -t "=$session_name" 2>/dev/null; then
        aura_reset_owner_error "tmux session exists without a verified owner stop: $session_name"
        return 1
    fi
}

aura_reset_tmux_session_snapshot_by_name() {
    local session_name="$1"
    command -v tmux >/dev/null 2>&1 || return 1
    tmux list-panes -t "=$session_name" \
        -F '#{session_id}|#{pane_id}|#{pane_pid}' 2>/dev/null
}

aura_reset_tmux_session_snapshot_by_id() {
    local session_id="$1"
    command -v tmux >/dev/null 2>&1 || return 1
    tmux list-panes -a -F '#{session_id}|#{pane_id}|#{pane_pid}' 2>/dev/null \
        | awk -F '|' -v expected="$session_id" '$1 == expected { print }'
}

aura_reset_assert_port_available() {
    local service="$1"
    local port="$2"
    local listeners
    listeners="$(aura_reset_listener_pids "$port")" || return 1
    if [ -n "$listeners" ]; then
        aura_reset_owner_error "$service port $port is owned by an unverified process (pids=$(echo "$listeners" | tr '\n' ',' | sed 's/,$//'))"
        return 1
    fi
    if ! aura_reset_port_is_bindable "$port"; then
        aura_reset_owner_error "$service port $port is occupied but its listener PID cannot be verified"
        return 1
    fi
}

aura_reset_assert_service_absent() {
    local service="$1"
    local port="$2"
    local tmux_session="${3:-}"
    local record_file
    record_file="$(aura_reset_service_record_file "$service")"
    if [ -e "$record_file" ]; then
        aura_reset_owner_error "$service has a runtime owner record; enable the ownership-scoped stop before reset"
        return 1
    fi
    aura_reset_assert_tmux_available "$tmux_session" || return 1
    aura_reset_assert_port_available "$service" "$port"
}

aura_reset_register_process() {
    local service="$1"
    local pid="$2"
    local port="$3"
    local process_root="$4"
    local command_token="$5"
    local tmux_session="${6:-}"
    local expected_tmux_session_id="${7:-}"
    local expected_tmux_pane_id="${8:-}"
    local record_file
    local record_tmp
    local canonical_process_root
    local start_time
    local pgid
    local cwd
    local command
    local script_pgid
    local tmux_snapshot=""
    local tmux_session_id=""
    local tmux_pane_id=""
    local tmux_pane_pid=""

    [[ "$service" =~ ^[a-z0-9_.-]+$ ]] || {
        aura_reset_owner_error "invalid service name: $service"
        return 1
    }
    [[ "$pid" =~ ^[1-9][0-9]*$ ]] && aura_reset_pid_alive "$pid" || {
        aura_reset_owner_error "cannot register dead or invalid $service pid: $pid"
        return 1
    }
    canonical_process_root="$(aura_reset_canonical_dir "$process_root")" || return 1
    aura_reset_validate_record_value process_root "$canonical_process_root" || return 1
    aura_reset_validate_record_value command_token "$command_token" || return 1
    aura_reset_validate_record_value tmux_session "$tmux_session" || return 1
    [ -n "$command_token" ] || {
        aura_reset_owner_error "command token is required for $service"
        return 1
    }

    start_time="$(aura_reset_process_start_time "$pid")"
    pgid="$(aura_reset_process_pgid "$pid")"
    cwd="$(aura_reset_process_cwd "$pid")"
    command="$(aura_reset_process_command "$pid")"
    script_pgid="$(aura_reset_process_pgid $$)"
    [ -n "$start_time" ] && [[ "$pgid" =~ ^[1-9][0-9]*$ ]] && [ -n "$cwd" ] || {
        aura_reset_owner_error "cannot prove process identity for $service pid=$pid"
        return 1
    }
    [ "$pgid" != "$script_pgid" ] || {
        aura_reset_owner_error "$service pid=$pid shares the reset runner process group $pgid"
        return 1
    }
    if [ -z "$tmux_session" ] && [ "$pid" != "$pgid" ]; then
        aura_reset_owner_error "$service detached pid=$pid is not its process-group leader (pgid=$pgid)"
        return 1
    fi
    case "$cwd" in
        "$canonical_process_root"|"$canonical_process_root"/*) ;;
        *)
            aura_reset_owner_error "$service pid=$pid cwd is outside process root: cwd=$cwd root=$canonical_process_root"
            return 1
            ;;
    esac
    case "$command" in
        *"$command_token"*) ;;
        *)
            aura_reset_owner_error "$service pid=$pid command does not contain '$command_token'"
            return 1
            ;;
    esac
    if [ -n "$tmux_session" ]; then
        tmux_snapshot="$(aura_reset_tmux_session_snapshot_by_name "$tmux_session")" || {
            aura_reset_owner_error "cannot resolve exact tmux session: $tmux_session"
            return 1
        }
        [ "$(printf '%s\n' "$tmux_snapshot" | wc -l | tr -d ' ')" = "1" ] || {
            aura_reset_owner_error "tmux session must contain exactly one pane: $tmux_session"
            return 1
        }
        IFS='|' read -r tmux_session_id tmux_pane_id tmux_pane_pid <<< "$tmux_snapshot"
        [[ "$tmux_session_id" =~ ^\$[0-9]+$ ]] \
            && [[ "$tmux_pane_id" =~ ^%[0-9]+$ ]] \
            && [ "$tmux_pane_pid" = "$pid" ] || {
                aura_reset_owner_error "tmux immutable identity does not match owner pid for $tmux_session"
                return 1
            }
        { [ -z "$expected_tmux_session_id" ] \
            || [ "$tmux_session_id" = "$expected_tmux_session_id" ]; } \
            && { [ -z "$expected_tmux_pane_id" ] \
                || [ "$tmux_pane_id" = "$expected_tmux_pane_id" ]; } || {
                    aura_reset_owner_error \
                        "tmux session was replaced between creation and registration: $tmux_session"
                    return 1
                }
    fi

    mkdir -p "$AURA_RESET_OWNER_PROCESS_DIR"
    record_file="$(aura_reset_service_record_file "$service")"
    [ ! -e "$record_file" ] || {
        aura_reset_owner_error "$service already has an owner record; stop it before registering another process"
        return 1
    }
    record_tmp="${record_file}.tmp.${AURA_RESET_OWNER_NONCE}"
    if ! (umask 077; {
        echo "schema=1"
        echo "runtime_id=$AURA_RESET_OWNER_RUNTIME_ID"
        echo "hostname=$AURA_RESET_OWNER_HOSTNAME"
        echo "service=$service"
        echo "pid=$pid"
        echo "pgid=$pgid"
        echo "start_time=$start_time"
        echo "source_root=$AURA_RESET_OWNER_SOURCE_ROOT"
        echo "process_root=$canonical_process_root"
        echo "port=$port"
        echo "tmux_session=$tmux_session"
        echo "tmux_session_id=$tmux_session_id"
        echo "tmux_pane_id=$tmux_pane_id"
        echo "command_token=$command_token"
        echo "created_epoch=$(date +%s)"
    } > "$record_tmp"); then
        rm -f "$record_tmp"
        aura_reset_owner_error "could not write owner record for $service"
        return 1
    fi
    if ! mv "$record_tmp" "$record_file"; then
        rm -f "$record_tmp"
        aura_reset_owner_error "could not publish owner record for $service"
        return 1
    fi
}

aura_reset_assert_service_owned() {
    local service="$1"
    local expected_port="$2"
    local expected_process_root="$3"
    local expected_command_token="$4"
    local expected_tmux_session="${5:-}"
    local canonical_process_root
    local record_file
    local runtime_id
    local hostname
    local record_service
    local pid
    local pgid
    local start_time
    local source_root
    local process_root
    local port
    local tmux_session
    local tmux_session_id
    local tmux_pane_id
    local command_token
    local current_start
    local current_pgid
    local current_cwd
    local current_command
    local listener_snapshot
    local listener_pid
    local listener_command
    local tmux_snapshot

    canonical_process_root="$(aura_reset_canonical_dir "$expected_process_root")" || return 1
    record_file="$(aura_reset_service_record_file "$service")"
    [ -f "$record_file" ] || {
        aura_reset_owner_error "$service has no owner record for readiness verification"
        return 1
    }

    runtime_id="$(aura_reset_record_value "$record_file" runtime_id)"
    hostname="$(aura_reset_record_value "$record_file" hostname)"
    record_service="$(aura_reset_record_value "$record_file" service)"
    pid="$(aura_reset_record_value "$record_file" pid)"
    pgid="$(aura_reset_record_value "$record_file" pgid)"
    start_time="$(aura_reset_record_value "$record_file" start_time)"
    source_root="$(aura_reset_record_value "$record_file" source_root)"
    process_root="$(aura_reset_record_value "$record_file" process_root)"
    port="$(aura_reset_record_value "$record_file" port)"
    tmux_session="$(aura_reset_record_value "$record_file" tmux_session)"
    tmux_session_id="$(aura_reset_record_value "$record_file" tmux_session_id)"
    tmux_pane_id="$(aura_reset_record_value "$record_file" tmux_pane_id)"
    command_token="$(aura_reset_record_value "$record_file" command_token)"

    [ "$runtime_id" = "$AURA_RESET_OWNER_RUNTIME_ID" ] \
        && [ "$hostname" = "$AURA_RESET_OWNER_HOSTNAME" ] \
        && [ "$record_service" = "$service" ] \
        && [ "$source_root" = "$AURA_RESET_OWNER_SOURCE_ROOT" ] \
        && [ "$process_root" = "$canonical_process_root" ] \
        && [ "$port" = "$expected_port" ] \
        && { [ -z "$tmux_session" ] || [ "$tmux_session" = "$expected_tmux_session" ]; } \
        && { [ -z "$tmux_session" ] \
            || { [[ "$tmux_session_id" =~ ^\$[0-9]+$ ]] \
                && [[ "$tmux_pane_id" =~ ^%[0-9]+$ ]]; }; } \
        && [ "$command_token" = "$expected_command_token" ] \
        && [[ "$pid" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$pgid" =~ ^[1-9][0-9]*$ ]] \
        && { [ -n "$tmux_session" ] || [ "$pid" = "$pgid" ]; } \
        && [ -n "$start_time" ] || {
            aura_reset_owner_error "$service owner record does not match readiness identity"
            return 1
        }

    aura_reset_pid_alive "$pid" || {
        aura_reset_owner_error "$service owner pid exited before readiness verification"
        return 1
    }
    current_start="$(aura_reset_process_start_time "$pid")"
    current_pgid="$(aura_reset_process_pgid "$pid")"
    current_cwd="$(aura_reset_process_cwd "$pid")"
    current_command="$(aura_reset_process_command "$pid")"
    [ "$current_start" = "$start_time" ] && [ "$current_pgid" = "$pgid" ] || {
        aura_reset_owner_error "$service pid/start-time/process-group changed before readiness verification"
        return 1
    }
    case "$current_cwd" in
        "$canonical_process_root"|"$canonical_process_root"/*) ;;
        *)
            aura_reset_owner_error "$service process cwd is outside its registered source root"
            return 1
            ;;
    esac
    case "$current_command" in
        *"$expected_command_token"*) ;;
        *)
            aura_reset_owner_error "$service process command no longer matches '$expected_command_token'"
            return 1
            ;;
    esac

    if [ -n "$tmux_session" ]; then
        tmux_snapshot="$(aura_reset_tmux_session_snapshot_by_id "$tmux_session_id")" || return 1
        [ "$tmux_snapshot" = "$tmux_session_id|$tmux_pane_id|$pid" ] || {
            aura_reset_owner_error "tmux immutable identity no longer matches owner record for $tmux_session"
            return 1
        }
    fi

    listener_snapshot="$(aura_reset_listener_snapshot "$expected_port")" || return 1
    [ -n "$listener_snapshot" ] || {
        aura_reset_owner_error "$service reported ready but has no listener on port $expected_port"
        return 1
    }
    while IFS=$'\t' read -r listener_pid listener_command; do
        [ -n "$listener_pid" ] || continue
        if ! aura_reset_listener_is_owned \
            "$listener_pid" "$listener_command" "$pid" "$pgid" "$start_time" \
            "$canonical_process_root"; then
            aura_reset_owner_error "$service ready port $expected_port has foreign listener pid=$listener_pid"
            return 1
        fi
    done <<< "$listener_snapshot"
}

aura_reset_stop_service() {
    local service="$1"
    local expected_port="$2"
    local expected_process_root="$3"
    local expected_command_token="$4"
    local expected_tmux_session="${5:-}"
    local record_file
    local canonical_process_root
    local runtime_id
    local hostname
    local record_service
    local pid
    local pgid
    local start_time
    local source_root
    local process_root
    local port
    local tmux_session
    local tmux_session_id
    local tmux_pane_id
    local command_token
    local current_start
    local current_pgid
    local current_cwd
    local current_command
    local listeners
    local listener_pid
    local listener_command
    local tmux_snapshot
    local attempts

    canonical_process_root="$(aura_reset_canonical_dir "$expected_process_root")" || return 1
    record_file="$(aura_reset_service_record_file "$service")"
    if [ ! -f "$record_file" ]; then
        aura_reset_assert_tmux_available "$expected_tmux_session" || return 1
        aura_reset_assert_port_available "$service" "$expected_port" || return 1
        return 0
    fi

    runtime_id="$(aura_reset_record_value "$record_file" runtime_id)"
    hostname="$(aura_reset_record_value "$record_file" hostname)"
    record_service="$(aura_reset_record_value "$record_file" service)"
    pid="$(aura_reset_record_value "$record_file" pid)"
    pgid="$(aura_reset_record_value "$record_file" pgid)"
    start_time="$(aura_reset_record_value "$record_file" start_time)"
    source_root="$(aura_reset_record_value "$record_file" source_root)"
    process_root="$(aura_reset_record_value "$record_file" process_root)"
    port="$(aura_reset_record_value "$record_file" port)"
    tmux_session="$(aura_reset_record_value "$record_file" tmux_session)"
    tmux_session_id="$(aura_reset_record_value "$record_file" tmux_session_id)"
    tmux_pane_id="$(aura_reset_record_value "$record_file" tmux_pane_id)"
    command_token="$(aura_reset_record_value "$record_file" command_token)"

    [ "$runtime_id" = "$AURA_RESET_OWNER_RUNTIME_ID" ] \
        && [ "$hostname" = "$AURA_RESET_OWNER_HOSTNAME" ] \
        && [ "$record_service" = "$service" ] \
        && [ "$source_root" = "$AURA_RESET_OWNER_SOURCE_ROOT" ] \
        && [ "$process_root" = "$canonical_process_root" ] \
        && [ "$port" = "$expected_port" ] \
        && { [ -z "$tmux_session" ] || [ "$tmux_session" = "$expected_tmux_session" ]; } \
        && { [ -z "$tmux_session" ] \
            || { [[ "$tmux_session_id" =~ ^\$[0-9]+$ ]] \
                && [[ "$tmux_pane_id" =~ ^%[0-9]+$ ]]; }; } \
        && [ "$command_token" = "$expected_command_token" ] \
        && [[ "$pid" =~ ^[1-9][0-9]*$ ]] \
        && [[ "$pgid" =~ ^[1-9][0-9]*$ ]] \
        && { [ -n "$tmux_session" ] || [ "$pid" = "$pgid" ]; } \
        && [ -n "$start_time" ] || {
            aura_reset_owner_error "$service owner record does not match the requested runtime/source/port/command"
            return 1
        }

    listeners="$(aura_reset_listener_pids "$expected_port")" || return 1
    if [ -z "$listeners" ] && ! aura_reset_port_is_bindable "$expected_port"; then
        aura_reset_owner_error "$service port $expected_port is occupied but its listener PID cannot be verified"
        return 1
    fi
    if ! aura_reset_pid_alive "$pid"; then
        [ -z "$listeners" ] || {
            aura_reset_owner_error "$service owner pid is dead but port $expected_port has a foreign listener"
            return 1
        }
        aura_reset_assert_tmux_available "$expected_tmux_session" || return 1
        rm -f "$record_file"
        return 0
    fi

    current_start="$(aura_reset_process_start_time "$pid")"
    current_pgid="$(aura_reset_process_pgid "$pid")"
    current_cwd="$(aura_reset_process_cwd "$pid")"
    current_command="$(aura_reset_process_command "$pid")"
    [ "$current_start" = "$start_time" ] && [ "$current_pgid" = "$pgid" ] || {
        aura_reset_owner_error "$service pid/start-time/process-group no longer matches its owner record"
        return 1
    }
    case "$current_cwd" in
        "$canonical_process_root"|"$canonical_process_root"/*) ;;
        *)
            aura_reset_owner_error "$service process cwd moved outside its registered source root"
            return 1
            ;;
    esac
    case "$current_command" in
        *"$expected_command_token"*) ;;
        *)
            aura_reset_owner_error "$service process command no longer matches '$expected_command_token'"
            return 1
            ;;
    esac

    while IFS=$'\t' read -r listener_pid listener_command; do
        [ -n "$listener_pid" ] || continue
        if ! aura_reset_listener_is_owned \
            "$listener_pid" "$listener_command" "$pid" "$pgid" "$start_time" \
            "$canonical_process_root"; then
            aura_reset_owner_error "$service port $expected_port has foreign listener pid=$listener_pid"
            return 1
        fi
    done < <(aura_reset_listener_snapshot "$expected_port")

    if [ -n "$tmux_session" ]; then
        command -v tmux >/dev/null 2>&1 || {
            aura_reset_owner_error "tmux is unavailable for registered session $tmux_session"
            return 1
        }
        tmux_snapshot="$(aura_reset_tmux_session_snapshot_by_id "$tmux_session_id")" || return 1
        [ "$tmux_snapshot" = "$tmux_session_id|$tmux_pane_id|$pid" ] || {
            aura_reset_owner_error "tmux immutable identity no longer matches owner record for $tmux_session"
            return 1
        }
        if ! tmux kill-session -t "$tmux_session_id"; then
            aura_reset_owner_error \
                "registered tmux session changed before immutable-id stop: $tmux_session"
            return 1
        fi
    else
        [ "$pgid" -gt 1 ] 2>/dev/null || {
            aura_reset_owner_error "refusing to signal unsafe process group $pgid"
            return 1
        }
        current_start="$(aura_reset_process_start_time "$pid")"
        current_pgid="$(aura_reset_process_pgid "$pid")"
        [ "$current_start" = "$start_time" ] && [ "$current_pgid" = "$pgid" ] || {
            aura_reset_owner_error "$service owner changed immediately before process-group stop"
            return 1
        }
        kill -TERM -- "-$pgid" 2>/dev/null || true
    fi

    attempts=0
    while aura_reset_pgid_alive "$pgid" && [ "$attempts" -lt 50 ]; do
        sleep 0.1
        attempts=$((attempts + 1))
    done
    if aura_reset_pgid_alive "$pgid"; then
        current_start="$(aura_reset_process_start_time "$pid")"
        current_pgid="$(aura_reset_process_pgid "$pid")"
        if ! aura_reset_pid_alive "$pid" \
            || [ "$current_start" != "$start_time" ] \
            || [ "$current_pgid" != "$pgid" ]; then
            aura_reset_owner_error "$service leader exited while process group $pgid remains; refusing an ambiguous KILL"
            return 1
        fi
        kill -KILL -- "-$pgid" 2>/dev/null || true
        attempts=0
        while aura_reset_pgid_alive "$pgid" && [ "$attempts" -lt 20 ]; do
            sleep 0.1
            attempts=$((attempts + 1))
        done
    fi
    aura_reset_pgid_alive "$pgid" && {
        aura_reset_owner_error "$service process group $pgid did not exit"
        return 1
    }

    aura_reset_assert_port_available "$service" "$expected_port" || return 1
    rm -f "$record_file"
}

aura_reset_spawn_detached() {
    local cwd="$1"
    local log_file="$2"
    shift 2
    python3 - "$cwd" "$log_file" "$@" <<'PY'
import os
import subprocess
import sys

cwd, log_file, *command = sys.argv[1:]
if not command:
    raise SystemExit("missing detached command")
log = open(log_file, "ab", buffering=0)
process = subprocess.Popen(
    command,
    cwd=cwd,
    env=os.environ.copy(),
    stdin=subprocess.DEVNULL,
    stdout=log,
    stderr=subprocess.STDOUT,
    start_new_session=True,
    close_fds=True,
)
print(process.pid)
PY
}
