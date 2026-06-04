---
type: system-reference
status: active
---

# System Overview

## What this document covers

This page is the architectural map of AuraBoot. It answers four questions a new architect or platform engineer needs answered before touching code: how the runtime is layered, how plugin contributions become live capabilities through the **Profile registration system**, how the **control plane** is separated from the **business plane**, and how **multi-tenant isolation** is enforced end-to-end.

If you have not yet read [Positioning](/docs/en/positioning), do so first. This page assumes you understand what AuraBoot is optimized for. For the execution contract of a single write operation, continue to the [Command Pipeline](/docs/en/core-concepts/command-pipeline) page after finishing this one.

The diagrams here describe the **logical contract**, not the deployment topology. A single JVM in development and a multi-node cluster in production run the same layers; only the substrate underneath the storage line changes.


## System layers

Every request — whether a browser click, a mobile tap, an external API call, an automation rule firing, or an AI agent invocation — flows through the same vertical stack:

```
┌────────────────────────────────────────────────────────────────────────┐
│                          Clients & Agents                              │
│   Browser UI   │   Mobile   │   External API   │  Automation  │  Agent │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │  (one contract for every write)
┌────────────────────────────────▼───────────────────────────────────────┐
│                          Command Pipeline                              │
│  resolve  →  authn  →  authz  →  entitlement  →  validate              │
│      →  preconditions  →  state-guard  →  execute  →  audit            │
│      →  events  →  side-effects                                        │
└────────────────────────────────┬───────────────────────────────────────┘
                                 │
       ┌─────────────────────────┼─────────────────────────┐
       │                         │                         │
┌──────▼──────────┐    ┌─────────▼─────────┐    ┌──────────▼──────────┐
│ Metadata        │    │  Runtime          │    │  Process Engine     │
│ Registry        │    │  Services         │    │  BPMN 2.0 +         │
│  Models/Fields  │    │  DSL renderer     │    │  Command tasks      │
│  Pages/Commands │    │  Permission eval  │    │  Long-running       │
│  Perms/Menus    │    │  Audit / Events   │    │  orchestration      │
│  Processes      │    │  Automation       │    │                     │
└──────┬──────────┘    └─────────┬─────────┘    └──────────┬──────────┘
       │                         │                         │
       └─────────────────────────┼─────────────────────────┘
                                 │
                  ┌──────────────▼──────────────┐
                  │     Plugin Substrate        │
                  │  PF4J hosting, manifests,   │
                  │  isolated classloaders,     │
                  │  dependency ordering        │
                  └──────────────┬──────────────┘
                                 │
                  ┌──────────────▼──────────────┐
                  │  PostgreSQL (+ pgvector)    │
                  │  Redis (cache / lock / bus) │
                  └─────────────────────────────┘
```

A short tour of each layer:

**Clients & Agents** — Browser, mobile, external API, automation, and AI agents are all peers. None of them gets a private write path. The fact that an agent uses the same pipeline as a button click is what makes AI execution governable.

**Command Pipeline** — The single write contract. Ten ordered stages turn an intent (command code + payload + principal) into a durable, audited state change. Stages are described in [Command Pipeline](/docs/en/core-concepts/command-pipeline).

**Metadata Registry** — The runtime store of resolved definitions: Model, Field, Command, Page, Permission, Menu, Process. The pipeline reads from it on every request. Plugins write into it at load time through the Profile registration system.

**Runtime Services** — The horizontal capabilities the pipeline composes: DSL page rendering, permission evaluation, audit writing, event dispatch, automation scheduling, and BPM execution. These are not plugins; they are part of the platform contract.

**Process Engine** — Long-running orchestration based on BPMN 2.0. Each service task in a process resolves to a command, so a workflow step is never an escape hatch from authorization or audit.

**Plugin Substrate** — PF4J-based hosting that loads jar/configuration plugins with isolated classloaders, validates their manifests, resolves dependencies, and hands their contributions to the Metadata Registry.

**Storage** — PostgreSQL is the system of record (transactional rows, JSONB metadata, pgvector embeddings). Redis is the operational tier (caches, distributed locks, ephemeral queues). The choice is not a placeholder — see [Storage architecture](#storage-architecture).


## Control plane vs. business plane

AuraBoot draws a deliberate line between two responsibilities that other low-code platforms tend to mix:

```
                ┌──────────────────────────────────────┐
                │            Control plane             │
                │                                      │
                │  Metadata Registry                   │
                │  Plugin lifecycle (install/upgrade)  │
                │  Profile registration                │
                │  Identity / Tenant / Membership      │
                │  Permission definition (not eval)    │
                │  Audit writer + audit log query      │
                │  Observability + health              │
                │  Entitlement registration            │
                └─────────────────┬────────────────────┘
                                  │  reads & enforces
                ┌─────────────────▼────────────────────┐
                │            Business plane            │
                │                                      │
                │  Command execution                   │
                │  Page rendering (list/form/detail)   │
                │  Process instances                   │
                │  Automation rule firing              │
                │  Reports, search, exports            │
                └──────────────────────────────────────┘
```

The **control plane** governs what the system can do. The **business plane** does it.

The control plane owns the **definitions**: what models exist, what commands are callable, what permissions are recognized, which plugins are installed, what entitlements a tenant holds, what audit shape is captured. It is the only layer allowed to mutate the registry.

The business plane owns the **operations**: dispatching commands against models, rendering pages, advancing processes, materializing reports. It is read-only against the registry — it never edits its own definitions mid-flight.

Why this split matters:

- **Governance** — The audit log is itself a control-plane artifact. A business operation cannot suppress its own audit record because audit-writing is part of the pipeline contract enforced by the control plane.
- **AI safety** — An agent invoking a business-plane command cannot redefine the command's risk level, idempotency, or permission requirements; those live in the control plane and the agent has no write surface there.
- **Upgrade safety** — Plugin upgrades touch the control plane (re-register models, refresh page DSL) without halting business-plane traffic. Long-running process instances continue against the same registry version that started them.
- **Multi-tenancy** — Tenants share the business plane runtime but have independent slices of the control plane (entitlements, role bindings, configuration overrides). Cross-tenant operations must cross both planes and are therefore always explicit.


## The Profile registration system

A **Profile** is the resolved capability set that a particular rendering context (e.g. admin console, storefront, partner portal) exposes. A profile bundles the block types it understands, the page kinds it can render, the renderer components for each block and kind, the component manifest, and the layout presets.

Plugin contributions are not directly callable. They are **declared** in a manifest and then **merged** into one or more profiles during plugin load. Until merge happens, the contribution exists in a staging area; once merged, it is visible to the runtime.

```
Plugin A manifest                Plugin B manifest
─────────────────                ─────────────────
models: [order, ...]             models: [contract, ...]
commands: [order.submit, ...]    commands: [contract.sign, ...]
pages: [admin/order/list, ...]   pages: [admin/contract/list, ...]
permissions: [...]               permissions: [...]
blockTypes: [order-card]         blockTypes: [signature-pad]
                  │                       │
                  └───────────┬───────────┘
                              ▼
                ┌──────────────────────────┐
                │   Plugin substrate load  │
                │   - dependency order     │
                │   - manifest validation  │
                │   - conflict detection   │
                └─────────────┬────────────┘
                              ▼
                ┌──────────────────────────┐
                │   Profile resolver        │
                │   merge into:             │
                │     admin profile         │
                │     storefront profile    │
                │     report profile        │
                └─────────────┬────────────┘
                              ▼
                ┌──────────────────────────┐
                │   Metadata Registry       │
                │   (live, queryable)       │
                └──────────────────────────┘
```

A few properties of the registration system worth remembering:

- **Profiles are named, registered, and resolvable.** A page DSL document declares which profile it targets; the renderer asks the `ProfileRegistry` for the right block components. The default profile is `admin`; new profiles are registered alongside it.
- **Contributions are typed.** Every entry — model, command, page, permission, block type — has a schema. The manifest validator rejects malformed plugins at load time, not at first use.
- **Conflicts are explicit failures.** Two plugins contributing the same model code or permission code without an explicit override declaration cause the load to fail. There is no last-writer-wins silent override.
- **Hot reload is bounded.** Adding or upgrading a plugin re-resolves its profiles and refreshes the relevant registry slices. In-flight commands continue against the registry version that started them; new commands pick up the new registry. Permanent removal of a contribution that is referenced by data is rejected.
- **Profile boundaries enforce delivery shape.** A storefront-only block type does not leak into the admin profile. A report-only kind does not collide with an admin kind. This is what lets the same DSL schema be reused across rendering contexts without coupling them.

The Profile registration system is the mechanism by which AuraBoot stays **plugin-delivered** rather than **monolith-delivered**: the platform never special-cases an industry vertical, because the vertical is just one more plugin contributing to a profile.


## Metadata Registry

The Metadata Registry is the in-memory + database-backed projection of every resolved definition that the runtime needs to answer a request:

| Entry kind   | Read on                                        |
| ------------ | ---------------------------------------------- |
| Model        | every dynamic CRUD call, every page render     |
| Field        | validation, page rendering, permission check   |
| Command      | every write, including pipeline dispatch       |
| Page         | every DSL page request                         |
| Permission   | every authorization check                      |
| Menu         | navigation rendering, capability listing       |
| Process      | workflow instantiation, task resumption        |
| BindingRule  | command-to-handler resolution                  |

Lookup paths go through typed accessors (`MetaModelService`, `MetaCommandService`, etc.) rather than ad-hoc SQL. Two operational properties matter:

- **Read path is hot, write path is cold.** The runtime reads from the registry on essentially every request. Writes happen only on plugin install / upgrade / removal, configuration apply, and explicit administrative actions.
- **Cache invalidation is keyed on plugin load events.** Registry caches (model definitions, page DSL, permission graphs) invalidate together when a plugin reloads. There is no field-by-field staleness window because the load event is the boundary.

Anything outside the registry — runtime state, business records, audit log entries — is **not** registry data. The registry answers "what can happen"; the database answers "what did happen".


## Multi-tenant isolation

Tenancy is a first-class concept, not a row-level afterthought. Every request runs under a `MetaContext` that carries `(tenantId, userId, principalRole, locale)` from authentication through to storage.

```
HTTP request
   │
   ▼
Auth filter ─────► resolves tenant_member principal
   │                  (user × tenant × role)
   ▼
MetaContext.setContext(tenantId, userId, role, locale)
   │
   ▼
Command pipeline (every stage reads MetaContext)
   │
   ▼
Repository layer
   - row-level tenant filter applied automatically
   - cross-tenant query requires explicit elevation
   │
   ▼
Storage
```

What isolation actually means:

- **Data isolation** — Business tables carry a `tenant_id` column; the repository layer rejects queries that do not constrain it. Cross-tenant reads must come from a service annotated for cross-tenant operation, and they emit an audit event.
- **Configuration isolation** — Permission bindings, menu visibility, automation rules, and plugin entitlements are per-tenant. A tenant disabling a plugin disables only its slice of the registry view, not the global plugin load.
- **Principal model** — Authorization is evaluated against the **tenant member**, not the bare user. The same user in two tenants holds two independent role sets; one tenant's role escalation does not leak to the other.
- **Process isolation** — A BPMN instance belongs to a tenant. Tasks, history, and timers are tenant-scoped. A process definition shared across tenants is rendered identically but instantiated independently.
- **Cross-tenant operations are explicit and audited** — Platform-admin operations (bulk migration, support actions, system-level reports) declare their cross-tenant intent and record every accessed tenant in the audit log.

The reason for putting tenancy at the principal level rather than the row level is the same reason commands are the only write path: making the safety property a property of the architecture, not of individual handlers, is what keeps the system honest as it scales to dozens of plugins and hundreds of commands.


## Runtime services

The platform layer hosts a small number of horizontal services that the command pipeline composes. Each is part of the platform contract, not a plugin.

**DSL renderer** — Resolves a page DSL document against the active profile, calls the matching block renderers, and produces the HTML/React tree. It is contract-driven: an unrecognized block type fails fast rather than silently rendering an empty placeholder. Page DSL is data, not code.

**Permission evaluator** — Applies the five-layer model (RBAC, ReBAC, organizational data scope, ABAC, field-level visibility) in a defined order against the tenant member. Evaluation is purely a function of the principal, the resource, and the operation; it has no side effects and can be cached.

**Audit writer** — Receives an audit event from the pipeline's `audit` stage and persists it. The audit log is **a queryable artifact, not a debug log** — it is shaped so that compliance and AI replay can both consume it.

**Event dispatcher** — Receives domain events emitted by the pipeline's `events` stage and routes them to subscribers: automation rules, notification channels, change-log writers, and the Inbox feed.

**Automation scheduler** — Owns cron triggers, event-triggered rule execution, retry/backoff for failed automations, and dead-letter queue handling. Every automation eventually issues a command — there is no separate write path for "system" actions.

**BPM engine** — Embedded BPMN 2.0 runtime (SmartEngine). Service tasks resolve to commands; user tasks resolve to inbox items; gateways and timers run inside the engine. The engine is a participant in the pipeline, not a parallel one.


## Plugin substrate

Plugins are the unit of delivery. The substrate is the layer that loads them safely.

- **PF4J-based hosting** — Each plugin is a jar (Java extensions) and/or a directory of configuration (models, pages, commands, permissions). Java plugins receive an isolated classloader so their dependencies do not collide with the host or with peer plugins.
- **Declared manifests** — A `plugin.yaml` / `plugin.json` declares the plugin id, version, dependencies on other plugins, capability declarations, and the resource directories that contribute models/pages/commands/permissions.
- **Dependency-ordered loading** — Plugins are sorted topologically. A plugin that declares a dependency on `platform.core` loads after it; a plugin that depends on `crm` loads after `crm`. Cyclic dependencies are rejected.
- **Capability declarations** — A plugin states which platform capabilities it consumes (e.g. needs the BPM engine, needs pgvector, needs object storage). The host refuses to load a plugin whose declared capabilities are not available, rather than letting it fail at first call.
- **Configuration-only vs. hybrid vs. solution package** — Three plugin shapes are supported: pure configuration (no Java), hybrid (configuration + Java extensions), and solution package (an umbrella that bundles other plugins with a vertical theme). All three share the same manifest format.

For the full manifest reference, see [Plugin manifest](/docs/en/core-concepts/plugin-manifest).


## Storage architecture

AuraBoot is **not** a database-agnostic platform, and this is a deliberate choice.

- **PostgreSQL is the system of record.** Transactional rows, JSONB metadata (for flexible DSL documents and capability declarations), and `pgvector` embeddings (for retrieval and semantic search) all live in one engine. Using one engine for structured + semi-structured + vector eliminates a class of consistency problems that hybrid stacks accumulate.
- **Redis is the operational tier.** Caches for hot registry lookups, distributed locks for command idempotency, transient queues for automation, and rate-limit counters. Redis is not the system of record; data there is recoverable from PostgreSQL.
- **Object storage is pluggable.** Attachments, exports, and large binaries live in S3-compatible storage. The substrate ships a local-filesystem adapter for development.

Why not abstract over multiple databases? Because the contract guarantees AuraBoot offers — `pgvector`-backed AI retrieval co-located with transactional rows, JSONB for evolving manifests, row-level tenant isolation enforced via repository scoping, partitioning for the audit log — are tightly coupled to PostgreSQL's feature set. A database-agnostic layer would either dilute those guarantees or replicate PostgreSQL features in application code. Both are worse than committing to a high-quality engine.

For development, deployment, and operational guides, see the [Operations](/docs/en/operations) section.


## Observability hooks

Observability is built into the pipeline, not bolted on afterward.

- **Structured logging** — Every pipeline stage emits a log entry tagged with `command_id`, `tenant_id`, `principal`, `stage`, `latency_ms`, and `result`. Logs are JSON; no log-message string parsing required.
- **OpenTelemetry tracing** — Each command run is a trace; each pipeline stage is a span. Side effects (events, automations, downstream commands) inherit the trace context. Distributed deployments correlate end-to-end without manual stitching.
- **Command-stage metrics** — Per-stage latency histograms (`authn`, `authz`, `validate`, `execute`, `audit`, ...) make it trivial to find which stage is dominating a slow command. The same metrics power [budget alerts](/docs/en/operations).
- **The audit log is observability too.** Unlike a debug log, the audit log is a queryable, retention-managed artifact intended to be read by humans, compliance tools, and AI replay. It is not optional and not configurable per command.


## What is intentionally NOT in the OSS distribution

:::note[Enterprise extensions]
The open-source platform is the truth source for how commands, permissions, plugins, tenants, profiles, and processes are defined. Several capabilities run on top of the same contracts but ship only with the commercial distribution:

- **Marketplace + License/Entitlement runtime** — Productized plugin distribution with per-tenant entitlement enforcement at the pipeline's `entitlement` stage. The OSS pipeline includes the stage but leaves it permissive.
- **Agent Control Plane** — Cross-tenant agent orchestration, audit replay, risk-graded approval gates, and budget guards.
- **Observability Pro** — Distributed tracing dashboard preconfigured against the pipeline's command-stage spans, with end-to-end latency budgets per stage.
- **Advanced multi-region tenancy** — Active-active multi-region routing for tenant data, cross-region failover for the process engine and audit log.

These are extensions of the architecture above, not parallel runtimes. The OSS distribution is fully self-sufficient for single-region production deployments.
:::


## Next steps

- **The execution contract** — [Command pipeline](/docs/en/core-concepts/command-pipeline)
- **Authorization in depth** — [Permissions](/docs/en/core-concepts/permissions)
- **Plugin shape** — [Plugin manifest](/docs/en/core-concepts/plugin-manifest)
- **Calling AuraBoot from agents** — [Agent readiness](/docs/en/core-concepts/agent-readiness)
- **Why this shape, not another** — [Positioning](/docs/en/positioning)
