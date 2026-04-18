# GAP-266 — SSRF Historical Audit: Pinned-IP Call Sites vs IPv6 / Multi-A Bypass

**Date:** 2026-04-18
**Branch:** `sec/gap-266-ssrf-audit-historical`
**Related hardening commit:** `b52212a1` (SsrfValidator IPv4-mapped unwrap + paranoid multi-A reject)

## 1. Context

Before `b52212a1`, `SsrfValidator` had two known gaps that the 10 pinned-IP call sites (BPM hook `rest_call` / `CallApi` / `CustomTool` / `McpClient` + Webhook / `ApiConnector` / Gmail / LLM / `Endpoint` / `HttpServiceTask`) relied on:

1. **IPv4-mapped IPv6 bypass** — a URL hostname literal like `[::ffff:127.0.0.1]` resolved to a loopback IPv4 but was not recognised as private by the old block-list because the check ran against the raw `Inet6Address` without unwrapping the embedded IPv4.
2. **Multi-A record bypass** — when a hostname resolved to multiple A records (e.g. `httpbin.org` → 6 IPs), the old validator only inspected the *first* record; an attacker controlling DNS could interleave a private IP among public ones and bypass.

This audit asks: **did any persisted URL in the database historically match either pattern?** If yes, we must quarantine or re-validate.

## 2. Audit scope

Tables inspected (URL-bearing columns only, OSS production DB `aura_boot`):

| Table | Column(s) | Owning call site |
|-------|-----------|------------------|
| `ab_webhook_subscription` | `target_url` | Webhook |
| `ab_api_connector` | `base_url` | ApiConnector |
| `ab_api_connector_endpoint` | `path` (relative, skipped — resolved against `base_url`) | Endpoint |
| `ab_bpm_node_hook` | `hook_config` (JSONB) | BPM hook `rest_call` / `HttpServiceTask` |
| `ab_automation` | `actions` / `flow_config` (JSONB) | `send_webhook` action |
| `ab_agent_mcp_server` | `server_url` | McpClient |

`ab_webhook_delivery_log`, `ab_automation_log`, `ab_automation_debug_session` are runtime logs, not configuration, and were skipped.

Other call sites (Gmail, LLM, `CustomTool`, `CallApi`) do not persist per-tenant URLs in OSS — they target vendor-fixed endpoints configured via `application.yml` / env vars (not tenant-writable), so DB audit is N/A; config files were spot-checked and hold only `https://api.openai.com`, `https://gmail.googleapis.com`, etc.

## 3. Queries executed

```sql
-- Webhook targets
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE target_url LIKE '%[%]%')            AS bracketed_ipv6,
       COUNT(*) FILTER (WHERE target_url LIKE '%::ffff%')         AS ipv4_mapped,
       COUNT(*) FILTER (WHERE target_url ~ '\d{1,3}(\.\d{1,3}){3}') AS ipv4_literal
  FROM ab_webhook_subscription;

-- API connector base URLs
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE base_url LIKE '%[%]%')    AS bracketed_ipv6,
       COUNT(*) FILTER (WHERE base_url LIKE '%::ffff%') AS ipv4_mapped
  FROM ab_api_connector;

-- BPM node hook config blobs
SELECT id, hook_config::text FROM ab_bpm_node_hook
 WHERE hook_config::text ~ 'http';

-- Automation actions / flow config
SELECT id FROM ab_automation
 WHERE (actions::text ~ 'http' OR flow_config::text ~ 'http')
   AND (actions::text    LIKE '%[%]%' OR actions::text    LIKE '%::ffff%'
     OR flow_config::text LIKE '%[%]%' OR flow_config::text LIKE '%::ffff%');

-- MCP server URLs
SELECT COUNT(*) AS total,
       COUNT(*) FILTER (WHERE server_url LIKE '%[%]%'
                            OR server_url LIKE '%::ffff%'
                            OR server_url ~ '\d{1,3}(\.\d{1,3}){3}') AS suspicious
  FROM ab_agent_mcp_server;
```

## 4. Findings

| Source | Rows | Bracketed IPv6 | IPv4-mapped | IPv4 literal | Suspicious |
|--------|------|---------------|-------------|--------------|-----------|
| `ab_webhook_subscription` | 10 | 0 | 0 | 0 | 0 |
| `ab_api_connector` | 0 | 0 | 0 | — | 0 |
| `ab_bpm_node_hook` | 16 (0 with URL payload) | 0 | 0 | — | 0 |
| `ab_automation` | 1 (`send_webhook → https://httpbin.org/post`) | 0 | 0 | — | 0 |
| `ab_agent_mcp_server` | 0 | 0 | 0 | 0 | 0 |

All persisted URLs use one of two hostnames:

- `httpbin.org` — multi-A (6 records): `98.89.132.151, 52.6.193.180, 98.94.233.70, 18.235.15.99, 54.145.142.3, 32.194.101.183`. **All public**, no RFC1918 / loopback, so the old validator would not have been "bypassed" in the exploitable sense (no private IP smuggled in).
- `example.com` — 2 A records (`104.20.23.154, 172.66.147.243`), both public.

No row contains `[::ffff:*]`, `[0:0:0:0:0:ffff:*]`, nor an IPv4 literal that would trigger loopback / link-local / RFC1918 rejection.

## 5. Conclusion

**No historical bypass observed.** The new `SsrfValidator` semantics (IPv4-mapped unwrap + paranoid multi-A all-must-pass) do not change the outcome for any currently-persisted URL — every existing record will continue to pass validation under the new rules.

**Multi-A exposure latent risk**: `httpbin.org` is used both in a webhook subscription and an automation action; its 6-record resolution set is the exact pattern the paranoid-mode change defends against. If an attacker ever added a hostname whose DNS rotated private IPs into its answer set, the old validator (first-record check) would have let it through; the new validator blocks it.

**Remediation needed: NO.** No quarantine migration script is required. The hardening in `b52212a1` is forward-looking; no legacy purge.

## 6. Ongoing guard

- All 10 pinned-IP call sites invoke `SsrfValidator` at request time, not at config-save time. Because validation is lazy, any previously-stored "borderline" URL is re-evaluated on every outbound call — no retro-scan needed.
- Recommend (follow-up, not GAP-266 scope): add a scheduled `SsrfConfigRevalidationJob` that re-runs `SsrfValidator.validate(url)` over the six tables weekly and emits a metric `ssrf.config.reject_count` so that any DNS rotation introducing a private-IP answer trips an alert before runtime.

## 7. Audit evidence artifacts

- Raw query output preserved in commit message / PR description.
- Re-run the queries in §3 against a fresh DB snapshot to reproduce.
