---
type: artifact
status: active
created: 2026-08-09
---

# SINGLE Business Tenant + Party Actor 验收证据（2026-08-09）

## Scope / environment

- Branch: `codex/single-business-tenant-party-actor-20260809`
- Worktree: `auraboot-single-business-tenant-party-actor-20260809`
- Isolated runtime: `auraboot-party-actor-long`
- Real stack: PostgreSQL 16.14, Redis 7, Spring Boot backend, Remix/Vite Web Admin
- Final rebuilt runtime ports: PostgreSQL 5481, backend 6492, Vite 5222, BFF 3549
- Federated-identity incremental runtime: PostgreSQL 17.6 database
  `aura_sso_idp_20260809`, Redis DB 15, backend 16443, Vite 15173, BFF 13500.
- PC closure runtimes: fresh SINGLE `single-party-sso-pc-final-20260820-s88` and fresh MULTI
  `multi-party-sso-pc-control-20260820-s90`, each with isolated PostgreSQL database, Redis DB,
  latest-source backend, BFF and Vite.
- Secrets and bearer tokens are intentionally omitted.

## Automated results

1. Backend final combined run:
   - Command: `./gradlew :test -x platform-plugin-api:test` with 18 scoped test classes and isolated `TEST_DATABASE_URL`.
   - Result: exit 0, 148/148 tests passed, 0 skipped.
   - Includes `PartyActorPersistenceIntegrationTest` against real PostgreSQL.
2. Web:
   - Result file: `web-admin/test-results/results.json`.
   - Result: 6/6 suites, 12/12 tests passed; actor-switch action covers precision-safe Party ID, replacement cookie, malformed input and local redirect/auth fail-closed.
   - `pnpm typecheck`: exit 0.
   - Generated acceptance report: `web-admin/test-results/runs/party-actor-long/acceptance.html` (`14 pass / 2 deliberate partial / 0 red / 16 total`).
   - PC closure: `pnpm typecheck` passed; five focused Vitest files passed 24/24; the new
     `social-oauth-callback.spec.ts` passed 3/3 on both SINGLE and MULTI; SINGLE registration and
     OTP mode-aware actions passed 1/1 each; MULTI auth setup passed 4/4, space selection passed
     5/5, and registration mode-aware action passed 1/1.
3. Schema:
   - `scripts/db/check-schema-drift.sh --edition oss` against a fresh database: exit 0.
   - 56 migrations applied through `V20260809120000`; regenerated 28,752-line snapshot exactly matches committed snapshot.
4. Structural gates:
   - `check-e2e-spec-registration`: pass.
   - Whole-repo `check-test-system` and `check-command-reachability`: fail on pre-existing/unrelated CRM QDP declarations (`crm:submit_qdp_review`, `crm:release_qdp`) and missing CRM QDP coverage rows. This branch did not change those baselines and does not count them as Party acceptance passes.

## Real API / database journey

- Public access policy returned: `single / closed / disabled / approval_required / invitation=true / actorSwitch=true`.
- Public login channel registry returned `email_password` for `business-web/default-business-web`.
- Fresh schema-snapshot initialization contained all Party 12 tables and IAM 5 tables; bootstrap repair created exactly one default application/channel/auth-method record.
- Admin login went directly to the unique Default Business Tenant; System Tenant was not selected as business context.
- Created Party `supplier-e2e`: initial lifecycle `pending`; approval changed it and its membership to `active`.
- Actor switch response carried `scope=party`, the expected Party/Membership, `stage=ready`, `cv=8` on the final rebuilt runtime.
- After switch, the old token returned HTTP 401 and the replacement token returned HTTP 200.
- Earlier persistence inspection matched `party | actorPartyId=1 | partyMembershipId=1 | ready | cv=5 | revoked=false`; subsequent smoke switches advanced the same Actor preference to `cv=8`.
- Real PostgreSQL IT proved Party mapper queries remain TenantLineInterceptor-filtered, login registry queries support a typed nullable tenant parameter, and application/channel capability policy rejects then accepts the same Party as its active capability changes.
- Tenant create/join policy is enforced in `TenantApplicationServiceImpl`, so internal callers cannot bypass the controller boundary.

## Browser semantic review

- Login page showed no public registration link under `CLOSED` policy.
- Login entered `/home` without Tenant create/join/select UI.
- User menu displayed `Business identity → Supplier E2E`.
- Selecting it completed the switch, returned to `/home`, showed a check beside `Supplier E2E`, and disabled the already-current option.
- Browser console errors: 0.
- Screenshots: `docs/coverage/evidence/single-business-tenant-party-actor-login.jpg` and `docs/coverage/evidence/single-business-tenant-party-actor-selected.jpg`.
- This is an `agent-vision` result; it is weaker than an executable browser spec and is reported separately from the Web unit/action tests.

## SQL performance evidence

Within a transaction, 20,000 Party rows were inserted for a realistic planner decision, analyzed, queried, then rolled back. Query predicate: `tenant_id + lifecycle_status + deleted_flag=false`.

```text
Bitmap Heap Scan on ab_party
  -> Bitmap Index Scan on idx_party_tenant_status
Execution Time: 0.938 ms
Rows returned: 1,000
Post-rollback leaked_perf_rows: 0
```

This proves the fixed `tenant_id` predicate can use the tenant-leading index. It is not a production latency SLA and does not claim every future Party-aware query is optimized.

## Falsifiability record

Controlled mutation: temporarily remove the provisional `MetaContext` tenant/member installation before Party membership revalidation in `JwtAuthenticationFilter`.

- Expected red: Party revalidation cannot observe a current tenant while Party tables remain behind TenantLineInterceptor.
- Actual red: targeted test exit 1; `partyTokenRevalidatesMembershipAndAddsOnlyPartyScopedRoles` failed at line 212.
- Restore: reinstalled the verified JWT tenant/member context before the Party authorization lookup.
- Restored green: the same targeted test passed.

Controlled mutation: temporarily make application/channel capability intersection always return true in `ActorCandidateResolverImpl`.

- Expected red: a supplier-only login channel must exclude a buyer-only Party Actor.
- Actual red: targeted resolver suite exited 1; `applicationChannelFiltersCandidatesByActivePartyCapability` failed.
- Restore: reinstated active Party capability intersection and tenant/global channel ownership checks.
- Restored green: 4/4 resolver tests passed, the real PostgreSQL IT passed, and the final 148-test combined batch passed.

## Federated identity incremental evidence

### Automated contracts

- OSS registry/channel/management/bootstrap targeted batch: 31/31 passed, plus the bootstrap
  billing-account test passed 1/1 on an isolated PostgreSQL database created from the current
  schema snapshot. It includes Web/mobile
  instance separation, mobile-only status changes, inline-secret rejection, exact redirect
  allow-listing, and remote HTTP redirect rejection.
- Enterprise federated-identity targeted batch: 24/24 passed across transaction, flow,
  canonical/legacy identity routing, OIDC, Apple, WeChat and LDAP. A wrong native/browser binding
  consumes the Redis state before rejection, and a retry with the correct binding still fails.
- The added `FederatedOAuthHttpIntegrationTest` runs through real Spring MVC, PostgreSQL and Redis.
  Only the external IdP network exchange is controlled. It proves the server ignores a forged
  SINGLE tenant, creates the canonical link and session through the shared admission pipeline,
  validates nonce/PKCE/redirect data, and consumes a wrong-bound state so it cannot be replayed.
- The changed OSS login-channel compatibility suite passed 13/13. Tenant-admin toggles now merge
  built-in password/OTP methods with canonical federated descriptors, so enabling `email_code`
  is reflected by both the string and typed public login-channel APIs.
- OIDC hardening rejects metadata, loopback, non-HTTP and plain-HTTP discovery URLs before
  outbound I/O; discovery, authorization, token, JWKS and userinfo endpoints must use HTTPS.
- A controlled identity-routing mutation restored canonical miss → legacy `providerType + subject`
  fallback. `SocialOAuthAuthStrategyTest` then ran 2 tests with 1 failure at the explicit
  never-call-legacy assertion. The restored code uses only `IdP instance + subject`; the same
  strategy test returned 2/2 and the complete federated batch returned 22/22. Legacy lookup/write
  is restricted to legacy provider configuration.
- Apple Web uses `response_type=code&response_mode=query` with no scope, matching AuraBoot's
  GET callback route; its dedicated contract test passes.
- WeChat Website contract covers the official QR authorization shape, server state,
  `openid` subject, optional `unionid`, and the fact that WeChat supplies no verified email.
- LDAP 5/5 passed: an in-process UnboundID server is reached over the real TCP/JNDI protocol;
  correct bind/search returns immutable `entryUUID`, while wrong password and escaped-filter
  injection fail. Canonical instance non-secret config is merged with a
  `cloud-config:` secret reference and routed through the shared admission/link strategy.
- Android OAuth-focused unit tests passed 39/39 (`LoginViewModelTest` 23/23 and
  `AuthRepositoryImplTest` 16/16), and the debug app plus test APKs assembled. The complete
  Features unit suite still has one unrelated existing `DynamicListViewModelTest` failure, so it
  is not reported as globally green. iOS native OAuth tests passed 4/4.
- A fresh SINGLE real-stack runtime exposed a dynamic `Mobile OIDC Fixture` only through
  `business-mobile/default-business-mobile`. Android connected tests on the booted emulator
  passed 3/3, and iOS XCUITests on the booted Simulator passed 3/3. Both exercised the live
  channel-options API, ordinary password login, and the authenticated social-link surface; these
  are simulator/emulator evidence, not physical-device evidence.

### Fresh PostgreSQL, Redis and API

- OSS Flyway/schema snapshot exact check passed at 57 migrations and 28,788 lines; Enterprise
  snapshot exact check passed at 63 migrations and 32,484 lines.
- Enterprise Flyway applied and validated 63 migrations through `V20260809130000` on a fresh
  PostgreSQL 17.6 database. The first fresh bootstrap exposed an obsolete conflict target left
  by the partial unique-index migration; `ON CONFLICT DO NOTHING` repaired both the fresh path
  and idempotent retry. Bootstrap creates/repairs both
  `business-web/default-business-web` and `business-mobile/default-business-mobile`.
- Real admin APIs created two OIDC instances with the same business code but different
  application/client/redirect settings. Disabling only the mobile instance removed only the
  mobile descriptor; Web stayed active. An inline `clientSecret` request returned HTTP 400.
- Web OAuth start resolved the Web instance and Google discovery, and Redis stored a 10-minute
  one-time transaction containing server-selected tenant/application/channel/instance,
  redirect, nonce and PKCE verifier. Redis stores only the client-binding SHA-256 digest, never
  the binding token itself.
- Native start resolved the mobile instance and custom-scheme redirect. Missing binding, wrong
  binding and Web/native platform mismatch all failed closed. Wrong binding destroyed the state,
  and retry could not replay it.
- The latest-source backend started healthy on PostgreSQL plus Redis DB 15. After password login,
  a controlled canonical-link API fixture returned the current-tenant link once and returned no
  link for a deliberately cross-tenant row. Provider instance code, display name and email were
  projected without tokens; cleanup left 0 fixture rows.
- A startup attempt without an explicit Redis host/port did not create `StringRedisTemplate` and
  failed the required OAuth strategy dependency. With the real Redis connection declared, the
  same source started successfully; no in-memory state/merge-token fallback was added.

### Real browser journey

- The real login page displayed Password plus `Google OIDC Canary` from
  `business-web/default-business-web`; it did not borrow the mobile channel.
- Clicking the dynamic button reached `accounts.google.com` and received the expected
  `invalid_client` for the intentionally invalid acceptance client. This proves the UI → registry
  → discovery → authorize path, not a successful Google login.
- A forged local callback rendered `OAuth state validation failed` before backend exchange.
- Password login still reached `/home`. The authenticated Social Account Binding page displayed
  the same active instance as `Not linked` with a Bind action; semantic DOM and rendered pixels
  were both inspected.
- The versioned Playwright social-link suite executed 5/5 green on the same authenticated runtime:
  live descriptor/link union, arbitrary `company-oidc` rendering, profile navigation, and a
  controlled LINK start that asserts POST, follows the server authorize URL, and stores the exact
  provider-scoped state.
- The versioned OAuth callback suite executed 3/3 on a fresh SINGLE runtime and 3/3 on a fresh
  MULTI runtime with empty storage. Its success case obtains a real JWT through the real password
  login endpoint and controls only the callback response; it asserts callback payload, token
  persistence and deployment-aware routing. The negative cases prove a state mismatch never calls
  the callback API and consumes local state, and that an empty password cannot submit account merge.
  Static trust review found no skip/fixme/only, wait timeout, retry or threshold escape hatch; its
  only `page.request.post` is the setup-only real password login used to obtain the backend-valid JWT.
- SINGLE open registration and email-code login each bypassed Tenant selection and entered the
  business workspace. MULTI password/registration retained `/tenant-selection`; authenticated setup
  passed 4/4 and the existing platform/business space-selection journey passed 5/5, including header,
  switcher and platform-console behavior.

### Physical-device denominator

- `adb devices -l` reports no Android physical device.
- The paired iPhone 13 Pro Max runs iOS 26.5.2 with Developer Mode enabled. A final retry reached
  `available (paired)`, `ddiServicesAvailable=true` and `tunnelState=connected`; the physical Debug
  build then failed at signing because Xcode could not use an iOS Development certificate for Team
  `D48N9943FK` and had no development profiles for `com.auraboot.ios`, `.share`, `.widgets`, or the
  UI-test runner. `-allowProvisioningUpdates` was deliberately not used, so no Apple account state
  changed and the app was not installed.
- Therefore native deep-link/state/binding/login remains partial. No simulator or source build is
  counted as a physical-device pass.

### External-provider denominator

- Google, WeChat, WeCom, generic OIDC and Microsoft client identifiers/secrets are unset in the
  verification environment. A safe scan of the known runtime databases found zero OAuth credential
  records in `ab_cloud_config`.
- Therefore a production/sandbox authorization-code → token → userinfo/id-token exchange could not
  be executed. The intentionally invalid Google authorize canary remains initiation-only evidence.

## 2026-08-22 main-conflict resolution rerun

- Merged the current `origin/main` into the PR branch. The only textual conflict was the generated
  OSS schema snapshot. It was regenerated from the combined 76 Flyway migrations rather than edited
  by hand; `scripts/db/check-schema-drift.sh --edition oss` passed against a fresh PostgreSQL 17.6
  database.
- Fresh verification runtime: `pr1006-oss-auth-20260822-s87`, slot 87, database `auraboot_87`,
  source root `/Users/ghj/work/auraboot/.worktrees/auraboot-single-business-tenant-party-actor-20260809`.
- The 20 affected Auth/Party backend test classes executed 162 tests with 162 passed, 0 failed and
  0 skipped. The batch includes `PartyActorPersistenceIntegrationTest` against the fresh PostgreSQL
  database.
- Focused Web verification passed: OAuth state, actor switching and the auto-merged root auth loader
  suites executed 18/18 tests; `pnpm typecheck` passed.
- Documentation governance passed with 0 errors and 0 warnings. E2E spec registration passed.
- The workspace-wide test-system gate still reports unrelated current-main CRM lead-pool command
  reachability/coverage rows and script-index drift. Those failures are not counted as Party/Auth
  passes and are tracked in the workspace closeout task.

## Deliberate partials / residual scope

- Complete data model does not mean complete UI/workflow. Generic Party invitation acceptance, Capability approval workbench, relation/network management, and all legacy business domains becoming Party-aware are deferred.
- Canonical IdP management API and OAuth/LDAP orchestration are implemented while legacy
  social-link/provider paths stay compatible. Real external Google/WeChat credentials and the
  physical-device native journeys remain explicitly partial.
- The external provider exchange remains deliberately partial, but the local Web journey now has
  both semantic/pixel review and an executable 5-case Playwright gate.
