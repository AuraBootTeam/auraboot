# DecisionOps UI Golden Coverage Matrix

This plugin uses DSL pages first. Full-page React routes are limited to the legacy
`/decision-ops` redirect into the DSL workspace.

## Page Matrix

| Page | Route | Source | Must Verify |
| --- | --- | --- | --- |
| Legacy entry | `/decision-ops` | React loader redirect | Redirects to `/p/decisionops_rollouts`; no naked console text; no raw JSON/code leak. |
| Decision definitions | `/p/decisionops_definitions` | DSL custom block + table | `DecisionDefinitionCatalogBlock` renders; API datasource `/api/decision/definitions`; columns fit; detail and rollout actions render; backend error remains visible inline. |
| DMN decision table | `/p/decisionops_tables` | DSL custom block | `DecisionTableWorkbenchBlock` loads; hit policy, row add/delete, cell operator/value edits, preview JSON, validation state. |
| Event Policy | `/p/decisionops_event_policies` | DSL custom block + table | Quick view loads default FORM complaint slice; list columns fit; design/log actions render. |
| Rollout governance | `/p/decisionops_rollouts` | DSL custom block + table | `DecisionRolloutMonitorBlock` loads; backend error state is visible when rollout API is unavailable; table layout remains stable. |
| Execution logs | `/p/decisionops_execution_logs` | DSL custom block | Empty prompt before `traceId`; no default `/recent` request; trace query renders success/error without page breakage. |
| Model fields | `/p/decisionops_model_fields` | DSL custom block + table | `DecisionModelFieldCatalogBlock` renders; API datasource `/api/decision/model/fields`; impact action renders; backend absence surfaces as error state. |
| Connectors | `/p/decisionops_connectors` | DSL table | Reuses platform connector management via `/p/api_connector`; no duplicate connector editor. |
| Webhooks | `/p/decisionops_webhooks` | DSL table | Reuses platform webhook management via `/p/webhook`; no duplicate webhook editor. |

## Action Coverage

Every browser E2E that claims DecisionOps page completion must cover:

- Page entry from menu and direct URL.
- Every visible row action button.
- Every toolbar action, including export/print visibility when present or hidden by DSL.
- Custom block primary actions, form fields, disabled states, and error states.
- Backend request URL, status, and response shape for each datasource.
- Download/export filename and content when export is enabled.
- Screenshot evidence for desktop layout; add mobile screenshot when the page is used on mobile.

## Runtime Gaps To Keep Visible

- Current frontend must not mask missing backend endpoints with fake data.
- `/api/decision/rollouts` and `/api/decision/model/fields` require current-source backend verification before they can be marked fully green.
- Designer-style future scope must reuse existing designer kernels unless explicitly approved as a special React/designer page.
