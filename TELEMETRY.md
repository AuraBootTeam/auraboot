# AuraBoot Telemetry

> Last updated: 2026-05-09

## TL;DR

**AuraBoot ships zero telemetry by default.** The community-edition
binary, frontend, and Docker images do not phone home, do not register
installations, and do not send anonymized usage statistics. Network
calls happen only for features the operator explicitly enables.

## What does cross the network

When AuraBoot is running, network egress comes from:

| Source | Destination | When |
|---|---|---|
| LLM provider SDK | OpenAI / Anthropic / Zhipu / etc. | Only when an AI feature (AuraBot, ChatBI, RAG, ACP) is invoked AND the operator has configured a provider API key |
| Webhook plugin | URLs the operator configured | Only when a configured webhook fires (e.g., on record create) |
| Plugin marketplace | https://plugins.auraboot.com | Only when an admin browses or installs a plugin from the marketplace UI |
| License verifier | License-issuance domain (configurable) | Only for commercial-license deployments that opted in to remote validation |
| OS / package manager updates | Distro-controlled | Standard system updates, not AuraBoot |
| OpenTelemetry / Sentry exports | Operator-configured collector endpoints | Only when `MANAGEMENT_TRACING_*` / `SENTRY_DSN` are set |

The platform itself initiates **no other outbound traffic**. There is
no usage analytics, no feature-flag service polling, no error-reporting
endpoint operated by The AuraBoot Project.

## How to verify

If your security policy requires it, drop the AuraBoot deployment in a
network-egress-deny environment with only your LLM provider whitelisted.
The platform should function for all non-AI features. Watch outbound
traffic with:

```bash
# In a docker-compose deployment
docker run --rm --network container:auraboot-platform-1 nicolaka/netshoot \
  tcpdump -nni any -c 200 'not port 22 and not port 53 and not host postgres and not host redis'
```

Anything that surprises you is a bug — please open an issue.

## Future telemetry plans

We may add **opt-in** anonymous usage telemetry in a future release to
help prioritize roadmap work (e.g., which DSL block types get used,
which LLM providers see traffic). When that happens:

1. The default will be **opt-out** off (you must explicitly enable it).
2. The data collected will be enumerated in this file.
3. The collection endpoint and exact wire format will be open and
   inspectable in the source code.
4. There will be one config flag — `aura.telemetry.enabled` — and one
   env var — `AURA_TELEMETRY_ENABLED` — to toggle.

We will never:
- Default telemetry to on
- Collect personal data, customer data, or schema content
- Sell telemetry data
- Share telemetry data with third parties
- Add telemetry to the commercial edition only (same default for both)

## Reporting a telemetry concern

If you find AuraBoot phoning home in a way not described here, that's a
bug AND a security/privacy issue. Email security@auraboot.com or open a
public issue with reproduction steps.
