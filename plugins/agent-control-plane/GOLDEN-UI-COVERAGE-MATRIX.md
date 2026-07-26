# Agent Control Plane Golden UI Coverage Matrix

This matrix is the user-journey inventory for the Agent Control Plane plugin. The
machine-readable definitions remain in `config/pages.json`, `config/fields.json`,
and `config/commands.json`; the rows below map those contracts to browser evidence.

Status vocabulary:

- `PASS`: an existing browser suite drives the action and asserts the visible result.
- `N/A`: the model intentionally has no detail route; `extension.skipDetailPage=true`
  prevents the platform from publishing an empty generated detail shell.

## Page, block, and field coverage

| Page / model | Blocks | Field coverage | Command / navigation coverage | Browser evidence | Status |
| --- | --- | --- | --- | --- | --- |
| `agent_definition_list/form/detail` | toolbar, table, form-section, form-buttons | identity, model, status, prompt, tools, skills, guardrails, soul profile | create, edit, delete, detail, save, cancel | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `mission_list/form` | toolbar, table, form-section, form-buttons | title, status, owner, priority, target date, description, KPIs, tags | create, edit, pause, resume, complete, archive, delete | `acp-lifecycle-deep.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `mission_detail` | N/A | N/A | no list or toolbar detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_task_list/form` | toolbar, table, form-section, form-buttons | title, status, mission/parent, priority, assignee, due date, retry/cost, input/config, tags | create, edit, dispatch, start, complete, block, cancel, delete | `acp-form-crud.spec.ts`, `acp-lifecycle-deep.spec.ts` | PASS |
| `agent_task_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_run_list/form` | table, form-section, form-buttons | task/agent, status/model, timing, token/cost, tool calls, messages, error, metadata | cancel running execution | `acp-exception-feedback.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_run_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_artifact_list/form` | toolbar, table, form-section, form-buttons | title/type, run/task, file path, MIME/content, tags, metadata | create, edit, delete | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_artifact_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_schedule_list/form` | toolbar, table, form-section, form-buttons | title, status/type, cron/interval/timezone, max runs, mission/event/template | create, edit, pause, activate, delete | `acp-lifecycle-deep.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_schedule_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `approval_policy_list/form` | toolbar, table, form-section, form-buttons | name/status, auto approval, timeout/action, trigger/approver rules, description | create, edit, delete | `acp-approval-closeloop.spec.ts`, `acp-form-crud.spec.ts` | PASS |
| `approval_policy_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_approval_list/form` | table, form-section, form-buttons | title/status/type, run/task, approver/time, expiry/action/policy, request/rejection | approve, reject | `acp-approval-closeloop.spec.ts` | PASS |
| `agent_approval_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_memory_list/form` | toolbar, table, form-section, form-buttons | title/type/agent, category/importance, source/validity, content/metadata | create, edit, delete | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_memory_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_observation_list/form` | table, form-section, form-buttons | title/type/severity, source type/id, agent, detail | read and edit form journey | `acp-exception-feedback.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_observation_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_tool_list/form` | toolbar, table, form-section, form-buttons | code/name/type, risk/status/source, approval, API method/path, schemas, description | create, edit, delete | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `agent_tool_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_skill_list/form` | toolbar, table, form-section, form-buttons | code/name/level/category/status, icon/version/builtin, tools/prompt/input schema | create, edit, delete | `digital-employee-skill-review.spec.ts`, `acp-form-crud.spec.ts` | PASS |
| `agent_skill_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `agent_action_list` | table | code/status/type/domain, execution time, intent, risk, target model | read-only audit journey | `acp-dashboard-views.spec.ts`, `acp-smoke.spec.ts` | PASS |
| `agent_action_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `object_alias_list/form` | toolbar, table, form-section, form-buttons | model, alias, language, priority | create, edit, save, cancel | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `object_alias_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `semantic_term_list/form` | toolbar, table, form-section, form-buttons | term, model, type, language, priority, description, resolution | create, edit, save, cancel | `acp-form-crud.spec.ts`, `acp-model-lifecycle.spec.ts` | PASS |
| `semantic_term_detail` | N/A | N/A | no detail action; generated empty shell is disabled | `extension.skipDetailPage=true` | N/A |
| `acp_mission_control_list` | stat-card, chart, table | mission aggregates and status distribution | dashboard filters and mission navigation | `acp-dashboard-views.spec.ts` | PASS |
| `mcp_server_list` | toolbar, table | name, endpoint, transport, auth, status, tool count, sync time | create, detail, edit, deactivate | 2026-07-26 MCP endgame screenshots 01, 04, 07, 10 + live DB assertions | PASS |
| `mcp_server_form` | form-section, form-buttons | name, endpoint/executable, transport, auth type, stdio args/env, secret/header | create/update, required validation, executable deny, cancel | 2026-07-26 MCP endgame screenshots 02, 03, 06, 08 + protocol/DB tests | PASS |
| `mcp_server_detail` | form-section | safe connection fields, status, tool count, sync time/error | safe detail projection; no secret or raw pid | 2026-07-26 MCP endgame screenshots 05, 09 + response secret scan | PASS |
| `ai_settings_hub` | card-grid | settings cards and descriptions | menu entry and MCP server navigation | `ai-settings-dsl-page.spec.ts` + 2026-07-26 live sidebar journey | PASS |
| `ai_colleagues` | custom | colleague cards and states | open, suspend, operation scope | `ai-colleagues-dsl-page.spec.ts`, `ai-colleagues.spec.ts` | PASS |
| `ai_colleague_new` | custom | identity, model, tools, enrollment inputs | create and enroll | `ai-colleague-new-dsl-page.spec.ts`, `ai-colleague-create-enroll.spec.ts` | PASS |
| `ai_colleague_detail` | custom | identity, capabilities, state, activity | inspect and suspend | `ai-colleague-detail-dsl-page.spec.ts`, `ai-colleague-suspend.spec.ts` | PASS |
| `ai_colleague_chat` | custom | conversation input and response | send a real turn and render the response | `ai-colleague-chat-dsl-page.spec.ts`, `ai-colleague-can-talk.spec.ts` | PASS |

## Command and status coverage

| Domain | Commands / status transitions | Positive path | Negative / edge path | Evidence | Status |
| --- | --- | --- | --- | --- | --- |
| Agent definition | create, update, delete; draft/active/suspended/archived | full-field create and edit | required validation and invalid transition | ACP CRUD/lifecycle suites | PASS |
| Mission | create, update, pause, resume, complete, archive, delete | each legal transition | hidden/blocked illegal transition | ACP lifecycle suite | PASS |
| Task | create, update, dispatch, start, complete, block, cancel, delete | each legal transition | terminal-state action unavailable | ACP lifecycle suite | PASS |
| Run | cancel | running to cancelled | terminal run cannot cancel | ACP exception/lifecycle suites | PASS |
| Schedule | create, update, pause, activate, delete | active/inactive lifecycle | invalid repeat transition | ACP lifecycle suite | PASS |
| Approval | approve, reject | pending decision closes loop | expired/already-decided request rejected | ACP approval close-loop suite | PASS |
| MCP server | create, update, deactivate; active/inactive | list/create/detail/edit/deactivate | required, executable deny, redacted error, soft-delete ordering | 2026-07-26 MCP endgame browser screenshots + protocol/real-DB tests | PASS |

## State presentation requirements

Every browser path must assert loading, empty, data, error, disabled, and unauthorized
states where applicable. User-facing labels must be localized; raw action codes, record
PIDs, encrypted values, tokens, and stdio environment values must never be rendered.
