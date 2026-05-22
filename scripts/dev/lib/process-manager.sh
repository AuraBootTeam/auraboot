#!/bin/bash
# shellcheck shell=bash
#
# Sourceable process helpers for per-worktree host dev services.

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    echo "Usage: source $0" >&2
    exit 2
fi

aura_dev_listen_pids_for_port() {
    local port="$1"
    if command -v lsof >/dev/null 2>&1; then
        lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null | sort -u || true
    fi
}

aura_dev_stop_tmux_session() {
    local session="$1"
    local dry_run="${2:-0}"

    if [ "$dry_run" = "1" ]; then
        echo "tmux kill-session -t $session"
        return 0
    fi

    if command -v tmux >/dev/null 2>&1 && tmux has-session -t "$session" 2>/dev/null; then
        tmux kill-session -t "$session"
        echo "stopped tmux session: $session"
    else
        echo "tmux session not running: $session"
    fi
}

aura_dev_stop_ports() {
    local dry_run="$1"
    shift

    local port
    for port in "$@"; do
        [ -n "$port" ] || continue
        local pids
        pids="$(aura_dev_listen_pids_for_port "$port" | tr '\n' ' ')"
        if [ "$dry_run" = "1" ]; then
            echo "port $port listen pids: ${pids:-none}"
            continue
        fi
        if [ -n "$pids" ]; then
            # shellcheck disable=SC2086
            kill $pids 2>/dev/null || true
            sleep 1
            local survivors
            survivors="$(aura_dev_listen_pids_for_port "$port" | tr '\n' ' ')"
            if [ -n "$survivors" ]; then
                # shellcheck disable=SC2086
                kill -9 $survivors 2>/dev/null || true
            fi
            echo "stopped listeners on port $port"
        else
            echo "no listener on port $port"
        fi
    done
}

aura_dev_stop_web_processes_for_root() {
    local dry_run="$1"
    local web_root="$2"

    [ -n "$web_root" ] || return 0

    local pids
    pids="$(
        ps -axo pid=,command= |
            awk -v root="$web_root" '
                index($0, root) &&
                ($0 ~ /concurrently/ ||
                 $0 ~ /pnpm dev:full/ ||
                 $0 ~ /pnpm dev:web/ ||
                 $0 ~ /pnpm dev:bff/ ||
                 $0 ~ /react-router\/dev\/bin\.js dev/ ||
                 $0 ~ /tsx.*app\/server\/bff\.server\.ts/) {
                    print $1
                }
            ' |
            sort -u
    )"

    if [ "$dry_run" = "1" ]; then
        echo "web process pids for $web_root: ${pids//$'\n'/ }"
        return 0
    fi

    if [ -n "$pids" ]; then
        # shellcheck disable=SC2086
        kill $pids 2>/dev/null || true
        sleep 1
        local survivors
        survivors="$(
            ps -axo pid=,command= |
                awk -v root="$web_root" '
                    index($0, root) &&
                    ($0 ~ /concurrently/ ||
                     $0 ~ /pnpm dev:full/ ||
                     $0 ~ /pnpm dev:web/ ||
                     $0 ~ /pnpm dev:bff/ ||
                     $0 ~ /react-router\/dev\/bin\.js dev/ ||
                     $0 ~ /tsx.*app\/server\/bff\.server\.ts/) {
                        print $1
                    }
                ' |
                sort -u
        )"
        if [ -n "$survivors" ]; then
            # shellcheck disable=SC2086
            kill -9 $survivors 2>/dev/null || true
        fi
        echo "stopped web/BFF processes for $web_root"
    else
        echo "no web/BFF processes for $web_root"
    fi
}
