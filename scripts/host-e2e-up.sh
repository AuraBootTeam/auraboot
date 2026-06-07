#!/usr/bin/env bash
# Host-mode E2E stack bring-up — host parity with docker-ga-e2e-up.sh.
#
# oss-reset-and-init.sh starts a plain host dev stack (real LLM provider, strict
# SSRF), which is correct for day-to-day development but makes the deterministic
# E2E goldens fail on host:
#   - LLM goldens (AuraBot ResultContract, action-llm-call, agent scenarios) hit a
#     real provider with no key → fail. The docker GA E2E stack routes them to the
#     StubLlmProvider via AGENT_LLM_STUB_MODE=true; this wrapper does the same.
#   - call_api goldens make a real outbound HTTP round-trip. The docker stack reaches
#     the host via host.docker.internal; on host the targets are loopback, which
#     SsrfValidator blocks unless allowlisted. AURA_SSRF_ALLOWED_PRIVATE_HOSTS=127.0.0.1
#     opens the existing escape hatch (the backend's own port 6443 stays blocked).
#
# These are scoped to E2E only — exporting them here, not in oss-reset-and-init.sh,
# keeps plain `oss-reset-and-init.sh` dev runs on the real LLM + strict SSRF.
#
# Companion test-run env (set when invoking the Playwright run, not the stack):
#   E2E_OUTBOUND_HOST=127.0.0.1
#   E2E_CALLAPI_OK_URL=http://127.0.0.1:3500/health
#   E2E_CALLAPI_404_URL=http://127.0.0.1:3500/api/this-endpoint-does-not-exist-404
#
# Usage: scripts/host-e2e-up.sh        # then: <test-run env> scripts/oss-test.sh ...
# Pass-through args go to oss-reset-and-init.sh (e.g. --no-bootstrap).
set -euo pipefail

cd "$(dirname "$0")/.."

# Deterministic LLM (canned StubLlmProvider, no real credentials) — mirrors
# docker-ga-e2e-up.sh. Override by exporting AGENT_LLM_STUB_MODE=false first.
export AGENT_LLM_STUB_MODE="${AGENT_LLM_STUB_MODE:-true}"

# Let call_api goldens reach loopback neighbours (e.g. the BFF on :3500); the
# platform backend port (6443) remains in SsrfValidator.BLOCKED_PORTS by design.
export AURA_SSRF_ALLOWED_PRIVATE_HOSTS="${AURA_SSRF_ALLOWED_PRIVATE_HOSTS:-127.0.0.1}"

echo "[host-e2e-up] AGENT_LLM_STUB_MODE=$AGENT_LLM_STUB_MODE  AURA_SSRF_ALLOWED_PRIVATE_HOSTS=$AURA_SSRF_ALLOWED_PRIVATE_HOSTS"

exec env FORCE_HOST="${FORCE_HOST:-1}" bash scripts/oss-reset-and-init.sh "$@"
