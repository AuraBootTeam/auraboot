# SINGLE Business Tenant + Party Actor 验收证据（2026-08-09）

## Scope / environment

- Branch: `codex/single-business-tenant-party-actor-20260809`
- Worktree: `auraboot-single-business-tenant-party-actor-20260809`
- Isolated runtime: `auraboot-party-actor-long`
- Real stack: PostgreSQL 16.14, Redis 7, Spring Boot backend, Remix/Vite Web Admin
- Final rebuilt runtime ports: PostgreSQL 5481, backend 6492, Vite 5222, BFF 3549
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

## Deliberate partials / residual scope

- Complete data model does not mean complete UI/workflow. Generic Party invitation acceptance, Capability approval workbench, relation/network management, and all legacy business domains becoming Party-aware are deferred.
- IdentityProviderInstance and ExternalIdentityLink are present, while unified external IdP management and full OAuth/LDAP federation orchestration remain partial; legacy social-link/provider paths stay compatible.
- The browser journey was semantically inspected but not promoted to a new Playwright release gate in this branch.
