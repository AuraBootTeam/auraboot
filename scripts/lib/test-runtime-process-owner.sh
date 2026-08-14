#!/usr/bin/env bash

# Fixture integration tests for runtime-process-owner.sh.
# These tests use real detached process groups and real TCP listeners, but only
# inside a throwaway state directory with exact PID cleanup.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROCESS_OWNER_LIB="${AURA_RESET_PROCESS_OWNER_LIB_UNDER_TEST:-$SCRIPT_DIR/runtime-process-owner.sh}"

if [ "${1:-}" = "hold-lock" ]; then
    shift
    source "$PROCESS_OWNER_LIB"
    source_root="$1"
    runtime_label="$2"
    database="$3"
    backend_port="$4"
    web_port="$5"
    bff_port="$6"
    ready_file="$7"
    release_file="$8"
    aura_reset_owner_init fixture "$source_root" "$runtime_label" localhost 5432 "$database" \
        "$backend_port" "$web_port" "$bff_port"
    aura_reset_acquire_locks
    printf 'ready\n' > "$ready_file"
    attempts=0
    while [ ! -f "$release_file" ] && [ "$attempts" -lt 200 ]; do
        sleep 0.05
        attempts=$((attempts + 1))
    done
    [ -f "$release_file" ] || exit 70
    exit 0
fi

if [ "${1:-}" = "try-lock" ]; then
    shift
    source "$PROCESS_OWNER_LIB"
    source_root="$1"
    runtime_label="$2"
    database="$3"
    backend_port="$4"
    web_port="$5"
    bff_port="$6"
    aura_reset_owner_init fixture "$source_root" "$runtime_label" localhost 5432 "$database" \
        "$backend_port" "$web_port" "$bff_port"
    aura_reset_acquire_locks
    aura_reset_release_locks
    exit 0
fi

source "$PROCESS_OWNER_LIB"

command -v lsof >/dev/null 2>&1 || {
    echo "ERROR: lsof is required for runtime process ownership fixture tests" >&2
    exit 69
}
command -v tmux >/dev/null 2>&1 || {
    echo "ERROR: tmux is required for exact-session ownership fixture tests" >&2
    exit 69
}

TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/aura-runtime-owner-test.XXXXXX")"
case "$TEST_ROOT" in
    "${TMPDIR:-/tmp}"/aura-runtime-owner-test.*|/tmp/aura-runtime-owner-test.*) ;;
    *) echo "unsafe fixture path: $TEST_ROOT" >&2; exit 1 ;;
esac
export AURA_RESET_OWNER_STATE_DIR="$TEST_ROOT/state"

FIXTURE_PIDS=""
FIXTURE_TMUX_SESSIONS=""
cleanup() {
    local pid
    local pgid
    for pid in $FIXTURE_PIDS; do
        if kill -0 "$pid" 2>/dev/null; then
            pgid="$(ps -p "$pid" -o pgid= 2>/dev/null | tr -d '[:space:]')"
            if [ "$pgid" = "$pid" ]; then
                kill -TERM -- "-$pgid" 2>/dev/null || true
            else
                kill -TERM "$pid" 2>/dev/null || true
            fi
        fi
    done
    local session
    for session in $FIXTURE_TMUX_SESSIONS; do
        tmux kill-session -t "=$session" 2>/dev/null || true
    done
    sleep 0.1
    rm -rf "$TEST_ROOT"
}
AURA_RESET_OWNER_EXIT_HOOK=cleanup
aura_reset_install_lock_trap

mkdir -p "$TEST_ROOT/source-a" "$TEST_ROOT/source-b" "$TEST_ROOT/source-lock"

free_port() {
    local port
    while true; do
        port="$(python3 - <<'PY'
import socket

with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
        )"
        if [ ! -f "$TEST_ROOT/ports.used" ] || ! grep -qx "$port" "$TEST_ROOT/ports.used"; then
            printf '%s\n' "$port" >> "$TEST_ROOT/ports.used"
            printf '%s\n' "$port"
            return
        fi
    done
}

wait_for_port() {
    local port="$1"
    local attempts=0
    while [ "$attempts" -lt 100 ]; do
        if [ -n "$(lsof -nP -iTCP:"$port" -sTCP:LISTEN -t 2>/dev/null || true)" ]; then
            return 0
        fi
        sleep 0.05
        attempts=$((attempts + 1))
    done
    echo "listener did not become ready: $port" >&2
    return 1
}

wait_for_file() {
    local file="$1"
    local owner_pid="${2:-}"
    local owner_log="${3:-}"
    local attempts=0
    while [ "$attempts" -lt 400 ]; do
        [ -f "$file" ] && return 0
        if [ -n "$owner_pid" ] && ! kill -0 "$owner_pid" 2>/dev/null; then
            echo "owner process exited before file appeared: pid=$owner_pid file=$file" >&2
            [ -n "$owner_log" ] && [ -f "$owner_log" ] && cat "$owner_log" >&2
            return 1
        fi
        sleep 0.05
        attempts=$((attempts + 1))
    done
    echo "file did not appear: $file" >&2
    [ -n "$owner_log" ] && [ -f "$owner_log" ] && cat "$owner_log" >&2
    return 1
}

assert_alive() {
    kill -0 "$1" 2>/dev/null || {
        echo "expected pid $1 to be alive" >&2
        return 1
    }
}

assert_dead() {
    if kill -0 "$1" 2>/dev/null; then
        echo "expected pid $1 to be dead" >&2
        return 1
    fi
}

wait_for_dead() {
    local pid="$1"
    local attempts=0
    while kill -0 "$pid" 2>/dev/null && [ "$attempts" -lt 100 ]; do
        sleep 0.05
        attempts=$((attempts + 1))
    done
    assert_dead "$pid"
}

init_fixture_runtime() {
    local source_root="$1"
    local runtime_label="$2"
    local database="$3"
    local backend_port="$4"
    local web_port="$5"
    local bff_port="$6"
    aura_reset_owner_init fixture "$source_root" "$runtime_label" localhost 5432 "$database" \
        "$backend_port" "$web_port" "$bff_port"
}

start_owned_web() {
    local source_root="$1"
    local runtime_label="$2"
    local database="$3"
    local backend_port="$4"
    local web_port="$5"
    local bff_port="$6"
    init_fixture_runtime "$source_root" "$runtime_label" "$database" \
        "$backend_port" "$web_port" "$bff_port"
    aura_reset_acquire_locks
    aura_reset_assert_port_available web "$web_port"
    FIXTURE_LAST_PID="$(aura_reset_spawn_detached "$source_root" "$TEST_ROOT/${runtime_label}.log" \
        python3 -m http.server "$web_port" --bind 127.0.0.1)"
    FIXTURE_PIDS="$FIXTURE_PIDS $FIXTURE_LAST_PID"
    aura_reset_register_process web "$FIXTURE_LAST_PID" "$web_port" "$source_root" \
        "http.server $web_port"
    aura_reset_release_locks
    wait_for_port "$web_port"
}

echo "Scenario 1: identical commands in runtime A and B; stopping A preserves B"
a_be="$(free_port)"; a_web="$(free_port)"; a_bff="$(free_port)"
b_be="$(free_port)"; b_web="$(free_port)"; b_bff="$(free_port)"
start_owned_web "$TEST_ROOT/source-a" slot-a fixture_a "$a_be" "$a_web" "$a_bff"
a_pid="$FIXTURE_LAST_PID"
start_owned_web "$TEST_ROOT/source-b" slot-b fixture_b "$b_be" "$b_web" "$b_bff"
b_pid="$FIXTURE_LAST_PID"
assert_alive "$a_pid"
assert_alive "$b_pid"

init_fixture_runtime "$TEST_ROOT/source-a" slot-a fixture_a "$a_be" "$a_web" "$a_bff"
aura_reset_acquire_locks
aura_reset_stop_service web "$a_web" "$TEST_ROOT/source-a" "http.server $a_web"
aura_reset_release_locks
assert_dead "$a_pid"
assert_alive "$b_pid"

init_fixture_runtime "$TEST_ROOT/source-b" slot-b fixture_b "$b_be" "$b_web" "$b_bff"
aura_reset_acquire_locks
aura_reset_stop_service web "$b_web" "$TEST_ROOT/source-b" "http.server $b_web"
aura_reset_release_locks
assert_dead "$b_pid"
echo "  PASS"

echo "Scenario 2: an unregistered foreign listener is never killed"
foreign_be="$(free_port)"; foreign_web="$(free_port)"; foreign_bff="$(free_port)"
foreign_pid="$(aura_reset_spawn_detached "$TEST_ROOT/source-a" "$TEST_ROOT/foreign.log" \
    python3 -m http.server "$foreign_web" --bind 127.0.0.1)"
FIXTURE_PIDS="$FIXTURE_PIDS $foreign_pid"
wait_for_port "$foreign_web"
init_fixture_runtime "$TEST_ROOT/source-a" foreign-target fixture_foreign \
    "$foreign_be" "$foreign_web" "$foreign_bff"
aura_reset_acquire_locks
if aura_reset_stop_service web "$foreign_web" "$TEST_ROOT/source-a" \
    "http.server $foreign_web" 2> "$TEST_ROOT/foreign-stop.err"; then
    echo "foreign stop unexpectedly succeeded" >&2
    exit 1
fi
aura_reset_release_locks
grep -q "unverified process" "$TEST_ROOT/foreign-stop.err"
assert_alive "$foreign_pid"
kill -TERM -- "-$foreign_pid"
wait "$foreign_pid" 2>/dev/null || true
wait_for_dead "$foreign_pid"
echo "  PASS"

echo "Scenario 3: a changed PID start-time record fails closed"
stale_be="$(free_port)"; stale_web="$(free_port)"; stale_bff="$(free_port)"
start_owned_web "$TEST_ROOT/source-a" stale-owner fixture_stale \
    "$stale_be" "$stale_web" "$stale_bff"
stale_pid="$FIXTURE_LAST_PID"
stale_record="$(aura_reset_service_record_file web)"
awk '
  /^start_time=/ { print "start_time=Mon Jan  1 00:00:00 1990"; next }
  { print }
' "$stale_record" > "$stale_record.mutated"
mv "$stale_record.mutated" "$stale_record"
aura_reset_acquire_locks
if aura_reset_stop_service web "$stale_web" "$TEST_ROOT/source-a" \
    "http.server $stale_web" 2> "$TEST_ROOT/stale-stop.err"; then
    echo "start-time mismatch unexpectedly allowed a stop" >&2
    exit 1
fi
aura_reset_release_locks
grep -q "start-time" "$TEST_ROOT/stale-stop.err"
assert_alive "$stale_pid"
kill -TERM -- "-$stale_pid"
wait_for_dead "$stale_pid"
echo "  PASS"

echo "Scenario 4: runtime and database locks are mutually exclusive"
lock_be="$(free_port)"; lock_web="$(free_port)"; lock_bff="$(free_port)"
lock_ready="$TEST_ROOT/lock.ready"; lock_release="$TEST_ROOT/lock.release"
bash "$0" hold-lock "$TEST_ROOT/source-lock" lock-a shared_lock_db \
    "$lock_be" "$lock_web" "$lock_bff" "$lock_ready" "$lock_release" \
    > "$TEST_ROOT/lock-holder.log" 2>&1 &
lock_holder_pid=$!
FIXTURE_PIDS="$FIXTURE_PIDS $lock_holder_pid"
wait_for_file "$lock_ready" "$lock_holder_pid" "$TEST_ROOT/lock-holder.log"

init_fixture_runtime "$TEST_ROOT/source-lock" lock-a shared_lock_db \
    "$lock_be" "$lock_web" "$lock_bff"
if aura_reset_acquire_locks 2> "$TEST_ROOT/runtime-lock.err"; then
    echo "same runtime lock unexpectedly allowed a second owner" >&2
    exit 1
fi
grep -q "lock is held" "$TEST_ROOT/runtime-lock.err"
assert_alive "$lock_holder_pid"

other_be="$(free_port)"; other_web="$(free_port)"; other_bff="$(free_port)"
aura_reset_owner_init fixture "$TEST_ROOT/source-lock" lock-b 127.0.0.1 5432 \
    shared_lock_db "$other_be" "$other_web" "$other_bff"
if aura_reset_acquire_locks 2> "$TEST_ROOT/db-lock.err"; then
    echo "same database lock unexpectedly allowed a second owner" >&2
    exit 1
fi
grep -q "db-" "$TEST_ROOT/db-lock.err"
assert_alive "$lock_holder_pid"
touch "$lock_release"
wait "$lock_holder_pid"
echo "  PASS"

echo "Scenario 5: different slots acquire locks in parallel"
p1_be="$(free_port)"; p1_web="$(free_port)"; p1_bff="$(free_port)"
p2_be="$(free_port)"; p2_web="$(free_port)"; p2_bff="$(free_port)"
p1_ready="$TEST_ROOT/p1.ready"; p1_release="$TEST_ROOT/p1.release"
p2_ready="$TEST_ROOT/p2.ready"; p2_release="$TEST_ROOT/p2.release"
bash "$0" hold-lock "$TEST_ROOT/source-lock" parallel-a parallel_db_a \
    "$p1_be" "$p1_web" "$p1_bff" "$p1_ready" "$p1_release" \
    > "$TEST_ROOT/p1.log" 2>&1 &
p1_pid=$!
FIXTURE_PIDS="$FIXTURE_PIDS $p1_pid"
bash "$0" hold-lock "$TEST_ROOT/source-lock" parallel-b parallel_db_b \
    "$p2_be" "$p2_web" "$p2_bff" "$p2_ready" "$p2_release" \
    > "$TEST_ROOT/p2.log" 2>&1 &
p2_pid=$!
FIXTURE_PIDS="$FIXTURE_PIDS $p2_pid"
wait_for_file "$p1_ready" "$p1_pid" "$TEST_ROOT/p1.log"
wait_for_file "$p2_ready" "$p2_pid" "$TEST_ROOT/p2.log"
assert_alive "$p1_pid"
assert_alive "$p2_pid"
touch "$p1_release" "$p2_release"
wait "$p1_pid"
wait "$p2_pid"
echo "  PASS"

echo "Scenario 6: different runtimes using the same host ports are mutually exclusive"
port_ready="$TEST_ROOT/port.ready"; port_release="$TEST_ROOT/port.release"
shared_be="$(free_port)"; shared_web="$(free_port)"; shared_bff="$(free_port)"
bash "$0" hold-lock "$TEST_ROOT/source-a" port-owner-a port_db_a \
    "$shared_be" "$shared_web" "$shared_bff" "$port_ready" "$port_release" \
    > "$TEST_ROOT/port-holder.log" 2>&1 &
port_holder_pid=$!
FIXTURE_PIDS="$FIXTURE_PIDS $port_holder_pid"
wait_for_file "$port_ready" "$port_holder_pid" "$TEST_ROOT/port-holder.log"
init_fixture_runtime "$TEST_ROOT/source-b" port-owner-b port_db_b \
    "$shared_be" "$shared_web" "$shared_bff"
if aura_reset_acquire_locks 2> "$TEST_ROOT/port-lock.err"; then
    echo "same host ports unexpectedly allowed a second runtime" >&2
    exit 1
fi
grep -q "tcp-port-" "$TEST_ROOT/port-lock.err"
assert_alive "$port_holder_pid"
touch "$port_release"
wait "$port_holder_pid"
echo "  PASS"

echo "Scenario 7: a live lock cannot be reclaimed by changing the caller timezone"
tz_ready="$TEST_ROOT/tz.ready"; tz_release="$TEST_ROOT/tz.release"
tz_be="$(free_port)"; tz_web="$(free_port)"; tz_bff="$(free_port)"
TZ=UTC bash "$0" hold-lock "$TEST_ROOT/source-lock" timezone-owner timezone_db \
    "$tz_be" "$tz_web" "$tz_bff" "$tz_ready" "$tz_release" \
    > "$TEST_ROOT/tz-holder.log" 2>&1 &
tz_holder_pid=$!
FIXTURE_PIDS="$FIXTURE_PIDS $tz_holder_pid"
wait_for_file "$tz_ready" "$tz_holder_pid" "$TEST_ROOT/tz-holder.log"
if TZ=Asia/Shanghai bash "$0" try-lock "$TEST_ROOT/source-lock" \
    timezone-owner timezone_db "$tz_be" "$tz_web" "$tz_bff" \
    > "$TEST_ROOT/tz-contender.log" 2>&1; then
    echo "timezone change reclaimed a live lock" >&2
    exit 1
fi
grep -q "lock is held" "$TEST_ROOT/tz-contender.log"
assert_alive "$tz_holder_pid"
touch "$tz_release"
wait "$tz_holder_pid"
echo "  PASS"

echo "Scenario 8: readiness rejects a foreign listener that wins after preflight"
race_be="$(free_port)"; race_web="$(free_port)"; race_bff="$(free_port)"
init_fixture_runtime "$TEST_ROOT/source-a" readiness-race readiness_race_db \
    "$race_be" "$race_web" "$race_bff"
aura_reset_acquire_locks
aura_reset_assert_port_available web "$race_web"
owned_race_pid="$(aura_reset_spawn_detached "$TEST_ROOT/source-a" "$TEST_ROOT/race-owned.log" \
    python3 -c 'import time; time.sleep(60)' owned-no-listener)"
FIXTURE_PIDS="$FIXTURE_PIDS $owned_race_pid"
aura_reset_register_process web "$owned_race_pid" "$race_web" \
    "$TEST_ROOT/source-a" owned-no-listener
foreign_race_pid="$(aura_reset_spawn_detached "$TEST_ROOT/source-b" "$TEST_ROOT/race-foreign.log" \
    python3 -m http.server "$race_web" --bind 127.0.0.1)"
FIXTURE_PIDS="$FIXTURE_PIDS $foreign_race_pid"
wait_for_port "$race_web"
if aura_reset_assert_service_owned web "$race_web" "$TEST_ROOT/source-a" \
    owned-no-listener 2> "$TEST_ROOT/race-owner.err"; then
    echo "foreign listener was accepted as the owned service" >&2
    exit 1
fi
grep -q "foreign listener" "$TEST_ROOT/race-owner.err"
assert_alive "$owned_race_pid"
assert_alive "$foreign_race_pid"
kill -TERM -- "-$foreign_race_pid"
wait_for_dead "$foreign_race_pid"
aura_reset_stop_service web "$race_web" "$TEST_ROOT/source-a" owned-no-listener
aura_reset_release_locks
assert_dead "$owned_race_pid"
echo "  PASS"

echo "Scenario 9: lock-record mutation cannot produce a successful release"
mutation_be="$(free_port)"; mutation_web="$(free_port)"; mutation_bff="$(free_port)"
init_fixture_runtime "$TEST_ROOT/source-lock" mutation-owner mutation_db \
    "$mutation_be" "$mutation_web" "$mutation_bff"
aura_reset_acquire_locks
mutated_lock="${AURA_RESET_OWNER_HELD_LOCKS[0]}"
touch "$mutated_lock/foreign-entry"
if aura_reset_release_locks 2> "$TEST_ROOT/mutation-release.err"; then
    echo "mutated lock release falsely reported success" >&2
    exit 1
fi
grep -q "refusing to release lock" "$TEST_ROOT/mutation-release.err"
[ -d "$mutated_lock" ] || {
    echo "mutated lock was not preserved fail-closed" >&2
    exit 1
}
rm -f "$mutated_lock/foreign-entry"
aura_reset_release_locks
echo "  PASS"

echo "Scenario 10: exact tmux-session stop preserves an identical foreign session"
tmux_be="$(free_port)"; tmux_web="$(free_port)"; tmux_bff="$(free_port)"
tmux_foreign_port="$(free_port)"
tmux_owned_session="$(aura_reset_normalize_tmux_session "aura.owner.it.owned.$$")"
tmux_foreign_session="${tmux_owned_session}_foreign"
[ "$tmux_owned_session" = "aura_owner_it_owned_$$" ] || {
    echo "dotted tmux session name was not normalized deterministically" >&2
    exit 1
}
FIXTURE_TMUX_SESSIONS="$FIXTURE_TMUX_SESSIONS $tmux_owned_session $tmux_foreign_session"
init_fixture_runtime "$TEST_ROOT/source-a" tmux-owner tmux_db \
    "$tmux_be" "$tmux_web" "$tmux_bff"
aura_reset_acquire_locks
aura_reset_assert_tmux_available "$tmux_owned_session"
tmux_owned_identity="$(tmux new-session -d -P \
    -F '#{session_id}|#{pane_id}|#{pane_pid}' \
    -s "$tmux_owned_session" -c "$TEST_ROOT/source-a" \
    "exec python3 -m http.server $tmux_web --bind 127.0.0.1")"
tmux new-session -d -s "$tmux_foreign_session" -c "$TEST_ROOT/source-b" \
    "exec python3 -m http.server $tmux_foreign_port --bind 127.0.0.1"
wait_for_port "$tmux_web"
wait_for_port "$tmux_foreign_port"
IFS='|' read -r tmux_owned_session_id tmux_owned_pane_id tmux_owned_pid \
    <<< "$tmux_owned_identity"
tmux_foreign_pid="$(tmux display-message -p -t "=$tmux_foreign_session:0.0" '#{pane_pid}')"
aura_reset_register_process web "$tmux_owned_pid" "$tmux_web" \
    "$TEST_ROOT/source-a" "http.server $tmux_web" "$tmux_owned_session" \
    "$tmux_owned_session_id" "$tmux_owned_pane_id"
aura_reset_assert_service_owned web "$tmux_web" "$TEST_ROOT/source-a" \
    "http.server $tmux_web" "$tmux_owned_session"
tmux() {
    if [ "${1:-}" = "kill-session" ] \
        && [ "${AURA_TMUX_PREFIX_RACE_ARMED:-0}" = "1" ]; then
        AURA_TMUX_PREFIX_RACE_ARMED=0
        command tmux kill-session -t "=$tmux_owned_session"
        command tmux new-session -d -s "$tmux_owned_session" \
            -c "$TEST_ROOT/source-b" \
            "exec sleep 60"
    fi
    command tmux "$@"
}
AURA_TMUX_PREFIX_RACE_ARMED=1
if aura_reset_stop_service web "$tmux_web" "$TEST_ROOT/source-a" \
    "http.server $tmux_web" "$tmux_owned_session" \
    2> "$TEST_ROOT/tmux-prefix-race.err"; then
    echo "tmux TOCTOU prefix race unexpectedly reported success" >&2
    exit 1
fi
unset -f tmux
grep -q "changed before immutable-id stop" "$TEST_ROOT/tmux-prefix-race.err"
wait_for_dead "$tmux_owned_pid"
command tmux has-session -t "=$tmux_owned_session" 2>/dev/null || {
    echo "same-name replacement tmux session was stopped" >&2
    exit 1
}
command tmux has-session -t "=$tmux_foreign_session" 2>/dev/null || {
    echo "foreign tmux session was stopped" >&2
    exit 1
}
assert_alive "$tmux_foreign_pid"
tmux_replacement_pid="$(tmux display-message -p \
    -t "=$tmux_owned_session:0.0" '#{pane_pid}')"
assert_alive "$tmux_replacement_pid"
tmux kill-session -t "=$tmux_owned_session"
aura_reset_stop_service web "$tmux_web" "$TEST_ROOT/source-a" \
    "http.server $tmux_web" "$tmux_owned_session"
tmux kill-session -t "=$tmux_foreign_session"
aura_reset_release_locks
echo "  PASS"

echo "runtime process ownership fixture IT: PASS (10 scenarios)"
