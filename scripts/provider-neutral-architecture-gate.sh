#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

targets=(
  scripts/seed-acp-agents.sql
  scripts/seed-aurabot-agent.sql
  scripts/seed-cs-agent.sql
  scripts/aurabot-scenario-golden-run.sh
  scripts/digital-employee-capability-eval-run.sh
  scripts/digital-employee-golden-run.sh
  scripts/faq-loop-golden-run.sh
  plugins/agent-control-plane/config/dicts.json
  plugins/platform-admin/config/capabilities.json
  web-admin/tests/e2e/agent-control-plane/digital-employee-skill-review.spec.ts
  web-admin/tests/e2e/agent-control-plane/digital-employee-write-approval.spec.ts
  web-admin/tests/e2e/agent-control-plane/ai-colleagues.spec.ts
)

pattern='qwen|deepseek|claude|anthropic|openai|gpt-[0-9]|ollama'
violations="$(
  rg -n -i "$pattern" "${targets[@]}" \
    | rg -v '^scripts/seed-acp-agents\.sql:26:--' \
    || true
)"
if [[ -n "$violations" ]]; then
  echo "provider-neutral architecture gate failed:" >&2
  echo "$violations" >&2
  exit 1
fi

echo "provider-neutral architecture gate passed"
