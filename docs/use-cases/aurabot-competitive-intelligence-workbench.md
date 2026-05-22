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

- Mission: future workflow object. The current first iteration routes users through `/p/agent_task` because the imported Mission DSL page still needs permission/data-source hardening before becoming a primary entry.
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
- Do not make `/p/mission` a primary action until its DSL datasource permission issue is fixed.

Deferred:

- Real scenario template creation wizard.
- Mission page permission/data-source fix, then reconnect mission creation as the first step.
- One-click creation of mission, tasks, schedule, policy, and report artifact.
- Seed data for a full competitive intelligence demo.
- E2E coverage for create/run/approve/report once those commands exist.

## Validation

- Unit test the dashboard link map so `/p/...` paths stay aligned with imported DSL page schemas.
- Browser-check `/aurabot/dashboard` in the bugfix environment.
- Verify key entry buttons navigate to DSL and static pages without schema errors.
- Keep the landing page usable when there are no runs or schedules.
