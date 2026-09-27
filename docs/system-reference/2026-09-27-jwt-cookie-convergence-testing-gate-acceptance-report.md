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
This is not a claim that the full OSS browser matrix passed. The full regular project was executed
once to completion and remains red on repository-wide baseline failures; a post-fix diagnostic rerun
was deliberately interrupted after identifying an OSS-scope mismatch that would otherwise turn one
missing independent BPM service into dozens of 90-second cascade failures.

## Final evidence pack

```text
acceptance_report: docs/system-reference/2026-09-27-jwt-cookie-convergence-testing-gate-acceptance-report.md
claim_level: targeted-tested
business_scope: Web Admin browser authentication, migration, social callback, CSRF, direct authenticated fetches, and BFF diagnostic redaction
typecheck: pnpm typecheck — pass
lint: pnpm lint — exit 0; repository baseline warnings remain
unit_component_tests: 11 files / 100 pass / 0 fail (final rerun includes Referer fallback and hostile-Origin precedence)
browser_evidence: dedicated fresh runtime jwt-cookie-full-verify, slot 144, PostgreSQL auraboot_144
browser_specs: final OSS smoke/auth/cookie/community 31/31; owned JWT denominator 15/15
full_oss_regular_initial: 1182 collected / 366 passed / 393 failed / 80 skipped / 343 did-not-run; no disconnect signature
full_oss_regular_post_fix_diagnostic: 1135 executed before intentional stop / 710 passed / 123 failed / 302 skipped / 47 did-not-run; no disconnect signature
full_oss_regular_fix_effect: the 246 direct CSRF 403 failures in the initial run disappeared after same-origin Referer support for Playwright APIRequestContext
full_oss_deep: not run because the blocking regular project remained red
feature_action_matrix: acceptance-manifest.json, 15 pass / 0 untested
storage_sweep: zero production JWT storage reads/writes outside app/auth/legacy-session-migration.ts
sse_websocket_sweep: zero token/access_token/jwt query construction in production Web Admin code
log_evidence: BFF PID 30464 logs redact JWT response fields as [REDACTED]
permission_negative: not applicable; no authorization policy changed
visual_feedback: not applicable; no visual or interaction design changed
skip_fixme_threshold_retry_audit: 0 skipped; no retry or threshold masking
did_not_run: OSS deep project; 47 regular cases after the post-fix diagnostic stop
remaining_blockers: none for the owned targeted denominator; repository-wide OSS scope and baseline failures remain outside this JWT change
allowed_claim: targeted Web Admin JWT cookie convergence passed; full OSS regular was run and is not green
```

## Falsifiability and failure classification

The new browser gate initially failed both cookie-convergence cases because Vite `changeOrigin`
rewrote `Host`, making legitimate browser writes appear cross-origin to the BFF. Enabling forwarded
host/protocol metadata and evaluating the browser-facing origin restored 2/2 green after a clean
managed runtime reset. This demonstrates that the CSRF assertion detects a real proxy-boundary
regression rather than only confirming the implementation.

The first full OSS regular run then exposed a second boundary: Playwright `page.request` shares the
browser cookie jar but does not add browser navigation metadata. Cookie-authenticated mutation calls
therefore lacked both `Origin` and `Referer`, and the new CSRF middleware correctly returned 403. The
test profile now supplies the exact Web origin as `Referer` only to browser projects (not setup/auth
projects that call Spring directly). The middleware accepts that exact Referer only when `Origin` is
absent; an explicit hostile `Origin` remains authoritative and is rejected. Final smoke was 31/31,
and the post-fix full diagnostic contained no recurrence of the original CSRF-403 cluster.

The first smoke invocation completed its business assertion but lacked `PG_DB` for global teardown;
it was classified environment-invalid and rerun with the runtime contract populated. A later social
callback run exposed stale test interception assumptions (query parameters were not included by the
old route glob/body assertion); the test was aligned with the actual callback protocol and then
passed 3/3. Neither result is hidden or counted as a final product pass.

The repository-wide structural test-system gate initially exposed three stale QuoteOps registry
names plus scripts README inventory drift (`check-license-boundary.sh`,
`extract-changelog-release.mjs`, and the missing `docker-cleanup-batch-up.sh`). The follow-up commit
removed the three nonexistent spec names, registered the two real scripts, and removed the dead
script row. The final `./scripts/check-test-system.sh` run passed every structural sub-gate; its
command-reachability messages are explicitly baselined warnings rather than failures.

The completed full regular run used the same clean database and collected all 1182 cases. Its 393
failures include genuine pre-existing product/test-contract failures and serial cascades. The
post-fix diagnostic proved the JWT-related CSRF cluster was removed, but also found that the OSS
scope currently includes tests which require independent applications: for example,
`unified-designer-workbench.spec.ts` creates `/api/bpm/process-definitions`, although `aura-bpm` is
explicitly an independent application in `oss-scope.json`. That denominator debt must be repaired
and the complete regular/deep sequence rerun before any full-OSS-green claim.

## Deliberate compatibility boundary

The legacy migration reads `jwtToken` (and the older `jwt` alias) once, sends it only in an
Authorization header to a same-origin BFF endpoint, validates it against the backend, commits the
cookie, and clears legacy JWT/refresh/expiry keys. Invalid credentials are cleared; transient network
failures are retained for retry. This bridge is intentionally temporary and should be removed after
one release once the installed-session population has crossed the migration window.
