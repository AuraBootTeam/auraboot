---
type: retro
status: active
created: 2026-09-22
---

# WeChat PC self-provision acceptance report (2026-09-22)

SOT: `aura-edu/docs/system-reference/05-identity-and-access.md` §2, §2b.

allowed_claim: Targeted unit verification passed. The browser and real WeChat login journey has not run against this change; production remains on the previous image until separately authorized deployment.

## Evidence

- `cd platform && ./gradlew :test --tests 'com.auraboot.framework.auth.wechat.WechatPcIdentityServiceTest' --no-daemon --quiet`: exit 0.
- `platform/build/test-results/test/TEST-com.auraboot.framework.auth.wechat.WechatPcIdentityServiceTest.xml`: 4 executed, 4 passed, 0 skipped, 0 failures, 0 errors.
- Cases: existing web identity, mini-to-web UnionID attachment, new tenantless account in self-service mode, rejection when self-service is disabled.
- Earlier attempts with the wrong Gradle task scope failed before executing tests; they are not counted as product failures or passes.

## Final Evidence Pack

```text
acceptance_report: docs/retro/2026-09-22-wechat-pc-self-provision-testing-gate-acceptance-report.md
claim_level: targeted pass
current_sot: aura-edu/docs/system-reference/05-identity-and-access.md
business_scope: First PC WeChat scan for a tenantless school creator in multi/self_service mode
integration_tests: did_not_run
integration_coverage: coverage_not_measured
e2e_specs: none added
e2e_collection: did_not_run
e2e_execution: browser E2E did_not_run (executed=0)
feature_action_matrix: identity resolution paths 4/4 targeted unit cases; full school onboarding not measured
browser_evidence: did_not_run
backend_evidence: 4/4 Mockito unit cases; no real WeChat code exchange on new build
artifact_evidence: Gradle test XML, local worktree only
permission_negative: self-service-disabled path rejects an unknown WeChat identity in unit test
visual_feedback: not applicable; no UI change
skip_fixme_threshold_retry_audit: no skips or retries in targeted suite; no threshold used
did_not_run: real-stack integration, browser E2E, production QR scan, school creation after login
remaining_blockers: merge and deployment authorization; live WeChat verification after deployment
allowed_claim: targeted unit verification passed; production fix not yet verified
```
