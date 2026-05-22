# AuraBot Competitive Intelligence Workbench

## Product Goal

AuraBot Dashboard should behave like an enterprise agent workbench, not a flat navigation directory. The first packaged scenario is competitive intelligence research: a team asks AuraBot to monitor public competitor signals, enrich source data, analyze changes, request approval for risky steps, and deliver a weekly report with traceable evidence.

## Target Users

- Product strategy lead: wants a weekly competitor brief with product, pricing, customer, and hiring signals.
- Sales enablement lead: wants battlecard updates and account-facing talking points.
- Revenue operations owner: wants repeatable data collection, cost control, and auditability.
- Admin: manages agents, schedules, policies, and model/provider governance.

## Scenario

The team creates a mission named "Weekly competitor scan". AuraBot coordinates several agents:

- Research Agent collects public website, pricing, release-note, job-posting, and public social signals.
- Data Analyst normalizes extracted findings into a comparable table.
- Sales Agent turns findings into GTM implications and battlecard notes.
- AuraBot supervises the workflow, routes approvals, remembers research preferences, and records traces.

The workflow can run on demand or on a weekly schedule. High-risk or externally visible actions pause for approval. The final artifact includes a concise report, source links, change summary, cost, model usage, and trace links.

## Reference Products

- [Zapier Agents](https://zapier.com/agents), accessed 2026-05-22: useful pattern is packaging agents as delegated teammates with templates, activity monitoring, web work, and scheduled execution.
- [Notion AI](https://www.notion.com/product/ai), accessed 2026-05-22: useful pattern is connecting agents, enterprise search, reports, permissions, usage analytics, and repeatable team workflows.
- [Clay](https://www.clay.com/), accessed 2026-05-22: useful pattern is turning public research, enrichment, signals, and workflow outputs into GTM action.

Adopted patterns:

- Scenario templates are the primary entry, not raw resource lists.
- Work is shown as a chain: mission, tasks, runs, traces, approvals, artifacts.
- Governance is visible but secondary: policy, model, schedule, memory, and permissions should be grouped.
- Costs, pending approvals, and run failures must remain visible on the landing page.

Rejected patterns:

- A generic chatbot-only entry is insufficient because enterprise users need repeatability, audit, and outputs.
- A pure CRUD menu is insufficient because it hides causality between tasks, runs, traces, approvals, and artifacts.
- A marketing-style hero is inappropriate for a daily operations workbench.

## Product Flow

1. User opens `/aurabot/dashboard`.
2. The top workbench shows operational KPIs: active missions, active tasks, running runs, pending approvals, active agents, and monthly cost.
3. The primary scenario panel offers "Competitive intelligence research" with scope, owners, and expected output.
4. User starts from a mission or task list, then tracks execution through runs and traces.
5. When a run needs human judgment, the pending approval and interrupt surfaces become the next action.
6. Artifacts become the delivery surface for reports, tables, summaries, and battlecards.
7. Memory and soul profile surfaces capture user or tenant preferences, such as priority competitors, preferred output format, and risk tolerance.
8. Schedules and policies keep the scenario repeatable and governed.

## Information Architecture

The dashboard should group links by job:

- Execute: missions, tasks, runs, artifacts.
- Govern: approvals, interrupts, policies, traces.
- Configure: agents, schedules, memory, profile, providers, prompts.

The old flat quick link grid caused duplicated entry points:

- "All tasks", "Tasks", and task KPI all pointed to the same object.
- "Runs" and "Traces" were separate cards even though they are execution observability.
- "Approvals", "Interrupts", and "Approval policies" were split across unrelated areas.
- Memory, memory promotions, my profile, and soul profiles were all top-level cards.
- Agent definitions, schedules, and policies looked like user tasks rather than configuration.

## AuraBoot Mapping

- Mission: `/p/mission` and `/p/mission/new`
- Task list: `/p/agent_task`
- Approval queue: `/p/agent_approval`
- Agent definition: `/p/agent_definition`
- Schedule: `/p/agent_schedule`
- Artifact: `/p/agent_artifact`
- Approval policy: `/p/approval_policy`
- Runs: `/aurabot/runs`
- Traces: `/aurabot/traces`
- Interrupts: `/aurabot/interrupts`
- Skill drafts: `/aurabot/learning-drafts`
- Memory: `/p/agent_memory`
- Memory promotions: `/aurabot/memory-promotions`
- My profile: `/aurabot/my-profile`
- Soul profiles: `/aurabot/soul-profiles`

## Implementation Scope

First iteration:

- Keep the existing KPI, agent status, recent activity, schedules, analytics, and event tabs.
- Replace the flat quick navigation section with a scenario workbench, workflow chain, and grouped command hub.
- Keep all links read-only navigation. Do not create new backend commands in this iteration.
- Preserve existing data loading and empty states.
- Use the competitive intelligence scenario as the default product framing, while keeping links generic enough for other enterprise agent scenarios.
- Mission owner uses the platform `sys_user` reference picker, not a dynamic `ns_user` model.

Deferred:

- Real scenario template creation wizard.
- One-click creation of mission, tasks, schedule, policy, and report artifact.
- Seed data for a full competitive intelligence demo outside the E2E-retained evidence records.
- End-to-end create/run/approve/report automation for a real agent execution engine.

## Validation

Validation is split into two layers:

1. Workbench wiring: prove the product entry points, DSL pages, and static pages are reachable from the scenario dashboard.
2. Agent scenario: prove an Agent run can execute the scenario path, invoke a real tool, persist a report artifact, and pass a deterministic quality rubric.
3. Scenario orchestration: prove the full business chain can connect mission, tasks, multiple agents, approval policy, schedule, run, artifact, and memory.

- Unit test the dashboard link map so `/p/...` paths stay aligned with imported DSL page schemas.
- Browser-check `/aurabot/dashboard` in the bugfix environment.
- Verify key entry buttons navigate to DSL and static pages without schema errors.
- Keep the landing page usable when there are no runs or schedules.
- Playwright E2E: `web-admin/tests/e2e/aurabot/competitive-intelligence-workbench.spec.ts`.
  - Creates retained evidence data through real command APIs: mission, agent definition, agent task, agent run, artifact, schedule, approval policy, and memory.
  - Drives the product chain from `/aurabot/dashboard` through the visible Dashboard entries instead of direct-linking to target pages.
  - Verifies DSL and static destinations: `/p/mission/new`, `/p/mission`, `/p/agent_task`, `/aurabot/runs`, `/p/agent_approval`, `/p/agent_artifact`, `/p/agent_definition`, `/p/agent_schedule`, `/p/approval_policy`, `/p/agent_memory`, `/aurabot/traces`, `/aurabot/interrupts`, and `/aurabot/my-profile`.
  - Attaches retained seed record ids, route evidence, dashboard screenshot, and Playwright trace for auditability.
  - Targeted command used on 2026-05-22:
    `CI=1 PW_SKIP_WEBSERVER=1 PLAYWRIGHT_BASE_URL=http://localhost:5260 BACKEND_URL=http://localhost:6530 BE_PORT=6530 BFF_PORT=3587 PW_ADMIN_STORAGE_STATE=/Users/ghj/work/auraboot/.aura/envs/bugfix-daily/auth/admin.json PW_ARTIFACT_DIR=./test-results/ciwb-artifacts PW_REPORT_DIR=./test-results/ciwb-html-report PW_RESULTS_JSON=./test-results/ciwb-results.json PW_WORKERS=1 npx playwright test tests/e2e/aurabot/competitive-intelligence-workbench.spec.ts --project=chromium --trace on --no-deps`.
- Playwright Agent scenario E2E: `web-admin/tests/e2e/aurabot/competitive-intelligence-agent-scenario.spec.ts`.
  - Triggers `/api/ai/aurabot/chat/stream` with `explicitDurableRequest=true`, so the default AuraBot path enters the durable Agent runtime.
  - Uses deterministic stub LLM only for the model response; the runtime, tool policy, confirmation, `ToolLoopService`, DSL command execution, database write, and UI verification remain real.
  - Requires `confirm_required`, then confirms through `/api/ai/aurabot/execute` and expects `tool_start`, `result_contract`, `tool_result`, and `done` events.
  - Persists a real `agent_artifact` report through `cmd_acp_create_agent_artifact`, then verifies it from the Dashboard artifact entry in the browser.
  - Scores the report with a deterministic quality rubric: minimum length, required sections, source links, competitor specificity, GTM actionability, and no stub placeholder in the artifact body.
  - Attaches runtime SSE events and `ciwb-agent-artifact-quality.json` as test evidence.
- Playwright orchestration E2E: `web-admin/tests/e2e/aurabot/competitive-intelligence-orchestration.spec.ts`.
  - Creates "竞对调研：A/B/C 公司本周变化" through the AuraBot confirmed tool path.
  - Creates Research Agent, Data Analyst, Sales Agent, and AuraBot supervisor definitions.
  - Creates five sub-tasks: official-site collection, pricing collection, feature extraction/comparison table, sales interpretation, and final weekly report.
  - Creates approval policy rules for external website access, budget overrun, email sending, and high-risk commands.
  - Creates memory for enterprise preferences: pricing, features, customer stories, keywords, source links, and sales actions.
  - Creates a Monday weekly schedule, triggers it through `/api/agent/schedule/{schedulePid}/trigger`, and verifies the resulting Agent run.
  - Persists the final report artifact linked to the scheduled run and report task, then verifies the schedule and artifact from Dashboard entries.
  - Attaches `ciwb-orchestration-evidence.json` with record ids, run id, task ids, tool events, and report quality score.
