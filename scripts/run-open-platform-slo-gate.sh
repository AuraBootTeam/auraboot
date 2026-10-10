#!/usr/bin/env bash
set -euo pipefail

profile="${1:-smoke}"
if [[ "$profile" != "smoke" && "$profile" != "production" ]]; then
  echo "usage: $0 [smoke|production]" >&2
  exit 2
fi
: "${CLIENT_ID:?CLIENT_ID is required}"
: "${CLIENT_SECRET:?CLIENT_SECRET is required}"
if [[ "$profile" == production ]]; then
  : "${BUSINESS_FIXTURE_PATH:?production requires BUSINESS_FIXTURE_PATH}"
  : "${K6_SUMMARY_EXPORT:?production requires K6_SUMMARY_EXPORT}"
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
command -v k6 >/dev/null || { echo "k6 is required" >&2; exit 2; }

summary_path="${K6_SUMMARY_EXPORT:-}"
# k6 interprets this variable as its built-in export, which includes setup data.
unset K6_SUMMARY_EXPORT
k6_args=(run)
if [[ -n "$summary_path" ]]; then
  k6_args+=(--env "SUMMARY_PATH=$summary_path")
fi

export CLIENT_ID CLIENT_SECRET
if [[ -n "${BUSINESS_FIXTURE_PATH:-}" ]]; then k6_args+=(--env "BUSINESS_FIXTURE_PATH=$BUSINESS_FIXTURE_PATH"); fi
k6 "${k6_args[@]}" \
  --env "PROFILE=$profile" \
  --env "BASE_URL=${BASE_URL:-http://localhost:6443}" \
  "$repo_dir/tests/load/k6/open-platform-slo.js"

if [[ -n "$summary_path" ]]; then
  verify_args=("$summary_path" "$profile")
  if [[ -z "${BUSINESS_FIXTURE_PATH:-}" ]]; then verify_args+=(--baseline-only); fi
  python3 "$script_dir/ci/verify-open-platform-slo.py" "${verify_args[@]}"
fi
