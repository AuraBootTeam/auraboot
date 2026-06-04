# Architecture

This document is the entry point for understanding how AuraBoot is structured. It is intentionally short — each section links to the canonical reference in [`docs/core-concepts/`](docs/core-concepts/) or [the documentation site](https://docs.auraboot.com).

Read [POSITIONING](docs/getting-started/positioning.md) first if you have not — the rest of this document assumes you understand what AuraBoot is optimized for.

---

## System at a glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                          Clients & Agents                           │
│   Browser UI  │  Mobile  │  External API  │  Automation  │  AI Agent │
└────────────────────────────┬────────────────────────────────────────┘
                             │   (every write goes through the same contract)
┌────────────────────────────▼────────────────────────────────────────┐
│                         Command Pipeline                            │
│   resolve → authn → authz → entitlement → validate → preconditions  │
│   → state guard → execute → audit → events → side effects           │
└────────────────────────────┬────────────────────────────────────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
┌───────▼──────┐    ┌────────▼────────┐   ┌───────▼──────────┐
│   Metadata   │    │     Runtime     │   │  Process Engine  │
│  Model/Field │    │  DSL renderer   │   │   BPMN 2.0 +     │
│ Page/Command │    │ Permission eval │   │   Command tasks  │
│ Process/Perm │    │ Audit / Events  │   │                  │
└───────┬──────┘    └────────┬────────┘   └───────┬──────────┘
        │                    │                    │
        └────────────────────┼────────────────────┘
                             │
                  ┌──────────▼──────────┐
                  │   Plugin Substrate  │
                  │  declared manifest, │
                  │  isolated resources │
                  └──────────┬──────────┘
                             │
                  ┌──────────▼──────────┐
                  │ PostgreSQL │ Redis  │
                  │  + pgvector         │
                  └─────────────────────┘
```

The diagram shows the contract, not the deployment topology. Everything above the database flows through the same pipeline. Everything that adds business behavior — a new module, an industry vertical, an integration — is a plugin that declares what it contributes.

---

## The six concepts

AuraBoot has exactly six first-class concepts. If a feature does not fit one of them, it is a feature on top of one of them.

### Model
Models are how AuraBoot understands your data. A model declaration produces the database table, REST endpoints, list/detail/form pages, and the type information that powers everything downstream.

→ [`docs/core-concepts/models-and-fields.md`](docs/core-concepts/models-and-fields.md)

### Page
Pages are DSL documents that compose blocks (list, form, detail, custom). There is no path where business CRUD is written as ad-hoc TSX — the renderer is contract-driven, and unrecognized block types fail fast rather than silently fall back.

→ [`docs/core-concepts/pages-and-layouts.md`](docs/core-concepts/pages-and-layouts.md)

### Command
A command is the only sanctioned write path. Every command — whether triggered by a button, an automation rule, a workflow task, or an AI agent — flows through the same multi-stage pipeline: resolve, authenticate, authorize, validate against entitlement and SoD, check preconditions, guard state transitions, execute, audit, dispatch events, and trigger side effects.

This contract is what makes the rest of the platform possible. Without it, audit and AI-safe execution would be aspirations, not guarantees.

→ [`docs/core-concepts/commands.md`](docs/core-concepts/commands.md)

### Permission
Permission in AuraBoot is layered, not stacked. Five evaluation layers — role-based (RBAC), relation-based (ReBAC), organizational data scope, attribute-based (ABAC), and field-level visibility — are applied in a defined order against a tenant-scoped principal. This is what lets enterprises grant precise authority *"see all opportunities owned by my org, edit only those above $50k that I created, hide the discount field from non-finance roles"* without writing code per scenario.

→ [`docs/core-concepts/permissions.md`](docs/core-concepts/permissions.md)

### Process
Processes are long-running orchestration. AuraBoot uses BPMN 2.0 (via SmartEngine) and lets each task resolve to a Command. The flow does not escape the governance contract just because it crosses a wait-state or a human approval.

→ [Process designer guide](docs/guides/bpm-workflows.md)

### Plugin
A plugin is the unit of delivery. It declares — through a typed manifest — the models, fields, commands, permissions, menus, pages, processes, and named queries it contributes. The plugin substrate isolates resources, resolves dependencies, and supports three plugin types: configuration-only, hybrid (configuration + Java extensions), and solution packages (industry verticals that bundle other plugins).

→ [`docs/core-concepts/plugin-manifest.md`](docs/core-concepts/plugin-manifest.md)

---

## Why this shape

Three design pressures push AuraBoot toward this architecture:

**The long-lived enterprise application doesn't fit "write once, run a year."** Business operations accumulate exceptions: approval matrices, dual-signature rules, segregation-of-duties, country-specific compliance, field-level confidentiality. Without a single contract, every module ends up re-implementing these concerns inconsistently. The command pipeline exists to make that consistent by construction.

**AI agents need a safe call surface, not a free-text shell.** An agent calling random APIs has the same blast radius as an unauthenticated user; a permissions-checking, audit-recording, idempotency-aware command is something an agent can invoke at scale without becoming a liability. AuraBoot's command metadata (`agentHint`, `idempotent`, `reversible`, `riskLevel`) makes this surface explicit, not implicit.

**Industry verticals must be delivered as products, not as long-lived consulting engagements.** Customization-as-patches breaks every upgrade. Customization-as-plugins, with declared dependencies and isolated resources, survives upgrades. The plugin substrate is the unit that lets AuraBoot ship industry packages (PCBA manufacturing, contract cost management, asset management) without forking the platform.

---

## What this architecture is not

For the things AuraBoot deliberately does not optimize for, see [POSITIONING](docs/getting-started/positioning.md):

- Not a drop-in replacement for a country-specific accounting suite
- Not a quickest-time-to-screen data-app builder
- Not a fit for tools where audit and permissions are not requirements

This is not a hedge — it is the consequence of optimizing for governance, lifecycle, and AI-safety. Choose the right tool for the job.

---

## Enterprise extensions

Several capabilities exist on top of this open-core architecture and are offered through the commercial distribution:

- **Marketplace and License/Entitlement** — productized distribution of plugin packages with per-tenant entitlement enforcement (the command pipeline has a dedicated stage for this; OSS leaves it permissive)
- **Agent Control Plane** — cross-tenant agent orchestration, audit replay, and risk-graded approval gates
- **Observability Pro** — distributed tracing wired into the command pipeline, end-to-end latency budgets per stage
- **Advanced governance** — extended ABAC policies, field-level masking strategies, cross-organization data-scope delegation

These are extensions of the same contracts described above, not parallel runtimes. The open-source platform remains the truth source for how commands, permissions, plugins, and processes are defined.

---

## Where to go next

- New to the platform → [Getting started](docs/getting-started/)
- Adding a model → [`docs/core-concepts/models-and-fields.md`](docs/core-concepts/models-and-fields.md)
- Designing a page → [`docs/core-concepts/pages-and-layouts.md`](docs/core-concepts/pages-and-layouts.md)
- Writing a command → [`docs/core-concepts/commands.md`](docs/core-concepts/commands.md)
- Building a plugin → [`docs/plugin-development/`](docs/plugin-development/)
- Calling AuraBoot from an AI agent → [`docs/core-concepts/agent-readiness.md`](docs/core-concepts/agent-readiness.md)
- Full doc site → [docs.auraboot.com](https://docs.auraboot.com)
