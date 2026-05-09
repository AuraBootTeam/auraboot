# Public Demo Mode

> Operations guide for running AuraBoot as a public demo (e.g. demo.auraboot.com).
>
> Demo mode is a runtime configuration; the same image runs in both production
> and demo deployments — only environment variables and a sidecar reset cron
> differ.

## What demo mode changes

Setting `aura.demo.enabled=true` (or env `AURA_DEMO_MODE=true`) activates the
[`DemoModeGuard`](../../platform/src/main/java/com/auraboot/framework/demo/DemoModeGuard.java)
servlet filter, which rejects requests matching configured deny patterns with
HTTP 403, regardless of caller authentication state.

**Default deny patterns** (configurable via `aura.demo.deny-paths`):

> Patterns marked _verified_ correspond to a controller that exists in the
> OSS repo today; _preventive_ patterns block future endpoints under those
> prefixes so a new feature added later doesn't accidentally become a
> demo-write loophole.

| Category | Patterns | Status |
|---|---|---|
| Plugin packages | `/api/plugins/packages/**`, `/api/plugins/*/install`, `/api/plugins/*/uninstall`, `/api/marketplace/install/**`, `/api/marketplace/*/install` | _verified_ — package upload via `PluginPackageController` |
| Admin | `/api/admin/**` | _verified_ — 18+ controllers under this prefix |
| Auth-sensitive | `/api/auth/forgot-password`, `/api/auth/reset-password`, `/api/users/*/password` | _verified_ — `AuthController` |
| Test / internal | `/api/test/**`, `/api/_internal/**` | _verified_ — `TestSeedController` |
| License | `/api/license/**`, `/api/admin/license/**` | _preventive_ — license issuance is enterprise-only today |
| System danger zone | `/api/system/migrate`, `/api/system/reset`, `/api/system/danger/**` | _preventive_ |
| Tenant lifecycle | `/api/tenants/*/destroy`, `/api/tenants/*/transfer-ownership` | _preventive_ |

**Allowlist** (`aura.demo.allow-paths`) overrides the denylist.

## What demo mode does NOT change

- Authentication still works (JWT, sessions, login)
- Reading data from any endpoint is unaffected
- Creating / updating user-data records (CRM leads, ERP orders, etc.) is allowed
- The reset cron sidecar is responsible for wiping user data periodically — see
  [auraboot-website/deploy/demo/reset-loop.sh](../../../auraboot-website/deploy/demo/reset-loop.sh)

The threat model is "anonymous public visitor with browser access", not
"authenticated tenant admin trying to escalate". Anyone you grant tenant-admin
can still create/update/delete records *within their tenant*; demo data is
disposable, so this is by design.

## Configuration

```yaml
aura:
  demo:
    enabled: ${AURA_DEMO_MODE:false}
    banner: ${AURA_DEMO_BANNER:This is a public demo — data is wiped every 30 minutes. Don't enter sensitive info.}
    reset-interval-min: ${AURA_DEMO_RESET_INTERVAL_MIN:30}
    # Add to defaults; do not replace unless you know what you're doing
    # deny-paths:
    #   - /api/your/extra/pattern/**
    # Override-allow specific paths
    # allow-paths:
    #   - /api/admin/system-info  # exposed for the demo banner
```

## Hardening checklist before going public

Before flipping Cloudflare to proxy mode and publishing the demo URL:

- [ ] Verify `aura.demo.enabled=true` is actually loaded (`/api/health` returns
      `X-Auraboot-Demo: true` header? — *only the 403 response carries this header
      today; consider extending `/api/system/info` to surface it for the frontend banner*)
- [ ] Smoke-test 5 deny patterns each return 403:
      ```bash
      for p in /api/license/issue /api/plugins/upload /api/admin/users \
               /api/auth/reset-password /api/users/abc/password ; do
        echo -n "$p → "
        curl -s -o /dev/null -w "%{http_code}\n" -X POST "https://demo.auraboot.com$p"
      done
      ```
      All should return 403.
- [ ] Verify reset-loop is actually running:
      ```bash
      docker compose logs --since 2h reset-cron | grep -E "initial seed|resetting"
      ```
- [ ] Verify TLS (Let's Encrypt cert issued, HSTS header set)
- [ ] Verify rate limit triggers after configured threshold
- [ ] Sentry DSN configured and at least one test event flows
- [ ] Login page banner visible and contains "Don't enter sensitive info"
- [ ] Demo personas have realistic names, NOT `test_user_1` / `dev_admin`
- [ ] Seed data covers at least one example per major feature

## What if a malicious endpoint slips past the denylist?

1. Add the pattern to `aura.demo.deny-paths` config — no code change needed
2. `docker compose restart auraboot` to pick it up
3. File a bug against AuraBoot OSS proposing a default-deny addition
4. The reset-cron will wipe any state changes within 30 minutes anyway
