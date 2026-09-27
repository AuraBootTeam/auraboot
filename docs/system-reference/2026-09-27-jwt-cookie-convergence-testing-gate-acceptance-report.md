---
type: system-reference
status: active
created: 2026-09-27
---

# JWT cookie convergence testing-gate acceptance report

The Web Admin JWT convergence is accepted at **targeted-tested** level: **15 pass, 0 fail,
0 skipped, 0 untested** across the owned authentication/session denominator. Browser JavaScript no
longer persists or reads JWTs except for the bounded, one-release migration bridge; the BFF owns the
httpOnly `__session` cookie, social exchanges terminate at the BFF, and cookie-authenticated writes
receive exact-origin CSRF enforcement.

The machine-readable denominator is the
[`acceptance manifest`](../e2e/evidence/jwt-cookie-convergence-2026-09-27/acceptance-manifest.json).
This is not a claim that the full OSS browser matrix passed: the executed real-stack scope was the
authentication smoke, cookie-convergence, social-callback, logout, and session-lifetime paths.

## Final evidence pack

```text
acceptance_report: docs/system-reference/2026-09-27-jwt-cookie-convergence-testing-gate-acceptance-report.md
claim_level: targeted-tested
business_scope: Web Admin browser authentication, migration, social callback, CSRF, direct authenticated fetches, and BFF diagnostic redaction
typecheck: pnpm typecheck — pass
lint: pnpm lint — exit 0; repository baseline warnings remain
unit_component_tests: 11 files / 99 pass / 0 fail (final rerun after fail-closed social error redaction)
browser_evidence: dedicated runtime jwt-cookie-verify, slot 143, PostgreSQL auraboot_143
browser_specs: smoke 1/1; cookie convergence 2/2; social OAuth callback 3/3; logout plus session lifetime 5/5
feature_action_matrix: acceptance-manifest.json, 15 pass / 0 untested
storage_sweep: zero production JWT storage reads/writes outside app/auth/legacy-session-migration.ts
sse_websocket_sweep: zero token/access_token/jwt query construction in production Web Admin code
log_evidence: BFF PID 30464 logs redact JWT response fields as [REDACTED]
permission_negative: not applicable; no authorization policy changed
visual_feedback: not applicable; no visual or interaction design changed
skip_fixme_threshold_retry_audit: 0 skipped; no retry or threshold masking
did_not_run: complete OSS Playwright matrix and unrelated product domains
remaining_blockers: none for the owned targeted denominator
allowed_claim: targeted Web Admin JWT cookie convergence passed; full OSS regression was not run
```

## Falsifiability and failure classification

The new browser gate initially failed both cookie-convergence cases because Vite `changeOrigin`
rewrote `Host`, making legitimate browser writes appear cross-origin to the BFF. Enabling forwarded
host/protocol metadata and evaluating the browser-facing origin restored 2/2 green after a clean
managed runtime reset. This demonstrates that the CSRF assertion detects a real proxy-boundary
regression rather than only confirming the implementation.

The first smoke invocation completed its business assertion but lacked `PG_DB` for global teardown;
it was classified environment-invalid and rerun with the runtime contract populated. A later social
callback run exposed stale test interception assumptions (query parameters were not included by the
old route glob/body assertion); the test was aligned with the actual callback protocol and then
passed 3/3. Neither result is hidden or counted as a final product pass.

The repository-wide structural test-system gate was also invoked on the rebased head. It passed
command reachability, manifest freshness, derived-field writers, and the hand-written page matrix,
but failed on three pre-existing stale QuoteOps registry names plus unrelated scripts README
inventory drift (`check-license-boundary.sh`, `extract-changelog-release.mjs`, and the missing
`docker-cleanup-batch-up.sh`). Those files are outside this JWT change and were not folded into this
PR.

## Deliberate compatibility boundary

The legacy migration reads `jwtToken` (and the older `jwt` alias) once, sends it only in an
Authorization header to a same-origin BFF endpoint, validates it against the backend, commits the
cookie, and clears legacy JWT/refresh/expiry keys. Invalid credentials are cleared; transient network
failures are retained for retry. This bridge is intentionally temporary and should be removed after
one release once the installed-session population has crossed the migration window.
