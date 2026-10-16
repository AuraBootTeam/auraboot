#!/usr/bin/env bash
# Run the existing deployment BFF/SSR in production; the loopback ingress keeps the runtime port contract.
set -euo pipefail
case "${AURA_WEB_MODE:-development}" in
  production)
    test -n "${SESSION_SECRET_FILE:-}" && test -r "$SESSION_SECRET_FILE" \
      || { echo 'Explicit SESSION_SECRET_FILE required for production' >&2; exit 1; }
    export SESSION_SECRET="$(cat "$SESSION_SECRET_FILE")"
    test "${#SESSION_SECRET}" -ge 32 || { echo 'Production session secret too short' >&2; exit 1; }
    test -f build/server/index.js || { echo 'Production SSR build missing' >&2; exit 1; }
    exec env NODE_ENV=production pnpm exec concurrently --kill-others-on-fail --names ingress,bff \
      'node --import ./instrumentation.server.mjs scripts/production-entry.mjs' 'pnpm dev:bff'
    ;;
  development)
    if [ "${1:-fresh}" = retained ]; then
      exec env NODE_ENV=development pnpm exec concurrently --names web,bff --prefix-colors cyan,green --restart-tries 20 --restart-after 2000 'pnpm dev:web' 'pnpm dev:bff'
    fi
    exec env NODE_ENV=development pnpm dev:full
    ;;
  *) echo 'Unsupported AURA_WEB_MODE' >&2; exit 1;;
esac
