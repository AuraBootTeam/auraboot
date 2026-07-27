---
type: system-reference
status: active
---

# Agent runtime risk approval testing-gate acceptance report

Date: 2026-07-27

## Scope

This change removes the semantic conflict between mobile AI-action risk and
the agent execution runtime:

- L3/L4 mutating effects are marked approval-required by
  `RuntimeAuthorizationService`;
- `ToolLoopService` routes that decision through the existing
  `AgentApprovalGateService`, including its policy lookup, idempotency, resume,
  and one-time grant consumption;
- L4 means irreversible/high risk that requires approval, not an implicit
  permanent prohibition;
- AuraBot skills explicitly declare that their preview/confirmation lifecycle
  owns approval so a second generic approval is not created.

No parallel guardrail service or new approval store is introduced.

## Layer matrix

| Layer | Evidence | Result |
| --- | --- | --- |
| Unit | risk-scale bridge, command-risk assessment, ceiling intersection, external-confirmation ownership | Pass |
| Service | ToolLoop consumes RuntimeAuthorization approval decisions through AgentApprovalGateService and does not dispatch before approval | Pass |
| Real PostgreSQL | high-risk authorization decision persists `require_approval=true`, policy id, and reason | Pass |
| Existing integration | low-risk grants and plan authorization remain unchanged | Pass |

## Commands and results

- Targeted `:test` suite for `AiActionRiskAssessorTest`,
  `AiActionRiskLevelBridgeTest`, `AuthorizationIntersectionTest`, and
  `ToolLoopServiceSafetyTest` — pass.
- `RuntimeAuthorizationServiceIntegrationTest` against isolated runtime database
  `auraboot_97` — pass, including the L4 approval-required row.
- `../gradlew testClasses --no-daemon` — pass.

## Acceptance

The runtime authorization and approval integration is accepted for review.
An irreversible action remains executable only after the existing approval
policy path grants it; absence of a matching policy continues to fail secure.
