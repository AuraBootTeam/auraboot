#!/usr/bin/env bash
set -euo pipefail

profile="${1:-smoke}"
if [[ "$profile" != "smoke" && "$profile" != "production" ]]; then
  echo "usage: $0 [smoke|production]" >&2
  exit 2
fi
: "${CLIENT_ID:?CLIENT_ID is required}"
: "${CLIENT_SECRET:?CLIENT_SECRET is required}"

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
command -v k6 >/dev/null || { echo "k6 is required" >&2; exit 2; }

k6_args=(run)
if [[ -n "${K6_SUMMARY_EXPORT:-}" ]]; then
  k6_args+=(--summary-export "$K6_SUMMARY_EXPORT")
fi

exec k6 "${k6_args[@]}" \
  --env "PROFILE=$profile" \
  --env "BASE_URL=${BASE_URL:-http://localhost:6443}" \
  --env "CLIENT_ID=$CLIENT_ID" \
  --env "CLIENT_SECRET=$CLIENT_SECRET" \
  "$repo_dir/tests/load/k6/open-platform-slo.js"
